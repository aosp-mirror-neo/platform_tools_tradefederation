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
import com.android.tradefed.util.FileUtil;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Factory for creating and configuring {@link FuseMountManager} instances. */
public class FuseMountManagerFactory {

    private static Path mMountRoot;

    private final IRunUtil mRunUtil;
    private static final String CAS_CLIENT_NAME = "casviewer";
    private static final long MOUNT_TIMEOUT_IN_MS = 10_000; // Allow 10 seconds for mounting.
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
            initMountRoot();
            logMountPointCount();
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

        // Ensure casviewer is executable. It can be distributed without the execute bit set.
        FileUtil.ensureGroupRWX(casViewerPath.toFile());
        CasUtil.logCasClientVersion(casViewerPath);
        this.mCasFuseDriverProfile = new CasFuseDriverProfile(casViewerPath, MOUNT_TIMEOUT_IN_MS);
    }

    private void logAndThrow(String message) {
        CLog.e(message);
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_MOUNT_UNAVAILABLE_COUNT, 1);
        throw new IllegalStateException(message);
    }

    private static void initMountRoot() throws IOException {
        if (mMountRoot == null) {
            Path rootDir = Path.of("/tmp", "tf-cas-fuse-mount-root");
            Files.createDirectories(rootDir);
            mMountRoot = rootDir;
        }
    }

    private static void logMountPointCount() throws IOException {
        // Record the number of active mount points. This should be close to the maximum concurrent
        // TF tests on a host. A high number indicates a leak of mount points.
        try (Stream<Path> files = Files.list(mMountRoot)) {
            long count = files.count();
            CLog.d("Found %d active mount points in %s", count, mMountRoot);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_MOUNT_POINTS_COUNT, count);
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
        return manager;
    }

    public static List<FuseMountManager> getLiveManagers() {
        // Purge any weak references that have been cleared by the GC
        mManagers.removeIf(ref -> ref.get() == null);

        // Return a list of strong references from the remaining weak ones.
        return mManagers.stream().map(WeakReference::get).collect(Collectors.toList());
    }
}
