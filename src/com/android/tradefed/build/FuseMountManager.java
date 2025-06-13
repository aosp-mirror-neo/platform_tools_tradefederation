/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// TODO(frankfeng): iterate to support sharing of mount points for same source data paths across
// multiple clients. The typical use case is a DeviceBuildInfo and its copies (clones).

package com.android.tradefed.build;

import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// FuseMountManager is a class that manages mounting and unmounting FUSE filesystems.
// It is responsible for starting and stopping the FUSE driver processes, and for ensuring that
// the mount points are ready to be used.
public class FuseMountManager {
    private final IFuseDriverProfile mDriverProfile;
    private final IRunUtil mRunUtil;
    // Map to associate a source data path with its corresponding mount information (mount point and
    // process)
    private final Map<Path, MountInfo> mActiveMounts = new ConcurrentHashMap<>();
    private final Path mMountRoot; // The base path for creation of mount points.

    /** MountInfo is a class that holds information about a mounted FUSE filesystem. */
    private static class MountInfo {
        Path mMountPoint;
        Process mProcess;

        MountInfo(Path mountPoint, Process process) {
            mMountPoint = mountPoint;
            mProcess = process;
        }
    }

    /**
     * Creates a new FuseMountManager with the given driver profile and default run util and mount
     * root.
     *
     * @param driverProfile The driver profile to use.
     */
    public FuseMountManager(IFuseDriverProfile driverProfile) {
        this(driverProfile, RunUtil.getDefault(), Path.of("/tmp/cas_mountpoints/"));
    }

    /**
     * Creates a new FuseMountManager with the given driver profile, run util, and mount root.
     *
     * @param driverProfile The driver profile to use.
     * @param runUtil The run util to use.
     * @param mountRoot The base path for creation of mount points.
     */
    public FuseMountManager(IFuseDriverProfile driverProfile, IRunUtil runUtil, Path mountRoot) {
        mDriverProfile = driverProfile;
        mRunUtil = runUtil;
        mMountRoot = mountRoot;
    }

    /**
     * Checks if the given path is a valid source that can be mounted by the current FUSE profile.
     *
     * @param sourceDataPath The path to check.
     * @return {@code true} if the source is valid, {@code false} otherwise.
     */
    public boolean isValidSourceData(Path sourceDataPath) {
        if (sourceDataPath == null) {
            return false;
        }

        try {
            mDriverProfile.validateSourceDataPath(sourceDataPath);
            return true;

        } catch (IOException e) {
            // Log for debugging but return false as the source is not valid/usable.
            CLog.v("Validation failed for source data path: %s", sourceDataPath);
            return false;
        }
    }

    /**
     * Mounts the given source data path to a uniquely created mount point. This method is
     * idempotent: if the same source path is already mounted, it returns the existing mount point.
     *
     * @param sourceDataPath The source data path to mount.
     * @return The {@link Path} to the new mount point where the source is available.
     * @throws IOException If the mount process fails to start or times out.
     * @throws InterruptedException If the mounting thread is interrupted.
     */
    public Path mount(Path sourceDataPath) throws IOException, InterruptedException {
        final long startTime = System.currentTimeMillis();

        if (mActiveMounts.containsKey(sourceDataPath)) {
            return mActiveMounts.get(sourceDataPath).mMountPoint;
        }

        try {
            mDriverProfile.validateSourceDataPath(sourceDataPath);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    String.format(
                            "Validation failed for source data path: %s",
                            sourceDataPath.toAbsolutePath()),
                    e);
        }

        Path mountPoint = null;
        Process process = null;
        try (ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream()) {
            mountPoint = createUniqueMountPoint(sourceDataPath);
            CLog.i(
                    "Mount '%s' at: %s",
                    sourceDataPath.toAbsolutePath().toString(),
                    mountPoint.toAbsolutePath().toString());
            List<String> command = mDriverProfile.buildMountCommand(sourceDataPath, mountPoint);

            process = mRunUtil.runCmdInBackground(command, capturedOutput);
            if (process == null) { // Unlikely to happen.
                throw new IOException("runCmdInBackground returned a null process.");
            }

            final long pollIntervalMs = 100;
            final long deadline = System.currentTimeMillis() + mDriverProfile.getMountTimeoutInMs();
            do {
                if (mDriverProfile.isMountPointReady(mountPoint)) {
                    // SUCCESS: The mount is ready.
                    long duration = System.currentTimeMillis() - startTime;
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.CAS_MOUNT_SUCCESS_COUNT, 1);
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.CAS_MOUNT_SUCCESS_TIME_MS, duration);
                    CLog.i(
                            "Done mounting '%s' at: %s (took %d ms)",
                            sourceDataPath, mountPoint, duration);
                    mActiveMounts.put(sourceDataPath, new MountInfo(mountPoint, process));
                    return mountPoint; // Exit the method on success.
                }

