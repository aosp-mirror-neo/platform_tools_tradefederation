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
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Factory for creating and configuring {@link FuseMountManager} instances. */
public class FuseMountManagerFactory {

    private static final Path mMountRoot = Path.of("/tmp", "tf-cas-fuse-mount-root");

    private final IRunUtil mRunUtil;
    private static final String CAS_CLIENT_NAME = "casviewer";
    private static final long MOUNT_TIMEOUT_IN_MS = 20_000; // Allow 20 seconds for mounting.
    private final CasFuseDriverProfile mCasFuseDriverProfile;
    private static final List<WeakReference<FuseMountManager>> mManagers =
            Collections.synchronizedList(new ArrayList<>());

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
        boolean logMetricsIfNotExecutable = false;  // Do not log as it's expected.
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
     * Checks the health of the mount points.
     *
     * <p>If the number of active mount points is high, it triggers a cleanup of stale mount points.
     */
    private static void checkMountPointHealth() {
        // The number of active mount points should be close to the maximum concurrent TF tests on a
        // host. A high number indicates stale mount points and a need for cleanup.

        long count = 0;
        try (Stream<Path> files = Files.list(mMountRoot)) {
            count = files.count();
        } catch (IOException e) {
            CLog.e("Failed to count mount points: %s", e.getMessage());
            return;
        }
        CLog.d("Found %d active mount points in %s", count, mMountRoot);
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_MOUNT_POINTS_COUNT, count);

        // A physical TF host typically has 14 devices and runs at most that many tests in parallel.
        // Allow for 2x that many concurrent tests.
        if (count > 30) {
            removeStaleMountPoints();
        }
    }

    /**
     * Removes stale mount points older than 24 hours.
     *
     * <p>TF invocations don't last more than 24 hours. Remove any mount points older than that.
     */
    @VisibleForTesting
    private static void removeStaleMountPoints() {
        long twentyFourHoursAgo = System.currentTimeMillis() - 1000 * 24 * 60 * 60;
        try (Stream<Path> files = Files.list(mMountRoot)) {
            AtomicInteger attempted = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            files.filter(p -> p.toFile().lastModified() < twentyFourHoursAgo)
                    .forEach(
                            p -> {
                                attempted.incrementAndGet();
                                if (!deleteMountPointNoThrow(p)) {
                                    failed.incrementAndGet();
                                }
                            });
            if (attempted.get() > 0) {
                CLog.d(
                        "Attempted to remove %d stale mount points. %d failed.",
                        attempted.get(), failed.get());
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.CAS_UNMOUNT_DEFENSIC_CLEANUP_FAILURE_COUNT,
                        failed.get());
            }
        } catch (IOException e) {
            CLog.e("Failed to remove stale mount points: %s", e.getMessage());
        }
    }

    /**
     * Deletes the mount point, logging any errors.
     *
     * @return {@code true} if the mount point was deleted, {@code false} otherwise.
     */
    private static boolean deleteMountPointNoThrow(Path mountPoint) {
        try {
            Files.delete(mountPoint);
            return true;
        } catch (IOException e) {
            CLog.e("Failed to delete stale mount point %s: %s", mountPoint, e.getMessage());
            return false;
        }
    }

    /**
     * Checks if the FUSE kernel module appears to be loaded and ready.
     *
     * @return {@code true} if /dev/fuse exists, {@code false} otherwise.
     */
    @VisibleForTesting
    protected boolean isFuseAvailable() {
        File fuseDevice = new File("/dev/fuse");
        return fuseDevice.exists();
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

    public static List<FuseMountManager> getLiveManagers() {
        // Purge any weak references that have been cleared by the GC
        mManagers.removeIf(ref -> ref.get() == null);

        // Return a list of strong references from the remaining weak ones.
        return mManagers.stream().map(WeakReference::get).collect(Collectors.toList());
    }
}
