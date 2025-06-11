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
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.google.common.base.Strings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Factory for creating and configuring {@link FuseMountManager} instances. */
public class FuseMountManagerFactory {

    private static Path mMountRoot;

    private final IRunUtil mRunUtil;
    private final String mTradefedJarDir;
    private static final long MOUNT_TIMEOUT_IN_MS = 10_000; // Allow 10 seconds for mounting.

    private static FuseMountManagerFactory sInstance;

    // Private constructor for singleton pattern or controlled instantiation.
    private FuseMountManagerFactory() throws IOException {
        initMountRoot();
        this.mRunUtil = RunUtil.getDefault();
        this.mTradefedJarDir = System.getProperty("TF_JAR_DIR");
        if (Strings.isNullOrEmpty(this.mTradefedJarDir)) {
            throw new IOException("TF_JAR_DIR system property is not set.");
        }
    }

    private static void initMountRoot() throws IOException {
        if (mMountRoot == null) {
            Path rootDir = Path.of("/tmp", "tf-cas-fuse-mount-root");
            Files.createDirectories(rootDir);
            mMountRoot = rootDir;
        }
    }

    /** Gets the singleton instance of the factory. */
    public static synchronized FuseMountManagerFactory getInstance() throws IOException {
        if (sInstance == null) {
            sInstance = new FuseMountManagerFactory();
        }
        return sInstance;
    }

    /**
     * Creates a new FuseMountManager configured for CAS (Content-Addressable Storage) FUSE driver.
     *
     * @return a configured {@link FuseMountManager}.
     * @throws IOException if the CAS driver cannot be found or configured.
     */
    public FuseMountManager createCasFuseMountManager() throws IOException {
        Path casDriverPath = getCasClientPath("casviewer");
        logCasClientVersion(casDriverPath); // Log the version of casviewer.
        CasFuseDriverProfile profile = new CasFuseDriverProfile(casDriverPath, MOUNT_TIMEOUT_IN_MS);
        return new FuseMountManager(profile, mRunUtil, mMountRoot);
    }

    /**
     * Resolves and validates the path to a CAS client executable within the Tradefed runfiles. The
     * returned path is guaranteed to be executable. A cas client like casviewer can be distributed
     * without the executable bit in its file mode.
     *
     * @param clientName The name of the client executable (e.g., "casviewer").
     * @return The absolute {@link Path} to the client executable.
     * @throws IOException if the TF_JAR_DIR is not set or the client is not found.
     */
    private Path getCasClientPath(String clientName) throws IOException {
        Path casClientDir = Path.of(mTradefedJarDir);
        Path client = casClientDir.resolve(clientName);
        if (!Files.exists(client)) {
            throw new IOException(
                    String.format("Could not find CAS client: %s in %s", clientName, casClientDir));
        }
        FileUtil.ensureGroupRWX(client.toFile()); // Ensure casviewer is executable.
        return client;
    }

    /**
     * Executes the CAS client with the --version flag and logs the result. This is a diagnostic
     * helper to confirm which driver is being used.
     *
     * @param clientPath The path to the CAS client executable.
     */
    private void logCasClientVersion(Path clientPath) {
        CommandResult cr =
                mRunUtil.runTimedCmd(5000, clientPath.toAbsolutePath().toString(), "--version");
        if (cr.getStatus() != CommandStatus.SUCCESS) {
            CLog.e(
                    "Failed to extract cas client version. Status: %s\nStdout: %s\nStderr: %s",
                    cr.getStatus(), cr.getStdout(), cr.getStderr());
            return;
        }

        String prefix = "version: ";
        String clientName = clientPath.getFileName().toString();
        String version = cr.getStdout().trim();
        if (version.startsWith(prefix)) {
            version = version.substring(prefix.length());
            CLog.d("%s, version: %s", clientName, version);
        } else {
            CLog.d("%s, version: unknown", clientName);
        }
    }

}
