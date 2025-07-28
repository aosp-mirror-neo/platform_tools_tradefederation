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
import com.android.tradefed.util.CasUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// FuseMountManager is a class that manages mounting and unmounting FUSE filesystems.
// It is responsible for starting and stopping the FUSE driver processes, and for ensuring that
// the mount points are ready to be used.
public class FuseMountManager {
    // Root directory for all CAS log dirs (one per mount) for easy checking of failed cleanups.
    private static final Path CAS_LOG_ROOT = Path.of("/tmp/tf-cas-fuse-log-root");
    private static final String CAS_VIEWER_NAME = "casviewer";
    private final IFuseDriverProfile mDriverProfile;
    private final IRunUtil mRunUtil;
    // Map to associate a source data path with its corresponding mount information (mount point and
    // process)
    private final Map<Path, MountInfo> mActiveMounts = new ConcurrentHashMap<>();
    private final Path mMountRoot; // The base path for creation of mount points.

    // Timeouts for unmount operations
    private static final long FUSEMOUNT_TIMEOUT_MS = 2 * 1000; // For 'fusermount -u'.
    private static final long UMOUNT_LAZY_TIMEOUT_MS = 5 * 1000; // For 'umount -l'.
    private static final long PROCESS_WAIT_TIMEOUT_MS = 1000; // For fuse process to exit.

    /** MountInfo is a class that holds information about a mounted FUSE filesystem. */
    private static class MountInfo {
        Path mMountPoint;
        Path mLogDir;
        Process mProcess;

