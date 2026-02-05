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
package com.google.android.tradefed.build;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RemoteZip;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.zip.CentralDirectoryInfo;

import com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A {@link IFileDownloader} that retrieves files from the Android Build API. */
public class AndroidBuildDownloader implements IFileDownloader {

    /** Standard platforms */
    private static final Set<String> STANDARD_PLATFORMS = new HashSet<String>();

    // Max retry attempts for partial downloading a zip file
    private static final int PARTIAL_DOWNLOAD_MAX_RETRIES = 3;

    static {
        STANDARD_PLATFORMS.add("linux");
        STANDARD_PLATFORMS.add("mac");
        STANDARD_PLATFORMS.add("mac_arm64");
    }

    /** A cache of remote zip files indexed by remote path. */
    protected static final ConcurrentMap<String, RemoteZip> REMOTE_ZIP_CACHE =
            new ConcurrentHashMap<>();

    // Maximum age of a cached RemoteZip object. Set to be 12 hours.
    private static final long MAX_REMOTE_ZIP_CACHE_TIME = 12 * 3600 * 1000;

    /** Android Build environment to download from. */
    private String mBuildEnvironment = "prod";

    /** Build Fetcher to use to download files. */
    private AndroidBuildFetcher mBuildFetcher;

    private boolean mUsePartialDownloadCache = false;
    private boolean mUseMetadataCheck = false;
    private boolean mUseMd5 = true;
    private boolean mResolveSymlinks = false;

    public void enablePartialDownloadCache(boolean enablePartialDownloadCache) {
        mUsePartialDownloadCache = enablePartialDownloadCache;
    }

    public void setMetadataCheck(boolean enableMetadataCheck) {
        mUseMetadataCheck = enableMetadataCheck;
    }

    public void setUseMd5(boolean useMd5) {
        mUseMd5 = useMd5;
    }

    public void setResolveSymlinks(boolean resolveSymlinks) {
        mResolveSymlinks = resolveSymlinks;
    }

