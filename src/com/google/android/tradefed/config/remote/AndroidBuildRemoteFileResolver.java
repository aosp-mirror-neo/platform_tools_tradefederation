/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.google.android.tradefed.config.remote;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.FileDownloadCache;
import com.android.tradefed.build.FileDownloadCacheFactory;
import com.android.tradefed.build.FileDownloadCacheWrapper;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.config.DynamicRemoteFileResolver;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.remote.ExtendedFile;
import com.android.tradefed.config.remote.IRemoteFileResolver;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.invoker.tracing.TracePropagatingExecutorService;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.VersionParser;
import com.google.android.tradefed.build.AndroidBuildArtifactHelper;
import com.google.android.tradefed.build.AndroidBuildDownloader;
import com.google.android.tradefed.build.DeviceLaunchControlProvider;
import com.google.android.tradefed.build.LCUtil;
import com.google.android.tradefed.build.RemoteBuildInfo;
import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;
import com.google.android.tradefed.util.CasFileDownloader;
import com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Implementation of {@link IRemoteFileResolver} that allows downloading from Android Build Server.
 */
public class AndroidBuildRemoteFileResolver implements IRemoteFileResolver {

    @Option(
            name = "android-build-environment",
            description = "The environment from Android Build we want to download from.")
    private String mAndroidBuildEnv = "prod";

    @Option(name = "use-partial-download-cache", description = "Enable the partial download cache.")
    private boolean mUsePartialDownloadCache = false;

    @Option(
            name = "force-disable-cas",
            description = "Don't use cas for downloading test artifacts.")
    private boolean mForceDisableCas = false;

    /**
     * @deprecated use option force-disable-cas instead.
     */
    @Option(name = USE_CAS, description = "Whether or not to use CAS when downloading.")
    @Deprecated
    private boolean mUseCas = true;

    @Option(
            name = "force-disable-cas-fuse-mount",
            description = "Keed chunks around after cas download for chunked artifacts.")
    private boolean mForceDisableCasFuseMount = false;

    @Option(
            name = "cas-keep-chunks",
            description =
                    "Keed chunks around after cas download for chunked artifacts, for Tradefed"
                            + " feature rollout only.")
    private boolean mCasKeepChunks = false;

    @Option(
            name = "cas-chunks-only",
            description =
                    "Download chunks only for chunked artifacts, for Tradefed feature rollout"
                            + " only.")
    private boolean mCasChunksOnly = false;

    @Option(
            name = "force-disable-cas-chunk-dir",
            description =
                    "Whether to disable cas-chunk-dir feature. Opt out for tests that are "
                            + "not compatible with the feature.")
    private boolean mDisableCasChunkDir = false;

    @Option(name = "cas-memory-limit", description = "Soft memory limit in MiB for cas downloader")
    private long mCasMemoryLimit = -1L; // not set, 0 for no memory limit.

    @Option(
            name = "cas-concurrency",
            description = "The maximum number of concurrent download operations")
    private long mCasConcurrency = -1L; // not set, 0 for default.

    @Option(
            name = "cas-rpc-timeout",
            description = "Default RPC timeout in seconds for cas downloader")
    private long mCasRpcTimeout = -1L; // not set, 0 for default.

    @Option(name = "cas-get-capabilities-timeout", description = "RPC timeout for GetCapabilities")
    private long mCasGetCapabilitiesTimeout = -1L; // not set, 0 for default.

    @Option(
            name = "cas-batch-update-blobs-timeout",
            description = "RPC timeout for BatchUpdateBlob")
    private long mCasBatchUpdateBlobsTimeout = -1L; // not set, 0 for default.

    @Option(name = "cas-batch-read-blobs-timeout", description = "RPC timeout for BatchReadBlobs")
    private long mCasBatchReadBlobsTimeout = -1L; // not set, 0 for default.

