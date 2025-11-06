/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.tradefed.util;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.FuseMountManagerFactory;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.remote.ExtendedFile;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.CasUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.ResourceUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.TimeUtil;
import com.google.android.tradefed.build.LCUtil;
import com.google.android.tradefed.util.cas.CasOptions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.base.Suppliers;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

/** File downloader to download files from RBE CAS. */
public class CasFileDownloader implements IFileDownloader {
    /** This is the key for {@link HostOptions}'s service-account-json-key-file option. */
    // TODO(b/233963928) Use "gcs-json-key" for short-term verification. Use "cas-json-key" and
    // update lab host configurations once the E2E workflow is verified.
    @VisibleForTesting static final String CAS_JSON_KEY = "gcs-json-key";

    private static final String CAS_CLIENT_NAME = "cas_downloader";
    private static final String DIGEST_KEY_INSTANCE = "cas_instance";
    private static final String DIGEST_KEY_SERVICE = "cas_service";
    private static final String DIGEST_KEY_FILES = "files";
    // The prefix for the chunked version of the artifact in digest.
    // This must match the same as in "tools/content_uploader.py".
    private static final String CHUNKED_ARTIFACT_NAME_PREFIX = "_chunked_";
    private static final String CHUNKED_DIR_ARTIFACT_NAME_PREFIX = "_chunked_dir_";

    // Keys of the statics JSON file output by CAS command tool.
    private static final String STATS_COLD_SIZE_KEY = "size_cold";
    private static final String STATS_HOT_SIZE_KEY = "size_hot";
    private static final String STATS_COLD_FILE_KEY = "count_cold";
    private static final String STATS_HOT_FILE_KEY = "count_hot";
    private static final String STATS_DIR_RETRIEVE_KEY = "dir_retrieve_time_ms";
    private static final String STATS_DIR_PREPARE_KEY = "dir_prepare_time_ms";
    private static final String STATS_FILE_DOWNLOAD_KEY = "file_download_time_ms";
    private static final String STATS_CHUNK_RESTORE_KEY = "chunk_restore_time_ms";
    private static final String STATS_DOWNLOAD_ERROR_KEY = "download_error";
    private static final String STATS_E2E_TIME_KEY = "e2e_time_ms";
    private static final String STATS_NOTES_KEY = "notes";

    // Keys of environment variables to set local cache options.
    // Will read from the environment variables if set for easiler experiment, will move to
    // CasOptions eventually.
    private static final String CAS_LOCAL_CACHE_TYPE_ENV_VAR_KEY = "cas_local_cache_type";
    private static final String CAS_NFS_LOCAL_CACHE_CONCURRENCY_ENV_VAR_KEY =
            "cas_nfs_local_cache_concurrency";
    private static final String CAS_NFS_LOCAL_CACHE_CAPACITY_ENV_VAR_KEY =
            "cas_nfs_local_cache_capacity";

    private static final ReentrantLock sLock = new ReentrantLock(true);
    private static boolean mIsCasCacheDirValidated = false;

    @Nullable private final File mCasServiceAccountJson;
    private File mManifestPath;
    private final IFileDownloader mFallbackDownloader;
    private final boolean mCasAutoDiscovery;
    private final boolean mCasChunk;
    private final boolean mCasChunkDir;
    private final String mCasLocalCacheType = System.getenv(CAS_LOCAL_CACHE_TYPE_ENV_VAR_KEY);
    private final String mCasLocalCacheConcurrency =
            System.getenv(CAS_NFS_LOCAL_CACHE_CONCURRENCY_ENV_VAR_KEY);
    private Path mCasClient;
    private File mCacheDir;
    @Nullable private final File mTempFileRoot;
    private final Map<String, DigestInfo> mDigests = new ConcurrentHashMap<>();
    private final IRunUtil mRunUtil;

    // CasOptions Override
    private long mCasRpcTimeout = getCasOptions().getRPCTimeoutInSeconds();
    private long mCasMemoryLimit = getCasOptions().getMemoryLimitMiB();
    private long mCasGetCapabilitiesTimeout = getCasOptions().getCapabilitiesTimeoutInSeconds();
    private long mCasBatchUpdateBlobsTimeout =
            getCasOptions().getBatchUpdateBlobsTimeoutInSeconds();
    private long mCasBatchReadBlobsTimeout = getCasOptions().getBatchReadBlobsTimeoutInSeconds();
    private long mCasGetTreeTimeout = getCasOptions().getTreeTimeoutInSeconds();
    private long mCasConcurrency = getCasOptions().getCasConcurrency();

    // Cas download chunks processing options
    private boolean mCanFuseMountArtifact = false; // Overrules mCasKeepChunks and mCasChunksOnly.
    private boolean mCasKeepChunks = false; // Keep chunks around for chunked artifacts.
    private boolean mCasChunksOnly = false; // Download chunks only for chunked artifacts.

    private static final String ADC_CREDENTIALS_RES = "/util/adc_credentials.sh";
    private boolean mAdcCredentialsLogged = false;

