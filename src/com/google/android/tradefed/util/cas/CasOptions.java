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
package com.google.android.tradefed.util.cas;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;

import com.google.common.base.Strings;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Hostwide configuration options for the CAS downloader client. */
@OptionClass(alias = "cas-downloader", global_namespace = false)
public class CasOptions {
    public static final String TYPE_NAME = "cas-downloader";
    // In delegation, keep the parent cache
    private static final Path DEFAULT_CAS_CACHE_DIR =
            Paths.get(
                    Strings.isNullOrEmpty(System.getProperty("original.tf.tmpdir"))
                            ? System.getProperty("java.io.tmpdir")
                            : System.getProperty("original.tf.tmpdir"),
                    "cas_cache");

    @Option(name = "force-disable", description = "Disable CAS at the cluster level")
    private boolean mForceDisable = false;

    @Option(
            name = "force-disable-fuse-mount",
            description = "Disable CAS fuse mount at the cluster level")
    private boolean mForceDisableFuseMount = false;

    @Option(
            name = "cache-max-size",
            description = "Maximum size for the host cache allocated for CAS")
    private long mCacheMaxSize = 80L * 1024 * 1024 * 1024; // 80GB

    @Option(
            name = "download-timeout",
            description = "Timeout for each CAS download. 0 means no timeout.",
            isTimeVal = true)
    private long mDownloadTimeoutMs = TimeUnit.MINUTES.toMillis(60);

    @Option(
            name = "cas-cache-dir",
            description =
                    "the directory for caching downloaded flashing files. Should be on the same"
                            + " filesystem as java.io.tmpdir.  Consider changing the java.io.tmpdir"
                            + " property if you want to move downloads to a different filesystem.")
    private File mCasCacheDir = DEFAULT_CAS_CACHE_DIR.toFile();

    @Option(
            name = "memory-limit",
            description = "Soft memory limit in MiB for cas downloader (respected by Go runtime)")
    private long mMemoryLimit = 0L; // 0 for no memory limit.

    @Option(name = "rpc-timeout", description = "Default RPC timeout in seconds")
    private long mRPCTimeout = 180L; // 0 for default.

    @Option(
            name = "get-capabilities-timeout",
            description = "RPC timeout for GetCapabilities in seconds.")
    private long mRPCGetCapabilitiesTimeout = 0L; // 0 for default.

    @Option(
            name = "batch-update-blobs-timeout",
            description = "RPC timeout for BatchUpdateBlobs in seconds.")
    private long mRPCBatchUpdateBlobsTimeout = 0L; // 0 for default.

    @Option(
            name = "batch-read-blobs-timeout",
            description = "RPC timeout for BatchReadBlobs in seconds.")
    private long mRPCBatchReadBlobsTimeout = 180L; // 0 for default.

    @Option(name = "get-tree-timeout", description = "RPC timeout for GetTree in seconds")
    private long mRPCGetTreeTimeout = 120L; // 0 for default.

    @Option(
            name = "cas-concurrency",
            description = "The maximum number of concurrent download operations")
    private long mCasConcurrency = 0; // 0 for default.

    public boolean isForceDisabled() {
        return mForceDisable;
    }

    public boolean isForceDisabledFuseMount() {
        return mForceDisableFuseMount;
    }

    public long getMaxCacheSize() {
        return mCacheMaxSize;
    }

    public long getDownloadTimeoutMs() {
        return mDownloadTimeoutMs;
    }

    public File getCasCacheDir() {
        return mCasCacheDir;
    }

    public long getMemoryLimitMiB() {
        return mMemoryLimit;
    }

    public long getCasConcurrency() {
        return mCasConcurrency;
    }

    public long getRPCTimeoutInSeconds() {
        return mRPCTimeout;
    }

    public long getCapabilitiesTimeoutInSeconds() {
        return mRPCGetCapabilitiesTimeout;
    }

    public long getBatchUpdateBlobsTimeoutInSeconds() {
        return mRPCBatchUpdateBlobsTimeout;
    }

    public long getBatchReadBlobsTimeoutInSeconds() {
        return mRPCBatchReadBlobsTimeout;
    }

    public long getTreeTimeoutInSeconds() {
        return mRPCGetTreeTimeout;
    }
}
