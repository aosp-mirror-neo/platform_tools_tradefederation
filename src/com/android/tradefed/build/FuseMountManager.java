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

package com.android.tradefed.build;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// FuseMountManager is a class that manages mounting and unmounting FUSE filesystems.
// It is responsible for starting and stopping the FUSE driver processes, and for ensuring that
// the mount points are ready to be used.
public class FuseMountManager {
    private final IFuseDriverProfile mDriverProfile;
    private final IRunUtil mRunUtil;
    // Map to associate a mount point with its running process
    private final Map<Path, Process> activeMounts = new ConcurrentHashMap<>();

    /**
     * Creates a new FuseMountManager with the given driver profile.
     *
     * @param driverProfile The driver profile to use.
     */
    public FuseMountManager(IFuseDriverProfile driverProfile) {
        this(driverProfile, RunUtil.getDefault());
    }

    /**
     * Creates a new FuseMountManager with the given driver profile and run util.
     *
     * @param driverProfile The driver profile to use.
     * @param runUtil The run util to use.
     */
    public FuseMountManager(IFuseDriverProfile driverProfile, IRunUtil runUtil) {
        this.mDriverProfile = driverProfile;
        this.mRunUtil = runUtil;
    }

    /**
     * Mounts the given source data path at the given mount point.
     *
     * @param sourceDataPath The source data path to mount.
     * @param mountPoint The mount point to mount the source data path at.
     * @throws IOException If the mount process fails to start.
     */
    public void mount(Path sourceDataPath, Path mountPoint) throws IOException {
        if (activeMounts.containsKey(mountPoint)) {
            throw new IllegalStateException("A mount already exists at:" + mountPoint);
        }

        try {
            mDriverProfile.validateSourceDataPath(sourceDataPath);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    String.format("Invalid source data path: %s", sourceDataPath.toAbsolutePath()),
                    e);
        }

        List<String> command = mDriverProfile.buildMountCommand(sourceDataPath, mountPoint);
        Process process = null;
        try (ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream()) {
            process = mRunUtil.runCmdInBackground(command, capturedOutput);
            if (process == null) { // This is unlikely to happen.
                throw new IOException(
                        String.format(
                                "FUSE process '%s' failed on startup. Output: %s",
                                command, capturedOutput.toString(StandardCharsets.UTF_8)));
            }
            // Wait for a short period to see if the process fails and exits immediately.
            if (process.waitFor(mDriverProfile.getMountTimeoutInMs(), TimeUnit.MILLISECONDS)) {
                throw new IOException(
                        String.format(
                                "FUSE process '%s' failed on startup. Exit Code: %d. Output: %s",
                                command,
                                process.exitValue(),
                                capturedOutput.toString(StandardCharsets.UTF_8)));
            }

            // Check to ensure the mount point is actually ready.
            if (!mDriverProfile.isMountPointReady(mountPoint)) {
                process.destroyForcibly(); // Kill the now-orphaned process.
                throw new IOException(
                        String.format(
                                "Mount process started but mount point is not ready. Output: %s",
                                capturedOutput.toString(StandardCharsets.UTF_8)));
            }
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly(); // Clean up on interruption.
            }
            Thread.currentThread().interrupt(); // Preserve the interrupted status.
            throw new IOException("Mount operation was interrupted.", e);
        }

        activeMounts.put(mountPoint, process);
        CLog.i("Done mounting \'%s\' at: %s", sourceDataPath, mountPoint);
    }

    /**
     * Unmounts the given mount point.
     *
     * @param mountPoint The mount point to unmount.
     */
    public void unmount(Path mountPoint) {
        Process process = activeMounts.get(mountPoint);
        if (process == null) {
            CLog.w("No mount found at: %s", mountPoint);
            return;
        }
        if (!process.isAlive()) {
            CLog.w("Already unmounted: %s", mountPoint);
            activeMounts.remove(mountPoint); // Atomically remove from map
            return;
        }

        CLog.d("Unmount: %s", mountPoint);

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
            activeMounts.remove(mountPoint); // Atomically remove from map
            if (process.isAlive()) {
                CLog.d(
                        "fusermount did not terminate the process for \'%s\'. Forcibly destroying"
                                + " it.",
                        mountPoint);
                process.destroyForcibly();
                // Don't wait for the process to terminate as there isn't much we can do if it
                // doesn't.
            }
        }

        CLog.d("Done unmounting: " + mountPoint);
    }

    /** Unmounts all tracked filesystems. */
    public void unmountAll() {
        List<Path> copyOfMountPoints = new ArrayList<>(activeMounts.keySet());
        for (Path mountPoint : copyOfMountPoints) {
            unmount(mountPoint);
        }
    }
}