    public void setCasRpcTimeout(
            long casRpcTimeout,
            long casGetCapabilitiesTimeout,
            long casBatchUpdateBlobsTimeout,
            long casBatchReadBlobsTimeout,
            long casGetTreeTimeout) {
        if (casRpcTimeout >= 0) {
            mCasRpcTimeout = casRpcTimeout;
        }
        if (casGetCapabilitiesTimeout >= 0) {
            mCasGetCapabilitiesTimeout = casGetCapabilitiesTimeout;
        }
        if (casBatchUpdateBlobsTimeout >= 0) {
            mCasBatchUpdateBlobsTimeout = casBatchUpdateBlobsTimeout;
        }
        if (casBatchReadBlobsTimeout >= 0) {
            mCasBatchReadBlobsTimeout = casBatchReadBlobsTimeout;
        }
        if (casGetTreeTimeout >= 0) {
            mCasGetTreeTimeout = casGetTreeTimeout;
        }
    }

    public void setCasMemoryLimit(long casMemoryLimit) {
        if (casMemoryLimit >= 0) {
            mCasMemoryLimit = casMemoryLimit;
        }
    }

    public void setCasConcurrency(long casConcurrency) {
        if (casConcurrency >= 0) {
            mCasConcurrency = casConcurrency;
        }
    }

    public void setCasChunksOptions(boolean casKeepChunks, boolean casChunksOnly) {
        if (!FuseMountManagerFactory.isFuseAvailable()) {
            // When fuse mounting is unavailable, it doesn't help to keep chunks around with either
            // option. Although the chunks can be converted to an uncompressed image, it doesn't
            // benefit (as it takes longer to make the image available).
            CLog.w("FUSE is not available. Fuse mounting options are ignored.");
            return;
        }

        boolean isFuseMountDisabledForCluster = getCasOptions().isForceDisabledFuseMount();
        if (isFuseMountDisabledForCluster) {
            CLog.i("CAS fuse mount is disabled by the cluster configuration.");
        }
        mCasKeepChunks = casKeepChunks && !isFuseMountDisabledForCluster;
        mCasChunksOnly = casChunksOnly && !isFuseMountDisabledForCluster;
        CLog.d(
                "[%s] Set CAS chunks options to: mCasKeepChunks := %s, mCasChunksOnly := %s",
                Integer.toHexString(System.identityHashCode(this)), mCasKeepChunks, mCasChunksOnly);
    }

    // The map to store locks for each digest. It ensures that the same file is only downloaded by
    // one instance at the same time to avoid unnecessary downloading that slows network.
    private static LoadingCache<String, ReentrantLock> sDigestLocks =
            CacheBuilder.newBuilder()
                    .expireAfterAccess(2, TimeUnit.HOURS)
                    .build(
                            new CacheLoader<String, ReentrantLock>() {
                                @Override
                                public ReentrantLock load(String key) throws Exception {
                                    return new ReentrantLock(true);
                                }
                            });

    private class DigestInfo {
        public String digest;
        public String casInstance;
        public String casService;

        public DigestInfo(String digest, String casInstance, String casService) {
            this.digest = digest;
            this.casInstance = casInstance;
            this.casService = casService;
        }
    }

    private final Supplier<Boolean> mUseCasDownloader =
            Suppliers.memoize(
                    () -> {
                        try {
                            initCasDownloader();
                            return true;
                        } catch (CasInitializationException e) {
                            CLog.w(
                                    "Exception occurs when initializing the CAS downloader. Will"
                                            + " only use the fallback downloader. Error message:");
                            CLog.w(e);
                            return false;
                        }
                    });

    /**
     * Constructor for CasFileDownloader.
     *
     * @param fallbackDownloader the file downloader to use if the CAS file downloader fails to be
     *     initialized or the file fails to be downloaded from CAS.
     * @param manifestPaths path to the manifest file, which contains CAS instance information and
     *     file digests.
     * @param casChunk whether to download the chunked version if both versions are available.
     * @param casChunkDir whether to download the chunked version uncompressed as a directory.
     */
    public CasFileDownloader(
            IFileDownloader fallbackDownloader,
            File manifestPath,
            boolean casAutoDiscovery,
            boolean casChunk,
            boolean casChunkDir) {
        this(
                fallbackDownloader,
                getCasOptions().getCasCacheDir(),
                manifestPath,
                casAutoDiscovery,
                casChunk,
                casChunkDir,
                GlobalConfiguration.getInstance()
                        .getHostOptions()
                        .getServiceAccountJsonKeyFiles()
                        .get(CAS_JSON_KEY),
                /* tempFileRoot= */ CurrentInvocation.getWorkFolder(),
                RunUtil.getDefault());
    }

    @VisibleForTesting
    public CasFileDownloader(
            IFileDownloader fallbackDownloader,
            File cacheDir,
            File manifestPath,
            boolean casAutoDiscovery,
            boolean casChunk,
            boolean casChunkDir,
            @Nullable File casServiceAccountJson,
            @Nullable File tempFileRoot,
            IRunUtil runUtil) {
        this.mFallbackDownloader = fallbackDownloader;
        this.mCacheDir = cacheDir;
        this.mManifestPath = manifestPath;
        this.mCasAutoDiscovery = casAutoDiscovery;
        this.mCasChunk = casChunk;
        this.mCasChunkDir = casChunkDir;
        this.mCasServiceAccountJson = casServiceAccountJson;
        this.mTempFileRoot = tempFileRoot;
        this.mRunUtil = runUtil;
    }