        MountInfo(Path mountPoint, Path logDir, Process process) {
            mMountPoint = mountPoint;
            mLogDir = logDir;
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
                            "Validation failed for source data path '%s'.",
                            sourceDataPath.toAbsolutePath()),
                    e);
        }

        Path mountPoint = null;
        Path logDir = null;
        Process process = null;
        try (ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream()) {
            mountPoint = createUniqueMountPoint(sourceDataPath);
            logDir = CasUtil.createUniqueCasLogDir(CAS_LOG_ROOT);
            List<String> command =
                    mDriverProfile.buildMountCommand(sourceDataPath, mountPoint, logDir);

            process = mRunUtil.runCmdInBackground(command, capturedOutput);
            if (process == null) { // Unlikely to happen.
                throw new IOException("runCmdInBackground returned a null process.");
            }

            final long pollIntervalMs = 50;
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
                            "Done mounting '%s' at '%s' (dir count: %d), took %d ms",
                            sourceDataPath, mountPoint, getMountPointDirCount(), duration);
                    mActiveMounts.put(sourceDataPath, new MountInfo(mountPoint, logDir, process));
                    return mountPoint; // Exit the method on success.
                }

                if (process.waitFor(pollIntervalMs, TimeUnit.MILLISECONDS)) {
                    // FAILURE: Process exited prematurely.
                    throw new IOException(
                            String.format(
                                    "Process '%s' failed on startup. Exit Code: %d. Output:\n%s",
                                    command,
                                    process.exitValue(),
                                    capturedOutput.toString(StandardCharsets.UTF_8)));
                }
            } while (System.currentTimeMillis() < deadline);

            // FAILURE: Timed out waiting for mount point to be ready.
            logTimeout(sourceDataPath, capturedOutput.toString(StandardCharsets.UTF_8), logDir);
            throw new IOException(String.format("Mount process started but timed out."));
        } catch (IOException | InterruptedException e) {
            // FAILURE: Handle all exceptions from the try block here.
            if (process != null && process.isAlive()) {
                destroyProcess(process, mountPoint);
            }
            cleanUp(mountPoint, logDir);

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

    private void logTimeout(Path sourceDataPath, String output, Path logDir) {
        InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.CAS_MOUNT_TIMEOUT_COUNT, 1);
        String log = CasUtil.getCasLogFromDir(logDir, CAS_VIEWER_NAME);
        CLog.i("Mounting of '%s' timed out. Output:\n%s\nLog:\n%s", sourceDataPath, output, log);
    }

    /**
     * Unmounts the filesystem associated with the given source data path. This gracefully
     * terminates the FUSE process and cleans up the mount point directory.
     *
     * @param sourceDataPath The source data path to unmount.
     */
    public void unmount(Path sourceDataPath) {
        final long startTime = System.currentTimeMillis();
        String sourceDataPathString = sourceDataPath.toAbsolutePath().toString();
        MountInfo mountInfo = mActiveMounts.get(sourceDataPath);

        if (mountInfo == null) {
            CLog.w("No mount found for '%s'. Skipping unmount.", sourceDataPathString);
            return;
        }

        Path mountPoint = mountInfo.mMountPoint;
        Path logDir = mountInfo.mLogDir;
        Process process = mountInfo.mProcess;

        try {
            if (process.isAlive()) {
                gracefullyUnmount(process, mountPoint);
            } else {
                CLog.d("Process for '%s' is already dead. Skipping 'fusermount -u'.", mountPoint);
            }

        } finally {
            mActiveMounts.remove(sourceDataPath); // Atomically remove from map
            cleanUp(mountPoint, logDir); // Best-effort directory cleanup

            CLog.d(
                    "Done unmounting '%s' (dir count: %d), took %d ms",
                    sourceDataPathString,
                    getMountPointDirCount(),
                    System.currentTimeMillis() - startTime);
        }
    }

    private void gracefullyUnmount(Process process, Path mountPoint) {
        try {
            CommandResult fusermountResult =
                    mRunUtil.runTimedCmd(
                            FUSEMOUNT_TIMEOUT_MS,
                            "fusermount",
                            "-u",
                            mountPoint.toAbsolutePath().toString());
            if (fusermountResult.getStatus() != CommandStatus.SUCCESS) {
                CLog.w(
                        "fusermount -u '%s' failed. Status: %s. Stderr:\n%s\nStdout:\n%s",
                        mountPoint,
                        fusermountResult.getStatus(),
                        fusermountResult.getStderr(),
                        fusermountResult.getStdout());
            }

            // Allow the process a brief moment to exit on its own after fusermount.
            // This returns immediately if the process is already dead.
            process.waitFor(PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            CLog.e("Error executing fusermount -u for '%s'. Error: %s", mountPoint, e.getMessage());
        }

        if (process.isAlive()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_UNMOUNT_GRACEFUL_FAILURE_COUNT, 1);
            destroyProcess(process, mountPoint);
        }
    }

    private void destroyProcess(Process process, Path mountPoint) {
        CLog.w("Attempting graceful process termination for FUSE driver backing '%s'.", mountPoint);
        process.destroy(); // Sends SIGTERM
        try {
            process.waitFor(PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupt status
            CLog.e("Interrupted while waiting for FUSE driver to exit. Error: %s", e.getMessage());
        }

        if (process.isAlive()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_UNMOUNT_SIGTERM_FAILURE_COUNT, 1);
            destroyProcessForcibly(process, mountPoint);
        }
    }

    private void destroyProcessForcibly(Process process, Path mountPoint) {
        CLog.e("FUSE driver for '%s' is still alive. Forcibly destroying process.", mountPoint);
        process.destroyForcibly(); // Sends SIGKILL
        try {
            process.waitFor(PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CLog.e(
                    "Interrupted while waiting for forceful destruction of FUSE driver."
                            + " Error: %s",
                    e.getMessage());
        }

        if (process.isAlive()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_UNMOUNT_SIGKILL_FAILURE_COUNT, 1);
        }
        // SIGTERM can't be caught, so we need to do a lazy unmount to avoid leaking the mount
        // point.
        lazyUnmount(mountPoint);
    }

    private void lazyUnmount(Path mountPoint) {
        try {
            CLog.d("Performing lazy unmount for '%s' to ensure cleanup.", mountPoint);
            CommandResult lazyUnmountResult =
                    mRunUtil.runTimedCmd(
                            UMOUNT_LAZY_TIMEOUT_MS,
                            "umount",
                            "-l",
                            mountPoint.toAbsolutePath().toString());
            if (lazyUnmountResult.getStatus() != CommandStatus.SUCCESS) {
                String error = lazyUnmountResult.getStderr();
                if (error.contains("not mounted")) {
                    // This is not an error, expected if the mount point was already cleaned up.
                    return;
                }
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.CAS_UNMOUNT_LAZZY_UNMOUNT_FAILURE_COUNT, 1);
                CLog.e(
                        "Lazy unmount for '%s' failed. Status: %s. Stderr:\n%s\nStdout:\n%s",
                        mountPoint,
                        lazyUnmountResult.getStatus(),
                        lazyUnmountResult.getStderr(),
                        lazyUnmountResult.getStdout());
            }
        } catch (Exception e) {
            CLog.e("Error executing umount -l for '%s'. Error: %s", mountPoint, e.getMessage());
        }
    }

    /**
     * Unmounts all tracked filesystems in parallel.
     *
     * <p>This method unmounts all tracked filesystems in parallel using a thread pool. It is
     * intended to be used when all filesystems need to be unmounted, such as when shutting down the
     * FuseMountManager.
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
            // Adjusted timeout for unmountAll to be sum of individual unmount steps + buffer
            long totalTimeout =
                    FUSEMOUNT_TIMEOUT_MS + PROCESS_WAIT_TIMEOUT_MS + UMOUNT_LAZY_TIMEOUT_MS + 5000;
            executor.shutdown(); // Stop accepting new tasks.
            if (!executor.awaitTermination(totalTimeout, TimeUnit.MILLISECONDS)) {
                CLog.e("UnmountAll timed out. Some FUSE processes may be" + " orphaned.");
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
     * Deletes the mount point and log dir. This method logs any exceptions but does not throw, as
     * this is a best-effort cleanup.
     *
     * @param mountPoint The mount point to delete.
     * @param logDir The log directory to delete.
     */
    protected void cleanUp(Path mountPoint, Path logDir) {
        final long timeoutMs = 1000;
        final long deadline = System.currentTimeMillis() + timeoutMs;
        final long intervalMs = 100;

        long start = System.currentTimeMillis();
        List<Path> toDelete = new ArrayList<>(Arrays.asList(mountPoint, logDir));
        do {
            try {
                if (Files.exists(logDir)) {
                    FileUtil.recursiveDelete(logDir.toFile()); // Doesn't throw.
                }
                Files.deleteIfExists(mountPoint);
                if (toDelete.stream().filter(Files::exists).count() == 0) {
                    CLog.d("Done cleaning up, took %d ms", System.currentTimeMillis() - start);
                    return;
                }
            } catch (IOException e) {
                // This is expected if the kernel hasn't released the mount yet.
                CLog.v(
                        "Deletion of mount point '%s' failed, will retry. Error: %s",
                        mountPoint, e.getMessage());
            }

            if (System.currentTimeMillis() >= deadline) {
                break;
            }

            mRunUtil.sleep(intervalMs);
        } while (System.currentTimeMillis() < deadline);

        List<Path> failedToDelete =
                toDelete.stream().filter(Files::exists).collect(Collectors.toList());
        if (!failedToDelete.isEmpty()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_UNMOUNT_CLEANUP_FAILURE_COUNT, 1);
            CLog.w(
                    "Failed to delete directories: %s, took %d ms",
                    failedToDelete.toString(), System.currentTimeMillis() - start);
        }
    }

    /**
     * Returns the number of directories under the mount root.
     *
     * <p>This method counts the number of directories under the mount root. It is used to monitor
     * the number of mount points created by the FUSE driver.
     *
     * @return The number of directories under the mount root.
     */
    private long getMountPointDirCount() {
        try (Stream<Path> stream = Files.list(mMountRoot)) {
            return stream.filter(Files::isDirectory).count();
        } catch (IOException e) {
            CLog.e("Failed to get mount point directory count. Error: %s", e.getMessage());
            return -1L;
        }
    }
}