    /** {@inheritDoc} */
    @Override
    public File downloadFile(String remoteFilePath) throws BuildRetrievalError {
        File destFile = createTempFile(remoteFilePath, null);
        try {
            downloadFile(remoteFilePath, destFile);
            return destFile;
        } catch (BuildRetrievalError e) {
            destFile.delete();
            throw e;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void downloadFile(String remoteFilePath, File destFile) throws BuildRetrievalError {
        if (mUseMetadataCheck) {
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope("getMetadata:" + remoteFilePath)) {
                BuildArtifactMetadata metadata = getMetadata(remoteFilePath);
                long fileSize = metadata.getSize();
                if (fileSize == 0L) {
                    throw new BuildRetrievalError(
                            String.format(
                                    "Requested %s size is 0 and cannot be downloaded. Please verify"
                                            + " the download regex.",
                                    remoteFilePath),
                            InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
                }
            }
        }
        downloadFile(remoteFilePath, destFile, 0, -1);
    }

    @VisibleForTesting
    BuildArtifactMetadata getMetadata(String remoteFilePath) throws BuildRetrievalError {
        CLog.i("Getting metadata of %s", remoteFilePath);
        final Map<String, String> attribs = LCUtil.parseAttributeLine(remoteFilePath);
        if (attribs == null) {
            throw new BuildRetrievalError(
                    String.format("Failed to parse file path %s as LC path", remoteFilePath),
                    InfraErrorIdentifier.ARTIFACT_UNSUPPORTED_PATH);
        }
        String target = attribs.get(LCUtil.FLAVOR);
        final String platform = attribs.get("os");
        if (platform != null && !STANDARD_PLATFORMS.contains(platform)) {
            // As a platform parameter gets deprecated in fetch_artifact, non-standard platform
            // string(ex)fastbuild_linux) should be appended to flavor to generate a correct build
            // target.
            target += "_" + platform;
        }
        final String buildId = attribs.get(LCUtil.BUILD_ID);
        final String remoteFile = attribs.get(LCUtil.FILENAME);
        // Retry getting metadata in of flaky network
        int retry = 3;
        IOException exception = null;
        do {
            try {
                return getBuildFetcher()
                        .getArtifactMetadata(
                                target,
                                buildId,
                                remoteFile,
                                AndroidBuildArtifactHelper.BUILD_ATTEMPT_LATEST);
            } catch (GeneralSecurityException e) {
                throw new BuildRetrievalError(
                        String.format("Failed to get metadata of '%s'", remoteFilePath),
                        e,
                        InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
            } catch (IOException e) {
                exception = e;
            }
            retry--;
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.METADATA_RETRY_COUNT, 1);
        } while (retry > 0);

        throw new BuildRetrievalError(
                String.format("Failed to get metadata of '%s'", remoteFilePath),
                exception,
                InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
    }

    @Override
    public void downloadZippedFiles(
            File destDir,
            String remoteFilePath,
            List<String> includeFilters,
            List<String> excludeFilters)
            throws BuildRetrievalError, IOException {
        RemoteZip remoteZip = REMOTE_ZIP_CACHE.get(remoteFilePath);
        if (remoteZip == null) {
            // Retrieve the information of all file entries in the remote zip file.
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope("getMetadata:zippedFile:" + remoteFilePath)) {
                BuildArtifactMetadata metadata = getMetadata(remoteFilePath);
                long fileSize = metadata.getSize();
                if (fileSize == 0L) {
                    throw new BuildRetrievalError(
                            String.format(
                                    "Requested %s size is 0 and cannot be downloaded. Please verify"
                                            + " the artifact.",
                                    remoteFilePath),
                            InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
                }
                boolean useZip64 = getHostOptions().getUseZip64InPartialDownload();
                remoteZip =
                        new RemoteZip(
                                remoteFilePath, fileSize, this, useZip64, mUsePartialDownloadCache);
                REMOTE_ZIP_CACHE.putIfAbsent(remoteFilePath, remoteZip);

                // TODO(dshi): Find a better way to periodically clean up the cache.
                cleanupRemoteZipCache();
            }
        }
        remoteZip.setLastAccess(System.currentTimeMillis());
        List<CentralDirectoryInfo> allEntries = remoteZip.getZipEntries();

        // Filter entries with the given include and exclude filters.
        List<CentralDirectoryInfo> matchedEntries = Collections.synchronizedList(new ArrayList<>());
        List<Pattern> includePatterns = new ArrayList<>();
        List<Pattern> excludePatterns = new ArrayList<>();
        if (includeFilters != null) {
            for (String filter : includeFilters) {
                String escapedFilter = filter.replace("+", "\\+");
                includePatterns.add(Pattern.compile(escapedFilter));
            }
        }
        if (excludeFilters != null) {
            for (String filter : excludeFilters) {
                excludePatterns.add(Pattern.compile(filter));
            }
        }
        try (CloseableTraceScope ignored = new CloseableTraceScope("filter_entries")) {
            allEntries.parallelStream()
                    .forEach(
                            entry -> {
                                boolean matched = false;
                                for (Pattern pattern : includePatterns) {
                                    if (pattern.matcher(entry.getFileName()).find()) {
                                        matched = true;
                                        break;
                                    }
                                }
                                if (!matched) {
                                    return;
                                }
                                for (Pattern pattern : excludePatterns) {
                                    if (pattern.matcher(entry.getFileName()).find()) {
                                        matched = false;
                                        break;
                                    }
                                }
                                if (matched) {
                                    matchedEntries.add(entry);
                                }
                            });
        }

        List<CentralDirectoryInfo> folders =
                matchedEntries.stream()
                        .parallel()
                        .filter(e -> e.getFileName().endsWith("/"))
                        .collect(Collectors.toList());
        List<CentralDirectoryInfo> files =
                matchedEntries.stream()
                        .parallel()
                        .filter(e -> !e.getFileName().endsWith("/"))
                        .collect(Collectors.toList());

        // Create all folders first as they don't require download.
        try (CloseableTraceScope ignored = new CloseableTraceScope("create_directories")) {
            for (CentralDirectoryInfo entry : folders) {
                File targetFile =
                        new File(Paths.get(destDir.toString(), entry.getFileName()).toString());
                // Use ZipUtil to create the folder to guarantee the folder's permission setting.
                ZipUtil.unzipPartialZipFolder(targetFile, entry);
            }
        }

        // Download individual files from the remote zip file.
        if (files.size() > 0) {
            int partialDownloadAttempt = 0;
            while (true) {
                try {
                    remoteZip.downloadFiles(destDir, files);
                    break;
                } catch (BuildRetrievalError | IOException e) {
                    // Allow partial downloading retry for PARTIAL_DOWNLOAD_MAX_RETRIES times
                    if (++partialDownloadAttempt == PARTIAL_DOWNLOAD_MAX_RETRIES) {
                        CLog.e(
                                String.format(
                                        "Partial download failed after %s attempts, aborting",
                                        PARTIAL_DOWNLOAD_MAX_RETRIES));
                        throw e;
                    }
                }
            }
        }

        if (mResolveSymlinks) {
            try (CloseableTraceScope ignored = new CloseableTraceScope("resolve_symlinks")) {
                resolveSymlinks(files, destDir, remoteFilePath);
            }
        }
    }

    private void resolveSymlinks(
            List<CentralDirectoryInfo> files, File destDir, String remoteFilePath)
            throws BuildRetrievalError, IOException {
        CLog.i("Resolving symlinks for %d file(s) in %s", files.size(), remoteFilePath);
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricLogger.InvocationMetricKey.RESOLVE_SYMLINK_COUNT, files.size());
        Map<String, CentralDirectoryInfo> filesByName =
                files.stream().collect(Collectors.toMap(e -> e.getFileName(), e -> e));
        Map<String, String> resolvableByDownload = new HashMap<>();
        Map<String, String> inPlaceResolvable = new HashMap<>();
        Set<CentralDirectoryInfo> filesForInPlaceResolve = new HashSet<>();

        for (CentralDirectoryInfo entry : files) {
            if (!entry.isSymLink()) {
                continue;
            }

            String linkName = entry.getFileName();
            Path target = Files.readSymbolicLink(Paths.get(destDir.toString(), linkName));
            String resolved =
                    Paths.get(linkName).getParent().resolve(target).normalize().toString();
            CentralDirectoryInfo resolvedEntry = filesByName.get(resolved);
            if (resolvedEntry == null) {
                resolvableByDownload.put(linkName, resolved);
            } else {
                inPlaceResolvable.put(linkName, resolvedEntry.getFileName());
                filesForInPlaceResolve.add(resolvedEntry);
            }
        }

        if (!inPlaceResolvable.isEmpty()) {
            resolveSymlinks(
                    filesForInPlaceResolve.stream().collect(Collectors.toList()),
                    destDir,
                    remoteFilePath);
            for (Map.Entry<String, String> entry : inPlaceResolvable.entrySet()) {
                replaceWithHardlink(
                        Paths.get(destDir.toString(), entry.getKey()),
                        Paths.get(destDir.toString(), entry.getValue()));
            }
        }

        if (!resolvableByDownload.isEmpty()) {
            File destForResolve = null;
            try {
                destForResolve = FileUtil.createTempDir("link_resolve", destDir);
                List<String> includeFiltersForResolve =
                        resolvableByDownload.values().stream()
                                .map(e -> "^" + e + "$")
                                .collect(Collectors.toList());
                CLog.i(
                        "Downloading files %s to resolve symlinks for %s.",
                        includeFiltersForResolve, remoteFilePath);
                downloadZippedFiles(
                        destForResolve,
                        remoteFilePath,
                        includeFiltersForResolve,
                        new ArrayList<>());
                for (Map.Entry<String, String> entry : resolvableByDownload.entrySet()) {
                    replaceWithHardlink(
                            Paths.get(destDir.toString(), entry.getKey()),
                            Paths.get(destForResolve.toString(), entry.getValue()));
                }
            } finally {
                FileUtil.recursiveDelete(destForResolve);
            }
        }
    }

    private void replaceWithHardlink(Path dest, Path orig) throws IOException {
        File origFile = orig.toFile();
        File destFile = dest.toFile();
        FileUtil.deleteFile(destFile);
        FileUtil.hardlinkFile(origFile, destFile);
    }

    void downloadBuildArtifact(
            File destFile,
            String buildId,
            String target,
            String resourceId,
            long startOffset,
            long size)
            throws BuildRetrievalError {
        String partialInfo = "";
        if (size > 0) {
            partialInfo = String.format(" (Starting at %,d for %,d bytes)", startOffset, size);
        }
        try {
            CLog.i("Attempting to download %s%s...", resourceId, partialInfo);
            getBuildFetcher()
                    .fetchArtifact(
                            target,
                            buildId,
                            resourceId,
                            AndroidBuildArtifactHelper.BUILD_ATTEMPT_LATEST,
                            destFile,
                            startOffset,
                            size);
            // Only log stage metrics for partial download.
            if (size > 0) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.STAGE_TESTS_BYTES, destFile.length());
            }
        } catch (BuildRetrievalError e) {
            throw e;
        } catch (GeneralSecurityException | IOException e) {
            String msg = String.format("Failed to download %s%s", resourceId, partialInfo);
            CLog.e(msg);
            CLog.e(e);
            destFile.delete();
            throw new BuildRetrievalError(msg, e, InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
        }
    }

    protected void setBuildEnvironment(String buildEnvironment) {
        mBuildEnvironment = buildEnvironment;
    }

    /** Returns an AndroidBuildFetcher, creating one if it does not already exist. */
    protected AndroidBuildFetcher getBuildFetcher() throws IOException, GeneralSecurityException {
        if (mBuildFetcher == null) {
            mBuildFetcher = new AndroidBuildFetcher(mBuildEnvironment);
            mBuildFetcher.setUseMd5(mUseMd5);
            if (getHostOptions().useAndroidBuildApiV4()) {
                CLog.d(
                        "Using Android Build API V4 in AndroidBuildDownloader for env %s",
                        mBuildEnvironment);
            }
        }
        return mBuildFetcher;
    }

    /**
     * Creates a unique file on temporary disk to house downloaded file with given path.
     *
     * <p>Constructs the file name based on base file name from path
     *
     * @param remoteFilePath the remote path to construct the name from
     */
    public static File createTempFile(String remoteFilePath, File rootDir)
            throws BuildRetrievalError {
        try {
            // create a unique file.
            File tmpFile = FileUtil.createTempFileForRemote(remoteFilePath, rootDir);
            // now delete it so name is available
            tmpFile.delete();
            return tmpFile;
        } catch (IOException e) {
            String msg = String.format("Failed to create tmp file for %s", remoteFilePath);
            throw new BuildRetrievalError(msg, e, InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        }
    }

    /**
     * Gets the {@link IHostOptions} instance to use.
     *
     * <p>Exposed for unit testing
     */
    @VisibleForTesting
    IHostOptions getHostOptions() {
        return GlobalConfiguration.getInstance().getHostOptions();
    }

    /** {@inheritDoc} */
    @Override
    public void downloadFile(String remoteFilePath, File destFile, long startOffset, long size)
            throws BuildRetrievalError {
        try (CloseableTraceScope ignore = new CloseableTraceScope(remoteFilePath)) {
            final Map<String, String> attribs = LCUtil.parseAttributeLine(remoteFilePath);
            if (attribs == null) {
                throw new BuildRetrievalError(
                        String.format("Failed to parse file path %s as LC path", remoteFilePath),
                        InfraErrorIdentifier.ARTIFACT_UNSUPPORTED_PATH);
            }
            String target = attribs.get(LCUtil.FLAVOR);
            final String platform = attribs.get(LCUtil.OS);
            if (platform != null && !STANDARD_PLATFORMS.contains(platform)) {
                // As a platform parameter gets deprecated in fetch_artifact, non-standard platform
                // string(ex)fastbuild_linux) should be appended to flavor to generate a correct
                // build
                // target.
                target += "_" + platform;
            }
            final String buildId = attribs.get(LCUtil.BUILD_ID);
            final String remoteFile = attribs.get(LCUtil.FILENAME);
            downloadBuildArtifact(destFile, buildId, target, remoteFile, startOffset, size);
        }
    }

    /** Remove any cached RemoteZip objects that are older than MAX_REMOTE_ZIP_CACHE_TIME. */
    @VisibleForTesting
    static void cleanupRemoteZipCache() {
        List<String> oldItems = new ArrayList<>();
        for (Map.Entry<String, RemoteZip> entry : REMOTE_ZIP_CACHE.entrySet()) {
            if (entry.getValue().getLastAccess()
                    < System.currentTimeMillis() - MAX_REMOTE_ZIP_CACHE_TIME) {
                oldItems.add(entry.getKey());
            }
        }
        for (String item : oldItems) {
            REMOTE_ZIP_CACHE.remove(item);
        }
    }
}