    /**
     * Initializes the downloader for downloading from CAS.
     *
     * @throws CasInitializationException if an error occurs when initializing the CAS downloader.
     */
    private void initCasDownloader() throws CasInitializationException {
        CLog.d("Initializing CAS downloader");
        if (!Files.isDirectory(mCacheDir.toPath())) {
            try {
                Files.createDirectories(mCacheDir.toPath());
            } catch (IOException e) {
                throw new CasInitializationException("Failed to create the cas cache.", e);
            }
        }

        try {
            mCasClient = extractCasClient(CAS_CLIENT_NAME).toPath();
        } catch (IOException e) {
            throw new CasInitializationException("The CAS client is invalid.", e);
        }

        try {
            if (mManifestPath == null) {
                if (mCasAutoDiscovery) {
                    return;
                } else {
                    throw new IOException("Missing CAS manifest reference.");
                }
            }
            loadDigests(mManifestPath.toPath());
        } catch (IOException | JSONException e) {
            throw new CasInitializationException("The CAS digest file is invalid", e);
        }
    }

    @Override
    public File downloadFile(String remoteFilePath) throws BuildRetrievalError {
        if (mUseCasDownloader.get()) {
            try (CloseableTraceScope ignored = new CloseableTraceScope("cas " + remoteFilePath)) {
                return downloadFromCas(remoteFilePath);
            } catch (CasDownloadException e) {
                CLog.w(
                        "Failed to download file %s from CAS, fallback to the original"
                                + " downloader.\n"
                                + "Error: %s",
                        remoteFilePath, e.getMessage());
                recordFileFailCountMetric(e);
            } catch (CasDigestConfigurationException e) {
                CLog.w(
                        "Failed to download file %s from CAS, fallback to the original"
                                + " downloader.\n"
                                + "Error: %s",
                        remoteFilePath, e.getMessage());
            }
        }
        File destFile = createTempFile(remoteFilePath, mTempFileRoot);
        try {
            mFallbackDownloader.downloadFile(remoteFilePath, destFile);
            return destFile;
        } catch (BuildRetrievalError e) {
            FileUtil.recursiveDelete(destFile);
            throw e;
        }
    }

