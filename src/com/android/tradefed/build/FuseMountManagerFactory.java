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

import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CasUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Factory for creating and configuring {@link FuseMountManager} instances. */
public class FuseMountManagerFactory {

    private static final Path mMountRoot = Path.of("/tmp", "tf-cas-fuse-mount-root");
    // A lab host typically has 14 physical devices attched and can run that many tests in parallel.
    // Each test typically needs 1 device image mounted. Set the threshold to 30 to provide some
    // buffer. Adjust this if the assumption is no longer valid.
    private static final int MOUNT_POINT_COUNT_THRESHOLD = 30;
    // An invocation should not last more than 24 hours. A mount point not used in 24 hours is
    // considered stale and can be removed.
    private static final long STALE_MOUNT_POINT_THRESHOLD_MS = TimeUnit.HOURS.toMillis(24);
    private static final long HEALTH_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final ExecutorService cleanupExecutor =
            Executors.newSingleThreadExecutor(
                    new ThreadFactoryBuilder()
                            .setNameFormat("FuseMountManagerFactory-CleanupManager-%d")
                            .setDaemon(true) // Daemon threads won't block JVM exit
                            .build());
    private static final AtomicBoolean cleanupTaskRunning = new AtomicBoolean(false);
    private static long lastCheckTime = 0;

    private final IRunUtil mRunUtil;
    private static final String CAS_CLIENT_NAME = "casviewer";
    private static final long MOUNT_TIMEOUT_IN_MS = 20_000; // Allow 20 seconds for mounting.
    private final CasFuseDriverProfile mCasFuseDriverProfile;
    private static final List<WeakReference<FuseMountManager>> mManagers =
            Collections.synchronizedList(new ArrayList<>());
    private static Boolean mIsFuseAvailable = null;

    private static class LazyHolder {
        private static final FuseMountManagerFactory INSTANCE = new FuseMountManagerFactory();
    }