    @Option(name = "cas-get-tree-timeout", description = "RPC timeout for GetTree")
    private long mCasGetTreeTimeout = -1L; // not set, 0 for default.

    @Option(
            name = ENABLE_RESOLVE_SYM_LINKS,
            description = "Enable relative symlinks inside zip files on partial download")
    private boolean mEnableResolveSymlinks = true;

    public static final String BUILD_ID_STRING = "buildid";
    public static final String BUILD_BRANCH_STRING = "branch";
    public static final String BUILD_TARGET_STRING = "target";
    public static final String BUILD_PATTERN_STRING = "pattern";
    public static final String OS_STRING = "linux";
    public static final String PROTOCOL = "ab";
    public static final String INCLUDE_FILTERS = "include_filters";
    public static final String EXCLUDE_FILTERS = "exclude_filters";
    public static final String USE_CAS = "use-cas";
    public static final String ENABLE_RESOLVE_SYM_LINKS = "enable-resolve-sym-links";

    private static final String DOWNLOAD_PATTERN_STRING =
            String.format(
                    "%s:/(?<%s>.*?)/(?<%s>.*?)/(?<%s>.*?)/(?<%s>.*$)",
                    PROTOCOL,
                    BUILD_BRANCH_STRING,
                    BUILD_TARGET_STRING,
                    BUILD_ID_STRING,
                    BUILD_PATTERN_STRING);
    public static final Pattern DOWNLOAD_PATTERN = Pattern.compile(DOWNLOAD_PATTERN_STRING);
    private static final String LATEST_BUILD_ID = "LATEST";
    private static final String CURRENT_TF_BUILD_ID = "CURRENT_TF";
    private static final AtomicInteger poolNumber = new AtomicInteger(1);

    private static final Set<String> PARALLEL_DOWNLOAD_OPTIONS =
            ImmutableSet.of("extra-target-downloads");

    private final LoadingCache<String, String> mLatestBuildCache;
    private final LoadingCache<String, RemoteBuildInfo> mQueryCache;

    public AndroidBuildRemoteFileResolver() {
        mQueryCache =
                CacheBuilder.newBuilder()
                        .maximumSize(50)
                        .expireAfterAccess(60, TimeUnit.MINUTES)
                        .build(
                                new CacheLoader<String, RemoteBuildInfo>() {
                                    @Override
                                    public RemoteBuildInfo load(String key)
                                            throws BuildRetrievalError {
                                        String[] build = key.split(",");
                                        String pattern = build[3];
                                        if ("null".equals(pattern)) {
                                            pattern = null;
                                        }
                                        return performBuildApiQuery(
                                                build[0], build[1], build[2], pattern);
                                    }
                                });
        mLatestBuildCache =
                CacheBuilder.newBuilder()
                        .maximumSize(50)
                        .expireAfterAccess(10, TimeUnit.MINUTES)
                        .build(
                                new CacheLoader<String, String>() {
                                    @Override
                                    public String load(String key) throws BuildRetrievalError {
                                        return null;
                                    }
                                });
    }