    private void recordFileFailCountMetric(CasDownloadException e) {
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_FILE_FAIL_COUNT, 1);
        if (e.getMessage().contains("rpc error: code = NotFound")) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_FILE_NOT_FOUND_COUNT, 1);
        }
    }

    @Override
    public void downloadFile(String relativeRemotePath, File destFile) throws BuildRetrievalError {
        if (mUseCasDownloader.get()) {
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope("cas " + relativeRemotePath)) {
                downloadFromCas(
                        relativeRemotePath,
                        destFile.toPath(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        true);
                return;
            } catch (CasDownloadException e) {
                CLog.w(
                        "Failed to download file %s from CAS, fallback to the original"
                                + " downloader. Error: %s",
                        relativeRemotePath, e.getMessage());
                recordFileFailCountMetric(e);
            } catch (CasDigestConfigurationException e) {
                CLog.w(
                        "Failed to download file %s from CAS, fallback to the original"
                                + " downloader. Error: %s",
                        relativeRemotePath, e.getMessage());
            }
        }
        mFallbackDownloader.downloadFile(relativeRemotePath, destFile);
    }

    @Override
    public void downloadFile(String remoteFilePath, File destFile, long startOffset, long size)
            throws BuildRetrievalError {
        // Partial download fallback directly. CAS downloader only supports download directories
        // with includeFilters and excludeFilters.
        mFallbackDownloader.downloadFile(remoteFilePath, destFile, startOffset, size);
    }

    private boolean canUseCasDownloader(String remoteFilePath, Boolean hasFilters) {
        // File filtering is supported only if downloaded uncompressed without chunking.
        // Device images are available as zip files or uncompressed chunked on CAS.
        if (hasFilters) {
            Map<String, String> attributes = LCUtil.parseAttributeLine(remoteFilePath);
            String baseName = extractFromLCPath(attributes, remoteFilePath);
            boolean isDeviceImage = baseName.contains("-img-");
            return !isDeviceImage;
        }
        return true;
    }

    @Override
    public void downloadZippedFiles(
            File destDir,
            String remoteFilePath,
            List<String> includeFilters,
            List<String> excludeFilters)
            throws BuildRetrievalError, IOException {
        if (excludeFilters == null) {
            excludeFilters = new ArrayList<>();
        }
        if (includeFilters == null) {
            includeFilters = new ArrayList<>();
        }
        boolean hasFilters = includeFilters.size() + excludeFilters.size() > 0;
        if (mUseCasDownloader.get() && canUseCasDownloader(remoteFilePath, hasFilters)) {
            try (CloseableTraceScope ignored = new CloseableTraceScope("cas " + remoteFilePath)) {
                downloadFromCas(
                        remoteFilePath, destDir.toPath(), includeFilters, excludeFilters, false);
                return;
            } catch (CasDownloadException e) {
                CLog.w(
                        "Failed to download file %s with include/exclude filters from CAS, "
                                + "fallback to the original downloader. Error: %s",
                        remoteFilePath, e.getMessage());
                recordFileFailCountMetric(e);
            } catch (CasDigestConfigurationException e) {
                CLog.w(
                        "Failed to download file %s with include/exclude filters from CAS, "
                                + "fallback to the original downloader. Error: %s",
                        remoteFilePath, e.getMessage());
            }
        }
        // if partial downloading is not enabled, or fail to download from CAS, fallback to the
        // original downloader.
        mFallbackDownloader.downloadZippedFiles(
                destDir, remoteFilePath, includeFilters, excludeFilters);
    }

    private File downloadFromCas(String remoteFilePath)
            throws CasDownloadException, CasDigestConfigurationException, BuildRetrievalError {
        File destDir;
        try {
            destDir = FileUtil.createTempDir(remoteFilePath, mTempFileRoot);
        } catch (IOException e) {
            throw new CasDownloadException(
                    String.format(
                            "Failed to create temp directory for downloading file %s",
                            remoteFilePath),
                    e);
        }
        try {
            downloadFromCas(
                    remoteFilePath,
                    destDir.toPath(),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    false);
            return handleFileDownload(destDir, remoteFilePath);
        } catch (CasDownloadException | CasDigestConfigurationException | BuildRetrievalError e) {
            FileUtil.recursiveDelete(destDir);
            throw e;
        }
    }

    private File handleFileDownload(File downloadDir, String remoteFilePath) {
        if (downloadDir.isDirectory()) {
            File[] children = downloadDir.listFiles();
            if (children.length == 1
                    && children[0]
                            .getName()
                            .equals(
                                    new File(remoteFilePath)
                                            .getName()
                                            .replaceAll(CHUNKED_ARTIFACT_NAME_PREFIX, "")
                                            .replaceAll(CHUNKED_DIR_ARTIFACT_NAME_PREFIX, ""))) {
                CLog.d(
                        "Downloaded a non-directory file entry '%s' with CAS",
                        children[0].getName());
                return children[0];
            }
        }
        return downloadDir;
    }

    private void downloadFromCas(
            final String remoteFilePath,
            Path destDir,
            List<String> includeFilters,
            List<String> excludeFilters,
            boolean inplaceFile)
            throws CasDownloadException, CasDigestConfigurationException, BuildRetrievalError {
        // Sometimes the given remote file path is in the format of Launch Control path (e.g.
        // downloading from Android Build API). Extracting the file name here is to match the file
        // name key in the CAS digest map.
        boolean hasFilters = includeFilters.size() + excludeFilters.size() > 0;
        String fileName = completeMissingDigest(remoteFilePath, hasFilters);
        mCanFuseMountArtifact = canFuseMountArtifact(fileName);
        CommandResult result;
        long startTime = System.currentTimeMillis();

        File statsFile;
        try {
            statsFile = File.createTempFile("cas_download_stats", ".json", mTempFileRoot);
        } catch (IOException e) {
            throw new CasDownloadException("Fail to create the stats file.", e);
        }

        boolean shouldUseCache = false;
        File cacheDir = mCacheDir;
        ReentrantLock lock = sLock;
        // Multiple download tasks are allowed to run
        // concurrently. But if one task is downloading the exact same file, we will block other
        // download tasks. We will wait up to 20 minutes. If the previous downloader has not
        // completed yet, download the file directly.
        long lockTimeoutSecs = 30;
        if (includeFilters.isEmpty() && excludeFilters.isEmpty()) {
            try {
                lock = sDigestLocks.get(mDigests.get(fileName).digest);
                lockTimeoutSecs = getCasOptions().getDownloadTimeoutMs() / 1000L;
            } catch (ExecutionException e) {
                CLog.w("Exception caught when getting a CAS downloading lock: %s", e.getMessage());
                // Ignore the error, directly start the download task.
                lock = new ReentrantLock();
            }
        } else {
            // When using parallel and partial download, let the locking happen inside CAS
            lock = null;
            shouldUseCache = true;
        }

        try (CloseableTraceScope syncroTime = new CloseableTraceScope("cas_wait_time")) {
            if (lock != null) {
                try {
                    shouldUseCache = lock.tryLock(lockTimeoutSecs, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // Set shouldUseCache to "false" to use another temp dir for cache if
                    // timeout to get lock.
                    CLog.e(e);
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.CAS_LOCK_TIMEOUTS, fileName);
                    shouldUseCache = false;
                }
                if (!shouldUseCache) {
                    try {
                        // TODO(b/298972282) Cas Downloader V2 supports downloading without cache.
                        // We can remove the -cache-dir if it use a separate cache.
                        cacheDir = FileUtil.createTempDir("cas-temp-dir");
                    } catch (IOException e) {
                        CLog.e(e);
                        throw new HarnessRuntimeException(
                                e.getMessage(), e, InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
                    }
                }
            }
        } finally {
            long waitTime = System.currentTimeMillis() - startTime;
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_WAIT_TIME, waitTime);
            CLog.i("Got CAS downloader lock after %s", TimeUtil.formatElapsedTime(waitTime));
        }
        String destination = destDir.toAbsolutePath().toString();
        // Always download to a dedicated dir
        destination = destination + "_cas";
        try {
            result =
                    runCasClient(
                            fileName,
                            getInvocationId(remoteFilePath),
                            mCasClient.toAbsolutePath().toString(),
                            cacheDir.getAbsolutePath(),
                            destination,
                            statsFile.getAbsolutePath(),
                            includeFilters,
                            excludeFilters);
        } finally {
            if (shouldUseCache) {
                if (lock != null) {
                    lock.unlock();
                }
            } else {
                FileUtil.recursiveDelete(cacheDir);
            }
        }
        try {
            if (result.getStderr() != null) {
                if (result.getStderr().contains("no space left on device")) {
                    throw new BuildRetrievalError(
                            String.format("No space left on device.\n%s", result.getStderr()),
                            InfraErrorIdentifier.NO_DISK_SPACE);
                }
                if (result.getStderr().contains("rpc error: code = PermissionDenied")) {
                    // Log the ADC credentials for troubleshooting.
                    logAdcCredentials();
                }
            }
            if (CommandStatus.TIMED_OUT.equals(result.getStatus())) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.CAS_TIMEOUT_COUNT, 1);
                throw new CasDownloadException(
                        String.format(
                                "Timeout to download from CAS after %s.",
                                TimeUtil.formatElapsedTime(
                                        getCasOptions().getDownloadTimeoutMs())));
            }
            if (!CommandStatus.SUCCESS.equals(result.getStatus()) || result.getExitCode() != 0) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.CAS_CACHE_FALLBACK_COUNT, 1);
                throw new CasDownloadException(
                        String.format(
                                "Command fail. Command status: %s\n"
                                        + "Command exit code: %d\n"
                                        + "Command stdout: %s\n"
                                        + "Command stderr: %s",
                                result.getStatus(),
                                result.getExitCode(),
                                result.getStdout(),
                                result.getStderr()));
            }

            if (new File(destination).exists()) {
                try (CloseableTraceScope ignored = new CloseableTraceScope("hardlink_in_place")) {
                    if (inplaceFile) {
                        destDir.toFile().delete();
                        try {
                            File downloaded = handleFileDownload(new File(destination), fileName);
                            if (downloaded.isDirectory()) {
                                FileUtil.recursiveHardlink(downloaded, destDir.toFile());
                            } else {
                                FileUtil.hardlinkFile(downloaded, destDir.toFile());
                            }
                        } catch (IOException e) {
                            CLog.e(e);
                            throw new CasDownloadException("Failed to download file inplace", e);
                        }
                    } else {
                        try {
                            FileUtil.recursiveHardlink(
                                    new File(destination), destDir.toFile(), true);
                        } catch (IOException e) {
                            CLog.e(e);
                            throw new CasDownloadException("Failed to link to destination", e);
                        }
                    }
                }
            }
        } finally {
            // Clean the temporary download location
            FileUtil.recursiveDelete(new File(destination));
            // Only ensure we log the download time (even in case of exception)
            long elapsedTime = System.currentTimeMillis() - startTime;
            CLog.i(
                    "Download time for %s with CAS: %s",
                    fileName, TimeUtil.formatElapsedTime(elapsedTime));
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_START_TIMESTAMP, startTime);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_TIME, elapsedTime);

            try (CloseableTraceScope ignored = new CloseableTraceScope("report_cas_errors")) {
                exportDownloadErrors(statsFile, remoteFilePath);
            } catch (IOException | JSONException e) {
                CLog.w(
                        "Fail to parse download statics %s. Message",
                        statsFile.getAbsolutePath(), e.getMessage());
            }
        }
        try (CloseableTraceScope ignored = new CloseableTraceScope("report_cas_metrics")) {
            extractDownloaderMetricsFromStdout(result.getStdout());
            exportDownloadStats(statsFile);
        } catch (IOException | JSONException e) {
            CLog.w(
                    "Fail to parse download statics %s. Message",
                    statsFile.getAbsolutePath(), e.getMessage());
        }
        if (!includeFilters.isEmpty() || !excludeFilters.isEmpty()) {
            String includes =
                    includeFilters.stream()
                            .map(filter -> "include-filters=" + filter)
                            .collect(Collectors.joining("&"));
            String excludes =
                    excludeFilters.stream()
                            .map(filter -> "exclude-filters=" + filter)
                            .collect(Collectors.joining("&"));
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_FILES,
                    // String metric values are separated with ',' internally. Replace ',' with ';'
                    // to replace(",", " ") to avoid parsing mistakes.
                    String.format(
                            "%s:partial-download?%s&%s",
                            fileName, includes.replace(",", ";"), excludes.replace(",", ";")));
        } else {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_FILES, fileName);
        }
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_FILE_SUCCESS_COUNT, 1);
        if (mCasLocalCacheConcurrency != null) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_NFS_LOCAL_CACHE_CONCURRENCY,
                    Integer.parseInt(mCasLocalCacheConcurrency));
        }
        if (mCasLocalCacheType != null) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_LOCAL_CACHE_TYPE, mCasLocalCacheType);
        }
    }

    private void logAdcCredentials() {
        if (mAdcCredentialsLogged) {
            return;
        }
        mAdcCredentialsLogged = true;

        String scriptPath = "";
        try {
            // This script is used to check the ADC credentials inside the VM.
            File scriptFile = FileUtil.createTempFile("adc_credentials", ".sh");
            scriptPath = scriptFile.getAbsolutePath();
            ResourceUtil.extractResourceToFile(ADC_CREDENTIALS_RES, scriptFile);
            FileUtil.ensureGroupRWX(scriptFile);
        } catch (IOException e) {
            CLog.e(e);
            return;
        }
        CommandResult result = mRunUtil.runTimedCmd(10000, scriptPath);
        CLog.i("ADC credentials inside VM. %s", result.toString());
    }

    private String getInvocationId(String remoteFilePath) {
        Map<String, String> attributes = LCUtil.parseAttributeLine(remoteFilePath);
        if (attributes == null) {
            return "caller=tradefed";
        }

        String bid = attributes.containsKey(LCUtil.BUILD_ID) ? attributes.get(LCUtil.BUILD_ID) : "";
        String branch = attributes.containsKey(LCUtil.BRANCH) ? attributes.get(LCUtil.BRANCH) : "";
        String flavor = attributes.containsKey(LCUtil.FLAVOR) ? attributes.get(LCUtil.FLAVOR) : "";
        return String.format("caller=tradefed,bid=%s,branch=%s,flavor=%s", bid, branch, flavor);
    }

    private boolean canFuseMountArtifact(String fileName) {
        // Enable fuse mount for chunked uncompressed artifacts only.
        // It could be enabled for chunked compressed artifacts if needed but it accounts for a
        // insignificant percentage. To enable it, verify the double fuse mounting (fuze-zip on top
        // of this) functions as expected and is reliable.
        return fileName.startsWith(CHUNKED_DIR_ARTIFACT_NAME_PREFIX);
    }

    private CommandResult runCasClient(
            String fileName,
            String invocationId,
            String client,
            String cacheDir,
            String destDir,
            String statsFile,
            List<String> includeFilters,
            List<String> excludeFilters) {
        List<String> commandArgs = new ArrayList<>();
        commandArgs.add(client);

        boolean logMetricsIfNotExecutable = true;
        CasUtil.ensureCasClientExecutable(Path.of(client), logMetricsIfNotExecutable);

        String maxCacheSizEnvVar = System.getenv(CAS_NFS_LOCAL_CACHE_CAPACITY_ENV_VAR_KEY);
        String maxCacheSize =
                maxCacheSizEnvVar == null
                        ? String.valueOf(getCasOptions().getMaxCacheSize())
                        : maxCacheSizEnvVar;
        List<String> commonArgs =
                new ArrayList<>(
                        Arrays.asList(
                                "-invocation-id",
                                invocationId,
                                "-cache-dir",
                                cacheDir,
                                "-cache-max-size",
                                maxCacheSize,
                                "-digest",
                                mDigests.get(fileName).digest,
                                "-cas-instance",
                                mDigests.get(fileName).casInstance,
                                "-dir",
                                destDir,
                                "-dump-json",
                                statsFile));
        addCasClientOptions(commandArgs);

        commandArgs.addAll(commonArgs);
        // Avoid using hardlink for NFS local cache as it cross filesystem.
        if (mCasLocalCacheType != null && mCasLocalCacheType.equals("NFS")) {
            commandArgs.add("-nouse-hardlink");
        }
        if (!Strings.isNullOrEmpty(mDigests.get(fileName).casService)) {
            commandArgs.add("-cas-addr");
            commandArgs.add(mDigests.get(fileName).casService);
        }
        if (mCasServiceAccountJson != null && mCasServiceAccountJson.isFile()) {
            commandArgs.add("-service-account-json");
            commandArgs.add(mCasServiceAccountJson.getAbsolutePath());
        } else {
            CLog.i(
                    "The service account key is not specified or is not valid, using "
                            + "Application Default Credentials instead");
            commandArgs.add("-use-adc");
        }
        commandArgs.add("-cache-lock");
        for (String filter : includeFilters) {
            commandArgs.add("-include-filters");
            commandArgs.add(filter);
        }
        for (String filter : excludeFilters) {
            commandArgs.add("-exclude-filters");
            commandArgs.add(filter);
        }
        CommandResult result =
                mRunUtil.runTimedCmd(
                        getCasOptions().getDownloadTimeoutMs(), commandArgs.toArray(new String[0]));
        String stdout = result.getStdout();
        CLog.i(
                "[%s] Output when downloading the file %s:\n%s\n%s",
                Integer.toHexString(System.identityHashCode(this)),
                fileName,
                stdout,
                result.getStderr());
        return result;
    }

    private void addCasClientOptions(List<String> commandArgs) {
        if (mCasMemoryLimit > 0) {
            commandArgs.add("-memory-limit");
            commandArgs.add(String.valueOf(mCasMemoryLimit));
        }
        if (mCasConcurrency > 0) {
            commandArgs.add("-cas-concurrency");
            commandArgs.add(String.valueOf(mCasConcurrency));
        }
        if (mCasRpcTimeout > 0) {
            commandArgs.add("-rpc-timeout");
            commandArgs.add(String.valueOf(mCasRpcTimeout) + "s");
        }
        if (mCasGetCapabilitiesTimeout > 0) {
            commandArgs.add("-get-capabilities-timeout");
            commandArgs.add(String.valueOf(mCasGetCapabilitiesTimeout) + "s");
        }
        if (mCasBatchUpdateBlobsTimeout > 0) {
            commandArgs.add("-batch-update-blobs-timeout");
            commandArgs.add(String.valueOf(mCasBatchUpdateBlobsTimeout) + "s");
        }
        if (mCasBatchReadBlobsTimeout > 0) {
            commandArgs.add("-batch-read-blobs-timeout");
            commandArgs.add(String.valueOf(mCasBatchReadBlobsTimeout) + "s");
        }
        if (mCasGetTreeTimeout > 0) {
            commandArgs.add("-get-tree-timeout");
            commandArgs.add(String.valueOf(mCasGetTreeTimeout) + "s");
        }
        if (mCanFuseMountArtifact) {
            if (mCasChunksOnly) {
                commandArgs.add("-chunks-only");
            } else if (mCasKeepChunks) {
                commandArgs.add("-keep-chunks");
            }
        }
    }

    private void extractDownloaderMetricsFromStdout(String stdout) {
        if (stdout == null) {
            return;
        }
        // Regex pattern to extract time took for acquiring local cache lock
        Pattern pattern = Pattern.compile("(?<=took\\s)\\d+\\.\\d+");
        for (String line : stdout.split("\n")) {
            if (!line.contains("local cache lock: lock obtained")) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                Long lockAcquiredTimeMs = Long.parseLong(matcher.group());
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.CAS_DOWNLOAD_ACQUIRE_LOCAL_CACHE_LOCK_TIME,
                        lockAcquiredTimeMs);
            } else {
                CLog.e("Failed to extract lock acquire time from %s", line);
            }
            break;
        }
    }

    private void exportDownloadErrors(File statsFile, String remoteFilePath)
            throws IOException, JSONException {
        String jsonString = new String(Files.readAllBytes(statsFile.toPath()));
        JSONObject statsObject = new JSONObject(jsonString);
        String casError = statsObject.optString(STATS_DOWNLOAD_ERROR_KEY);
        if (!casError.isEmpty()) {
            CLog.d("Cas error: %s", casError);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_ERRORS, casError.replace(",", " "));
            Map<String, String> attributes = LCUtil.parseAttributeLine(remoteFilePath);
            String build_id = attributes.get(LCUtil.BUILD_ID);
            if (build_id == null) {
                build_id = "";
                CLog.d("Build id unknown for remote file path: %s", remoteFilePath);
            }
            // Log download error with build id so they are paired. This is useful to filter out
            // digest-not-found errors due to old builds already evicted from CAS.
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_ERROR_BUILD_ID, build_id);
        }
    }

    private void exportDownloadStats(File statsFile) throws IOException, JSONException {
        String jsonString = new String(Files.readAllBytes(statsFile.toPath()));
        CLog.d("Cas stats: %s", jsonString);
        JSONObject statsObject = new JSONObject(jsonString);
        // In golang, json.Marshal() omits fields with default values. It is desirable to Log
        // default values for missing fields to equalize the number of data itmes for each workunit
        // which makes the analyzing of ANTS data less error prone.
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_COLD_BYTES,
                statsObject.optString(STATS_COLD_SIZE_KEY, "0"));
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_HOT_BYTES,
                statsObject.optString(STATS_HOT_SIZE_KEY, "0"));
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_HOT_FILES_COUNT,
                statsObject.optString(STATS_HOT_FILE_KEY, "0"));
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_COLD_FILES_COUNT,
                statsObject.optString(STATS_COLD_FILE_KEY, "0"));
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_DIR_RETRIEVE_TIME,
                statsObject.optString(STATS_DIR_RETRIEVE_KEY, "0"));
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_DIR_PREPARE_TIME,
                statsObject.optString(STATS_DIR_PREPARE_KEY, "0"));
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_FILE_DOWNLOAD_TIME,
                statsObject.optString(STATS_FILE_DOWNLOAD_KEY, "0"));
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_CHUNK_RESTORE_TIME,
                statsObject.optString(STATS_CHUNK_RESTORE_KEY, "0"));
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CAS_DOWNLOAD_E2E_TIME,
                statsObject.optString(STATS_E2E_TIME_KEY, "0"));
        if (statsObject.has(STATS_NOTES_KEY) && !statsObject.optString(STATS_NOTES_KEY).isEmpty()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CAS_DOWNLOAD_NOTES,
                    statsObject.getString(STATS_NOTES_KEY));
        }
    }

    private void loadDigests(Path digestPath) throws JSONException, IOException {
        CLog.d("Loading digest: %s", digestPath);
        String jsonString = new String(Files.readAllBytes(digestPath));
        JSONObject digestObject = new JSONObject(jsonString);
        String casInstance = digestObject.getString(DIGEST_KEY_INSTANCE);
        String casService = digestObject.optString(DIGEST_KEY_SERVICE);
        JSONObject filesObject = digestObject.getJSONObject(DIGEST_KEY_FILES);
        for (Iterator<String> files = filesObject.keys(); files.hasNext(); ) {
            String file = files.next();
            mDigests.put(
                    file, new DigestInfo(filesObject.getString(file), casInstance, casService));
        }
    }

    protected File extractCasClient(String clientName) throws IOException {
        // Use CAS bundled in Tradefed if available
        String tfDir = System.getProperty("TF_JAR_DIR");
        if (!Strings.isNullOrEmpty(tfDir)) {
            File client = new File(tfDir, clientName);
            if (client.exists()) {
                boolean logMetricsIfNotExecutable = false; // Do not log as it's expected.
                CasUtil.ensureCasClientExecutable(client.toPath(), logMetricsIfNotExecutable);
                CasUtil.logCasClientVersion(
                        client.toPath(),
                        version -> {
                            InvocationMetricLogger.addInvocationMetrics(
                                    InvocationMetricKey.CAS_VERSION, version);
                        });
                return client;
            }
        }
        throw new IOException(String.format("Could not find the %s client", clientName));
    }

    /**
     * Extracts the file name from a Launch Control path.
     *
     * <p>For example, git_master-linux-test_suites_x86_64/8499441/android-cts.zip ->
     * android-cts.zip
     *
     * @param filePath the file path in the format of Launch Control path (example:
     *     git_master-linux-test_suites_x86_64/8499441/android-cts.zip)
     * @return the extracted file name. If the given file path is failed to parse, returns the file
     *     name without any change
     */
    private String extractFromLCPath(Map<String, String> attributes, String filePath) {
        if (attributes == null || !attributes.containsKey(LCUtil.FILENAME)) {
            return filePath;
        }
        return attributes.get(LCUtil.FILENAME);
    }

    /**
     * Creates a unique file on temporary disk to house downloaded file with given path.
     *
     * <p>Constructs the file name based on base file name from path
     *
     * @param remoteFilePath the remote path to construct the name from
     */
    // TODO(b/233963928) This util is copied across multiple downloaders (http://shortn/_siKBPzrgi5)
    // It could be moved to FileUtil.
    File createTempFile(String remoteFilePath, File rootDir) throws BuildRetrievalError {
        try {
            // create a unique file.
            File tmpFile = FileUtil.createTempFileForRemote(remoteFilePath, rootDir);
            // now delete it so name is available
            tmpFile.delete();
            return tmpFile;
        } catch (IOException e) {
            String msg = String.format("Failed to create tmp file for %s", remoteFilePath);
            throw new BuildRetrievalError(msg, e);
        }
    }

    private static final class CasInitializationException extends Exception {
        private CasInitializationException(String message) {
            super(message);
        }

        private CasInitializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class CasDigestConfigurationException extends Exception {
        private CasDigestConfigurationException(String message) {
            super(message);
        }

        private CasDigestConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class CasDownloadException extends Exception {
        private CasDownloadException(String message) {
            super(message);
        }

        private CasDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private boolean mismatchManifest(File manifestFile, Map<String, String> attributes) {
        if (manifestFile == null) {
            return true;
        }
        if (!(manifestFile instanceof ExtendedFile)) {
            return false;
        }
        ExtendedFile eFile = (ExtendedFile) manifestFile;
        if (!attributes.get(LCUtil.FLAVOR).equals(eFile.getBuildTarget())) {
            return true;
        }
        if (!attributes.get(LCUtil.BRANCH).equals(eFile.getBranch())) {
            return true;
        }
        return false;
    }

    private String completeMissingDigest(String remoteFilePath, boolean hasFilters)
            throws CasDigestConfigurationException {
        Map<String, String> attributes = LCUtil.parseAttributeLine(remoteFilePath);
        String baseName = extractFromLCPath(attributes, remoteFilePath);
        Iterator<String> fileNameIterator = List.of(baseName).iterator();
        if (!hasFilters) {
            fileNameIterator = getArtifactNames(baseName).iterator();
        }
        String fileName = fileNameIterator.next();
        if (mCasAutoDiscovery
                && (mManifestPath == null || mismatchManifest(mManifestPath, attributes))) {
            CLog.d("Auto-discovery enabled, backfilling digest");
            String altname =
                    remoteFilePath.replaceAll(attributes.get(LCUtil.FILENAME), "cas_digests.json");
            // Try opportunistically to fetch the digest but only a single time
            // to avoid mixing origin
            try {
                mManifestPath = mFallbackDownloader.downloadFile(altname);
                loadDigests(mManifestPath.toPath());
            } catch (BuildRetrievalError e) {
                CLog.e("No digest available on %s", altname);
            } catch (IOException | JSONException e) {
                CLog.e(e);
            }
        }
        while (!mDigests.containsKey(fileName)) {
            if (!fileNameIterator.hasNext()) {
                throw new CasDigestConfigurationException(
                        String.format(
                                "Digests don't contain the remote file %s. artifact name: %s",
                                remoteFilePath, baseName));
            }
            fileName = fileNameIterator.next();
        }
        CLog.w("Download %s (casChunkDir: %s, casChunk: %s)", fileName, mCasChunkDir, mCasChunk);
        return fileName;
    }

    private ArrayList<String> getArtifactNames(String baseName) {
        ArrayList<String> artifactNames = new ArrayList<>();
        if (mCasChunkDir) {
            artifactNames.add(CHUNKED_DIR_ARTIFACT_NAME_PREFIX + baseName);
            artifactNames.add(CHUNKED_ARTIFACT_NAME_PREFIX + baseName);
        } else if (mCasChunk) {
            artifactNames.add(CHUNKED_ARTIFACT_NAME_PREFIX + baseName);
        }
        artifactNames.add(baseName);
        return artifactNames;
    }

    public static CasOptions getCasOptions() {
        CasOptions options =
                (CasOptions)
                        GlobalConfiguration.getInstance()
                                .getConfigurationObject(CasOptions.TYPE_NAME);
        if (options == null) {
            CLog.w("No 'cas-downloader' global object was configured. Using default one.");
            // Return default values if object is not configured
            options = new CasOptions();
        } else {
            if (options != null && !mIsCasCacheDirValidated) {
                synchronized (CasOptions.class) {
                    if (!mIsCasCacheDirValidated) {
                        File cacheDir = options.getCasCacheDir();
                        String cacheDirPath = cacheDir != null ? cacheDir.getAbsolutePath() : null;
                        if (cacheDirPath != null
                                && !cacheDirPath.startsWith(System.getProperty("java.io.tmpdir"))) {
                            InvocationMetricLogger.addInvocationMetrics(
                                    InvocationMetricKey.CAS_CACHE_DIR_NOT_IN_TEMP_DIR,
                                    cacheDirPath);
                            CLog.w(
                                    "The cas-downloader cache directory is not under the default"
                                        + " temporary directory. This may result in unexpected"
                                        + " behavior. Please ensure the cache directory is under"
                                        + " the default temporary directory. %s",
                                    cacheDirPath);
                        }
                        mIsCasCacheDirValidated = true;
                    }
                }
            }
        }
        return options;
    }
}