                if (process.waitFor(pollIntervalMs, TimeUnit.MILLISECONDS)) {
                    // FAILURE: Process exited prematurely.
                    throw new IOException(
                            String.format(
                                    "FUSE process '%s' failed on startup. Exit Code: %d. Output:"
                                            + " %s",
                                    command,
                                    process.exitValue(),
                                    capturedOutput.toString(StandardCharsets.UTF_8)));
                }
            } while (System.currentTimeMillis() < deadline);

            // FAILURE: Timed out waiting for the mount point to become ready.
            process.destroyForcibly();
            deleteMountPoint(mountPoint);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_MOUNT_TIMEOUT_COUNT, 1);
            throw new IOException(
                    String.format(
                            "Mount process started but mount point is not ready (timed out)."
                                    + " Output: %s",
                            capturedOutput.toString(StandardCharsets.UTF_8)));

        } catch (IOException | InterruptedException e) {
            // FAILURE: Handle all exceptions from the try block here.
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteMountPoint(mountPoint);

            // Log generic failure metrics for any exception path.
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_MOUNT_FAILURE_COUNT, 1);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_MOUNT_FAILURE_TIME_MS,
                    System.currentTimeMillis() - startTime);

            // Re-throw the exception to the caller.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new IOException("Mount operation was interrupted.", e);
            }
            throw e;
        }
    }

    /**
     * Unmounts the filesystem associated with the given source data path. This gracefully
     * terminates the FUSE process and cleans up the mount point directory.
     *
     * @param sourceDataPath The source data path to unmount.
     */
    public void unmount(Path sourceDataPath) {
        MountInfo mountInfo = mActiveMounts.get(sourceDataPath);
        if (mountInfo == null) {
            CLog.w("No mount found for: %s", sourceDataPath.toAbsolutePath().toString());
            return;
        }

        CLog.d("Unmount: %s", sourceDataPath.toAbsolutePath().toString());

        Path mountPoint = mountInfo.mMountPoint;
        Process process = mountInfo.mProcess;
        if (!process.isAlive()) {
            CLog.w("Already unmounted: %s", sourceDataPath.toAbsolutePath().toString());
            deleteMountPoint(mountPoint);
            mActiveMounts.remove(sourceDataPath); // Atomically remove from map
            return;
        }

        try {
            // Attempt graceful shutdown first (the preferred way)
            CommandResult result =
                    mRunUtil.runTimedCmd(
                            10000, "fusermount", "-u", mountPoint.toAbsolutePath().toString());
            if (result.getStatus() != CommandStatus.SUCCESS) {
                CLog.w(
                        "Graceful shutdown with 'fusermount -u' failed. Stderr: "
                                + result.getStderr());
                // Wait for up to 2 seconds to see if the process terminated despite the error
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }

        } catch (InterruptedException e) {
            CLog.e(
                    "fusermount command failed or timed out. Will attempt to kill the process"
                            + " directly. Error: %s",
                    e.getMessage());
        } finally {
            // Forcibly kill the driver process
            if (process.isAlive()) {
                CLog.d(
                        "fusermount did not terminate the process for \'%s\'. Forcibly destroying"
                                + " it.",
                        mountPoint.toAbsolutePath().toString());
                process.destroyForcibly();
                // Don't wait for the process to terminate as there isn't much we can do if it
                // doesn't.
            }
            mActiveMounts.remove(sourceDataPath); // Atomically remove from map
            deleteMountPoint(mountPoint);
        }

        CLog.d("Done unmounting: %s", sourceDataPath.toAbsolutePath().toString());
    }

    /** Unmounts all tracked filesystems in parallel.
     *
     * <p>This method unmounts all tracked filesystems in parallel using a thread pool. It is
     * intended to be used when all filesystems need to be unmounted, such as when shutting down
     * the FuseMountManager.
     */
    public void unmountAll() {
        if (mActiveMounts.keySet().isEmpty()) {
            return;
        }

        // This can be the typical case - just unmount the one mount point.
        if (mActiveMounts.keySet().size() == 1) {
            unmount(mActiveMounts.keySet().iterator().next());
            return;
        }

        Set<Path> copyOfSourceDataPaths = Set.copyOf(mActiveMounts.keySet());

        // Choose ExecutorService over parallelStream() as this is IO bound.

        // Let's not use more than 10 threads, even if there are many mounts.
        int numThreads = Math.min(copyOfSourceDataPaths.size(), 10);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        CLog.i(
                "Unmounting %d filesystems in parallel with %d threads...",
                copyOfSourceDataPaths.size(), numThreads);

        // Submit each unmount task to the thread pool.
        copyOfSourceDataPaths.forEach(path -> executor.submit(() -> unmount(path)));

        // Shut down the executor and wait for tasks to complete.
        try {
            executor.shutdown(); // Stop accepting new tasks.
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                CLog.e(
                        "UnmountAll timed out after 15 seconds. Some FUSE processes may be"
                            + " orphaned.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            CLog.e("UnmountAll was interrupted.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        CLog.i("Finished unmountAll.");
    }

    /**
     * Creates a unique mount point for the given source data path.
     *
     * <p>This method creates a unique mount point for the given source data path by combining the
     * device image name and a random UUID. The mount point is created as a directory.
     *
     * @param sourceDataPath The source data path to create a mount point for.
     * @return The {@link Path} to the new mount point.
     * @throws IOException If the mount point cannot be created.
     */
    private Path createUniqueMountPoint(Path sourceDataPath) throws IOException {
        String uniqueFileName = getImageName(sourceDataPath) + "_" + UUID.randomUUID().toString();
        Path mountPoint = mMountRoot.resolve(uniqueFileName);
        if (!Files.exists(mountPoint)) { // Should always be true.
            Files.createDirectory(mountPoint);
        }
        return mountPoint;
    }

    /** Returns the image name from the given path. */
    private static String getImageName(Path path) {
        String fileName = path.getFileName().toString();

        int dotIndex = fileName.indexOf('.');
        if (dotIndex == -1) {
            return fileName;
        }

        return fileName.substring(0, dotIndex);
    }

    /**
     * Deletes the given mount point if it exists. This method logs any exceptions but does not
     * throw as this is a best-effort cleanup.
     *
     * @param mountPoint The mount point to delete.
     */
    private void deleteMountPoint(Path mountPoint) {
        if (mountPoint != null) {
            try {
                Files.delete(mountPoint);
            } catch (IOException e) {
                CLog.w("Failed to delete mount point %s", mountPoint.toAbsolutePath().toString());
            }
        }
    }
}