    @Override
    public ResolvedFile resolveRemoteFile(RemoteFileResolverArgs args) throws BuildRetrievalError {
        // Use a local variable to avoid overriding the option
        boolean useCas = mUseCas && !mForceDisableCas;
        Map<String, String> query = args.getQueryArgs();
        if (query.containsKey(USE_CAS)) {
            useCas = Boolean.parseBoolean(query.get(USE_CAS));
        }
        if (useCas) {
            CLog.d("use-cas is enabled.");
        }
        if (query.containsKey(ENABLE_RESOLVE_SYM_LINKS)) {
            mEnableResolveSymlinks = Boolean.parseBoolean(query.get(ENABLE_RESOLVE_SYM_LINKS));
            CLog.d("enable-resolve-sym-links=%s", mEnableResolveSymlinks);
        }
        // Don't use absolute path as it would not start with ab:
        String path = args.getConsideredFile().getPath();
        Matcher m = DOWNLOAD_PATTERN.matcher(path);
        if (!m.matches()) {
            return null;
        }
        boolean wasLatest = false;
        String buildId = m.group(BUILD_ID_STRING);
        if (LATEST_BUILD_ID.equals(buildId.toUpperCase())) {
            buildId = null;
            wasLatest = true;
        } else if (CURRENT_TF_BUILD_ID.equals(buildId.toUpperCase())) {
            buildId = VersionParser.fetchVersion();
            if (buildId == null) {
                throw new BuildRetrievalError("Couldn't find current TF version.");
            }
        }
        String branch = m.group(BUILD_BRANCH_STRING);
        String buildFlavor = m.group(BUILD_TARGET_STRING);
        if (buildId == null) {
            buildId = mLatestBuildCache.getIfPresent(String.format("%s,%s", branch, buildFlavor));
        }
        String pattern = m.group(BUILD_PATTERN_STRING);
        String originalPattern = pattern;
        pattern = ".*/" + pattern;
        // For partial download query without pattern
        String queryPattern = isPartialDownloadQuery(query) ? null : originalPattern;
        if (useCas && queryPattern != null) {
            queryPattern += "|cas_digests.json";
        }
        CLog.d("Query pattern is: %s", queryPattern);
        String key = String.format("%s,%s,%s,%s", buildId, branch, buildFlavor, queryPattern);
        boolean shouldUseParallelDownload = canUseParallelDownload(query);
        String remoteFilePath = null;
        RemoteBuildInfo info = null;
        // TODO: Move query to parallel thread to avoid blocking here.
        info = queryRemoteBuild(key, queryPattern, path);
        buildId = info.getBuildId();
        if (wasLatest && buildId != null) {
            mLatestBuildCache.put(String.format("%s,%s", branch, buildFlavor), buildId);
        }
        remoteFilePath = findFileForPattern(info, pattern);

        if (isPartialDownloadQuery(query)) {
            File destDir = getOrCreatePartialDownloadDir(args);
            if (shouldUseParallelDownload) {
                ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
                ThreadFactory factory =
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t =
                                        new Thread(
                                                currentGroup,
                                                r,
                                                "ab-pool-task-" + poolNumber.getAndIncrement());
                                t.setDaemon(true);
                                return t;
                            }
                        };
                ExecutorService service =
                        TracePropagatingExecutorService.create(
                                Executors.newFixedThreadPool(1, factory));

                Entry<File, Future<BuildRetrievalError>> parallelDownload =
                        downloadZippedFileParallel(
                                service,
                                getAndroidBuildDownloader(info, useCas),
                                destDir,
                                remoteFilePath,
                                args);
                ExtendedFile eFile =
                        new ExtendedFile(
                                parallelDownload.getKey(),
                                buildId,
                                buildFlavor,
                                branch,
                                LCUtil.parseAttributeLine(remoteFilePath).get(LCUtil.FILENAME));
                eFile.setDownloadFuture(service, parallelDownload.getValue());
                // Return the file with metadata
                return new ResolvedFile(eFile);
            } else {
                return new ResolvedFile(
                        downloadZippedFiles(
                                getAndroidBuildDownloader(info, useCas),
                                destDir,
                                args,
                                remoteFilePath));
            }
        }
        try {
            if (shouldUseParallelDownload) {
                CLog.d("Downloading %s in parallel.", remoteFilePath);
                ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
                ThreadFactory factory =
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t =
                                        new Thread(
                                                currentGroup,
                                                r,
                                                "ab-pool-task-" + poolNumber.getAndIncrement());
                                t.setDaemon(true);
                                return t;
                            }
                        };
                ExecutorService service =
                        TracePropagatingExecutorService.create(
                                Executors.newFixedThreadPool(1, factory));

                Entry<File, Future<BuildRetrievalError>> parallelDownload =
                        downloadFileParallel(
                                service, remoteFilePath, getAndroidBuildDownloader(info, useCas));
                ExtendedFile eFile =
                        new ExtendedFile(
                                parallelDownload.getKey(),
                                buildId,
                                buildFlavor,
                                branch,
                                LCUtil.parseAttributeLine(remoteFilePath).get(LCUtil.FILENAME));
                eFile.setDownloadFuture(service, parallelDownload.getValue());
                // Return the file with metadata
                return new ResolvedFile(eFile);
            } else {
                File downloadedFile =
                        downloadFile(remoteFilePath, getAndroidBuildDownloader(info, useCas));
                File unzippedIfNeeded =
                        DynamicRemoteFileResolver.unzipIfRequired(downloadedFile, query);
                // Return the file with metadata
                return new ResolvedFile(
                        new ExtendedFile(
                                unzippedIfNeeded, buildId, buildFlavor, branch, remoteFilePath));
            }
        } catch (BuildRetrievalError | IOException e) {
            mQueryCache.invalidate(key);
            deleteCacheEntry(remoteFilePath);
            throw new BuildRetrievalError(
                    String.format("Failed to download %s due to: %s", path, e.getMessage()),
                    e,
                    InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
        }
    }

    /**
     * Download partial files from remote zip file based on the given filters.
     *
     * @param args The {@link
     *     com.android.tradefed.config.remote.IRemoteFileResolver.RemoteFileResolverArgs} describing
     *     the download and its options
     * @return a {@link File} object of the destination directory to store the individual files.
     * @throws BuildRetrievalError
     */
    private File downloadZippedFiles(
            IFileDownloader downloader,
            File destDir,
            RemoteFileResolverArgs args,
            String remoteFilePath)
            throws BuildRetrievalError {
        Map<String, String> query = args.getQueryArgs();

        List<String> includeFilters = new ArrayList<>();
        if (query.containsKey(INCLUDE_FILTERS)) {
            includeFilters = Arrays.asList(query.get(INCLUDE_FILTERS).split(";"));
        }
        List<String> excludeFilters = new ArrayList<>();
        if (query.containsKey(EXCLUDE_FILTERS)) {
            excludeFilters = Arrays.asList(query.get(EXCLUDE_FILTERS).split(";"));
        }
        try {
            downloader.downloadZippedFiles(destDir, remoteFilePath, includeFilters, excludeFilters);
        } catch (BuildRetrievalError | IOException e) {
            CLog.e(e);
            throw new BuildRetrievalError(
                    String.format(
                            "Failed to download partial files from: %s. Error message: %s",
                            remoteFilePath, e.getMessage()),
                    e,
                    InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
        }

        return destDir;
    }

    private static boolean isPartialDownloadQuery(Map<String, String> query) {
        return query.containsKey(INCLUDE_FILTERS) || query.containsKey(EXCLUDE_FILTERS);
    }

    private static File getOrCreatePartialDownloadDir(RemoteFileResolverArgs args)
            throws BuildRetrievalError {
        File partialDownloadDir = args.getDestinationDir();

        if (partialDownloadDir == null) {
            try {
                return FileUtil.createTempDir("partial-download");
            } catch (IOException e) {
                throw new BuildRetrievalError(
                        "Could not create a directory to store the partially downloaded files",
                        e,
                        InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
            }
        }

        if (!partialDownloadDir.exists() || !partialDownloadDir.isDirectory()) {
            throw new BuildRetrievalError(
                    String.format(
                            "The destination directory for partial download '%s' does not exist.",
                            partialDownloadDir),
                    InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
        }
        return partialDownloadDir;
    }

    @Override
    public @Nonnull String getSupportedProtocol() {
        return PROTOCOL;
    }

    @VisibleForTesting
    protected RemoteBuildInfo performBuildApiQuery(
            String buildId, String branch, String buildFlavor, String pattern)
            throws BuildRetrievalError {
        AndroidBuildArtifactHelper helper =
                new AndroidBuildArtifactHelper(mAndroidBuildEnv, false, true);
        if (Strings.isNullOrEmpty(buildId) || "null".equals(buildId)) {
            CLog.d("No build id specified, using latest for download.");
            return helper.queryForLatestBuild(branch, buildFlavor, OS_STRING, pattern);
        } else {
            return helper.queryForBuildById(
                    branch, buildId, buildFlavor, OS_STRING, pattern, new HashSet<>());
        }
    }

    @VisibleForTesting
    protected IFileDownloader getAndroidBuildDownloader(RemoteBuildInfo info, boolean useCas) {
        IFileDownloader downloader = null;
        AndroidBuildDownloader abDownloader = new AndroidBuildDownloader();
        abDownloader.enablePartialDownloadCache(mUsePartialDownloadCache);
        abDownloader.setResolveSymlinks(mEnableResolveSymlinks);
        downloader =
                new FileDownloadCacheWrapper(getHostOptions().getDownloadCacheDir(), abDownloader);
        if (CasFileDownloader.getCasOptions().isForceDisabled()) {
            useCas = false;
        }
        if (useCas
                && DeviceLaunchControlProvider.getPathForPattern(info, ".*cas_digests.json")
                        != null) {
            CLog.d(
                    "Using CasFileDownloader for %s. casChunkDir=%s",
                    info.toString(), !mDisableCasChunkDir);
            CasFileDownloader cas =
                    new CasFileDownloader(downloader, null, true, true, !mDisableCasChunkDir);
            cas.setCasMemoryLimit(mCasMemoryLimit);
            cas.setCasConcurrency(mCasConcurrency);
            cas.setCasRpcTimeout(
                    mCasRpcTimeout,
                    mCasGetCapabilitiesTimeout,
                    mCasBatchUpdateBlobsTimeout,
                    mCasBatchReadBlobsTimeout,
                    mCasGetTreeTimeout);
            CLog.d(
                    "[%s] setCasChunksOptions(%s, %s) (mForceDisableCasFuseMount: %s)",
                    Integer.toHexString(System.identityHashCode(cas)),
                    mCasKeepChunks && !mForceDisableCasFuseMount,
                    mCasChunksOnly && !mForceDisableCasFuseMount,
                    mForceDisableCasFuseMount);
            cas.setCasChunksOptions(
                    mCasKeepChunks && !mForceDisableCasFuseMount,
                    mCasChunksOnly && !mForceDisableCasFuseMount);
            downloader = cas;
        }
        return downloader;
    }

    /**
     * Download the requested file via a pattern.
     *
     * @param remoteFilePath The path of file to download
     * @param downloader The {@link IFileDownloader} that will be used for downloading.
     * @return The downloaded {@link File}.
     */
    private File downloadFile(String remoteFilePath, IFileDownloader downloader)
            throws BuildRetrievalError {
        File destFile =
                AndroidBuildDownloader.createTempFile(
                        remoteFilePath, CurrentInvocation.getWorkFolder());
        try {
            downloader.downloadFile(remoteFilePath, destFile);
            return destFile;
        } catch (BuildRetrievalError e) {
            destFile.delete();
            throw e;
        }
    }

    private Entry<File, Future<BuildRetrievalError>> downloadFileParallel(
            ExecutorService service, String remoteFilePath, IFileDownloader downloader)
            throws BuildRetrievalError {
        File destFile =
                AndroidBuildDownloader.createTempFile(
                        remoteFilePath, CurrentInvocation.getWorkFolder());
        CompletableFuture<BuildRetrievalError> futureClient =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                downloader.downloadFile(remoteFilePath, destFile);
                                return null;
                            } catch (BuildRetrievalError e) {
                                return e;
                            }
                        },
                        service);
        return new AbstractMap.SimpleEntry<File, Future<BuildRetrievalError>>(
                destFile, futureClient);
    }

    private Entry<File, Future<BuildRetrievalError>> downloadZippedFileParallel(
            ExecutorService service,
            IFileDownloader downloader,
            File destDir,
            String remoteFilePath,
            RemoteFileResolverArgs args) {
        CompletableFuture<BuildRetrievalError> futureClient =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                downloadZippedFiles(downloader, destDir, args, remoteFilePath);
                                return null;
                            } catch (BuildRetrievalError e) {
                                return e;
                            }
                        },
                        service);
        return new AbstractMap.SimpleEntry<File, Future<BuildRetrievalError>>(
                destDir, futureClient);
    }

    private String findFileForPattern(RemoteBuildInfo remoteBuild, String pattern)
            throws BuildRetrievalError {
        // download any additional files; expect comma-separated file paths.
        final String filesPathCSVString = remoteBuild.getAttribute(BuildAttributeKey.FILES);
        if (filesPathCSVString == null) {
            return null;
        }
        String[] filePaths;
        if (filesPathCSVString.isEmpty()) {
            filePaths = new String[] {};
        } else {
            filePaths = filesPathCSVString.split(",");
        }
        List<String> ret = new ArrayList<>(Arrays.asList(filePaths));
        for (String exclusionPattern :
                AndroidBuildAPIHelper.getAndroidBuildAPIOptions().getDownloadExclusionPatterns()) {
            boolean removed = ret.removeIf(e -> e.matches(exclusionPattern + ".*"));
            if (removed) {
                CLog.d("Filtered all '%s' build artifacts.", exclusionPattern);
                CLog.d("Remaining build artifacts: %s", ret);
            }
        }
        for (String filePath : ret) {
            if (filePath.matches(pattern)) {
                String label = new File(filePath).getName();
                CLog.d("label for %s is %s", filePath, label);
                // Always download the first matched file in the list of files.
                return filePath;
            }
        }
        throw new BuildRetrievalError(
                String.format(
                        "No files '%s' match the requested pattern '%s'.",
                        filesPathCSVString, pattern),
                InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
    }

    /** Gets the {@link IHostOptions} instance to use. */
    private IHostOptions getHostOptions() {
        return GlobalConfiguration.getInstance().getHostOptions();
    }

    private void deleteCacheEntry(String remoteFilePath) {
        if (remoteFilePath != null) {
            FileDownloadCache cache =
                    FileDownloadCacheFactory.getInstance()
                            .getCache(getHostOptions().getDownloadCacheDir());
            cache.deleteCacheEntry(remoteFilePath);
        }
    }

    private boolean canUseParallelDownload(Map<String, String> query) {
        String unzipValue = query.get(DynamicRemoteFileResolver.UNZIP_KEY);
        if (unzipValue != null && "true".equals(unzipValue.toLowerCase())) {
            return false;
        }
        String parallelValue = query.get("parallel");
        if (parallelValue != null && "false".equals(parallelValue.toLowerCase())) {
            return false;
        }
        if (!PARALLEL_DOWNLOAD_OPTIONS.contains(
                query.get(DynamicRemoteFileResolver.OPTION_NAME_KEY))) {
            return false;
        }
        return true;
    }

    private RemoteBuildInfo queryRemoteBuild(String key, String pattern, String path)
            throws BuildRetrievalError {
        try (CloseableTraceScope ignored = new CloseableTraceScope("query " + pattern)) {
            RemoteBuildInfo info = mQueryCache.get(key);
            if (info == null) {
                throw new BuildRetrievalError("Failed to find a proper RemoteBuild");
            }
            return info;
        } catch (ExecutionException | BuildRetrievalError | RuntimeException e) {
            mQueryCache.invalidate(key);
            throw new BuildRetrievalError(
                    String.format("Failed to download %s due to: %s", path, e.getMessage()),
                    e,
                    InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
        }
    }
}