    @VisibleForTesting
    protected FuseMountManagerFactory() {
        this.mRunUtil = RunUtil.getDefault();

        try {
            if (!Files.exists(mMountRoot)) {
                Files.createDirectories(mMountRoot);
            }
        } catch (IOException e) {
            logAndThrow(String.format("Failed to initialize mount root: %s", e.getMessage()));
        }

        if (!isFuseAvailable()) {
            logAndThrow("FUSE kernel module is not available.");
        }

        String jarDir = System.getProperty("TF_JAR_DIR");
        if (Strings.isNullOrEmpty(jarDir)) {
            logAndThrow("TF_JAR_DIR system property is not set.");
        }

        Path casViewerPath = Path.of(jarDir).resolve(CAS_CLIENT_NAME);
        if (!Files.exists(casViewerPath)) {
            logAndThrow(String.format("Could not find '%s' in '%s'.", CAS_CLIENT_NAME, jarDir));
        }

        // Ensure `casviewer` is executable. It can be distributed without the execute bit set.
        boolean logMetricsIfNotExecutable = false; // Do not log as it's expected.
        CasUtil.ensureCasClientExecutable(casViewerPath, logMetricsIfNotExecutable);

        CasUtil.logCasClientVersion(
                casViewerPath,
                version -> {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.CAS_VIEWER_VERSION, version);
                });
        this.mCasFuseDriverProfile = new CasFuseDriverProfile(casViewerPath, MOUNT_TIMEOUT_IN_MS);
    }

    private void logAndThrow(String message) {
        CLog.e(message);
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_MOUNT_UNAVAILABLE_COUNT, 1);
        throw new IllegalStateException(message);
    }

    /**
     * Checks the health of the mount points and triggers a cleanup if necessary.
     *
     * <p>This method uses a two-level throttle to ensure the cleanup task is run at most once every
     * `HEALTH_CHECK_INTERVAL_MS` and that only one such task runs at a time.
     */
    private static void checkMountPointHealth() {
        // Attempt to acquire the lock. If another task is running, return.
        if (!cleanupTaskRunning.compareAndSet(false, true)) {
            CLog.d(
                    "Another cleanup task is already in progress. Last check started %d minutes"
                            + " ago.",
                    (System.currentTimeMillis() - lastCheckTime) / TimeUnit.MINUTES.toMillis(1));
            return;
        }

        // We have the lock. Check if we need to throttle.
        if (System.currentTimeMillis() - lastCheckTime < HEALTH_CHECK_INTERVAL_MS) {
            CLog.d(
                    "Health check throttled. Last check was %d minutes ago.",
                    (System.currentTimeMillis() - lastCheckTime) / TimeUnit.MINUTES.toMillis(1));
            cleanupTaskRunning.set(false);
            return;
        }

        // The throttle interval has passed and we have the lock. Update the time.
        lastCheckTime = System.currentTimeMillis();

        // The submitted task holds the responsibility to release the lock when it is finished.
        cleanupExecutor.submit(
                () -> {
                    try {
                        long count = 0;
                        try (Stream<Path> files = Files.list(mMountRoot)) {
                            count = files.count();
                        } catch (IOException e) {
                            CLog.e("Failed to count mount points: %s", e.getMessage());
                            return; // Exit the task on failure
                        }
                        CLog.d("Found %d active mount points in %s", count, mMountRoot);
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.CAS_MOUNT_POINTS_COUNT, count);

                        if (count > MOUNT_POINT_COUNT_THRESHOLD) {
                            CLog.d(
                                    "Mount point count > %s. Triggering stale mount point removal.",
                                    MOUNT_POINT_COUNT_THRESHOLD);
                            removeStaleMountPoints(mMountRoot);
                        }
                    } finally {
                        // Ensure the flag is reset when the task is complete.
                        cleanupTaskRunning.set(false);
                    }
                });
    }

    /**
     * Removes stale mount points older than STALE_MOUNT_POINT_THRESHOLD_MS.
     *
     * @param mountRoot The mount root directory to scan for stale mount points.
     */
    @VisibleForTesting
    protected static void removeStaleMountPoints(Path mountRoot) {
        long cutoffTime = System.currentTimeMillis() - STALE_MOUNT_POINT_THRESHOLD_MS;

        try (Stream<Path> files = Files.list(mountRoot)) {
            // Create a map to functionally count the successes and failures
            // We'll collect a Map<Boolean, Long> where true=success, false=failure
            Map<Boolean, Long> results =
                    files.filter(
                                    p -> {
                                        try {
                                            // Use Files.getLastModifiedTime directly on the Path
                                            // for efficiency
                                            return Files.getLastModifiedTime(p).toMillis()
                                                    < cutoffTime;
                                        } catch (IOException e) {
                                            if (Files.exists(p)) {
                                                // Log error if it still exists and leave it alone.
                                                CLog.e(
                                                        "Could not get last modified time for '%s'",
                                                        p, e);
                                            }
                                            return false;
                                        }
                                    })
                            .collect(
                                    Collectors.partitioningBy(
                                            p -> FuseMountManager.cleanUp(p, RunUtil.getDefault()),
                                            Collectors.counting()));

            long failed = results.getOrDefault(false, 0L);
            long stale = results.getOrDefault(true, 0L) + failed;

            if (stale > 0) {
                CLog.d("Attempted to remove %d stale mount points. %d failed.", stale, failed);
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.CAS_UNMOUNT_DEFENSIC_CLEANUP_STALE_COUNT, stale);
                if (failed > 0) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.CAS_UNMOUNT_DEFENSIC_CLEANUP_FAILURE_COUNT, failed);
                }
            } else {
                CLog.d("No stale mount points (not modified in the last 24 hours) found.");
            }
        } catch (IOException e) {
            CLog.e("Failed to remove stale mount points: %s", e.getMessage());
        }
    }

    /**
     * Checks if the FUSE kernel module appears to be loaded and ready.
     *
     * @return {@code true} if /dev/fuse exists, {@code false} otherwise.
     */
    public static boolean isFuseAvailable() {
        if (mIsFuseAvailable == null) {
            File fuseDevice = new File("/dev/fuse");
            mIsFuseAvailable = fuseDevice.exists();
        }
        return mIsFuseAvailable;
    }

    /** Gets the singleton instance of the factory. */
    public static synchronized FuseMountManagerFactory getInstance() {
        return LazyHolder.INSTANCE;
    }

    /**
     * Creates a new FuseMountManager configured for CAS (Content-Addressable Storage) FUSE driver.
     *
     * @return a configured {@link FuseMountManager}.
     * @throws IOException if the CAS driver cannot be found or configured.
     */
    public FuseMountManager createCasFuseMountManager() throws IOException {
        FuseMountManager manager =
                new FuseMountManager(mCasFuseDriverProfile, mRunUtil, mMountRoot);
        mManagers.add(new WeakReference<>(manager));
        checkMountPointHealth();
        return manager;
    }

    public static void shutdownCleanupExecutor() {
        CLog.i("Shutting down cleanup executor...");
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                CLog.w("Cleanup executor did not shut down gracefully. Forcing shutdown.");
                // This interrupts all active tasks and could lead to an inconsistent state. Since
                // the cleanup is defensive, this risk is acceptable.
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            CLog.w("Cleanup executor shutdown was interrupted. Forcing shutdown.");
            cleanupExecutor.shutdownNow();
            // Restore the interrupted status
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Cleans up any orphaned FUSE mounts.
     *
     * <p>This is intended to be called from a shutdown hook. It will unmount all live mounts.
     */
    public static void cleanUp() {
        // DeviceBuildInfo unmounts device images it owns on shutdown.
        // This is a best effort attempt to unmount orphaned mounts if any.

        // Purge any weak references that have been cleared by the GC.
        mManagers.removeIf(ref -> ref.get() == null);
        mManagers.stream()
                .map(WeakReference::get)
                .forEach(
                        manager -> {
                            // The null check is needed as the manager can be null if the weak
                            // reference is cleared by the GC after removeIf().
                            if (manager != null) {
                                manager.unmountAll();
                            }
                        });
    }
}
