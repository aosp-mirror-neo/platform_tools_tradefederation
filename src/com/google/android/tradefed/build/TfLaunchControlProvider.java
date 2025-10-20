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
import com.android.tradefed.build.FolderBuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.build.content.ContentAnalysisContext;
import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.build.content.ContentInformation;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.invoker.tracing.TracePropagatingExecutorService;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;
import com.google.android.tradefed.build.content.ContentBasedHelper;
import com.google.android.tradefed.build.content.ContentMappings;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * A {@link LaunchControlProvider} to retrieve a google-tradefed build as a {@link
 * IFolderBuildInfo}.
 *
 * <p>Retrieves and extracts a google-tradefed.zip from the Android build server.
 */
@OptionClass(alias = "tf-launch-control")
public class TfLaunchControlProvider extends LaunchControlProvider {

    private static final String TF_PACKAGE_NAME = "google-tradefed.zip";
    private static final String AOSP_TF_PACKAGE_NAME = "tradefed.zip";
    private static final Set<String> IGNORED_TF_FILES = ImmutableSet.of("version.txt");

    @Option(
            name = "tf-aosp-fallback",
            description =
                    "Allow to fallback to the aosp version of tradefed if the internal one is not "
                            + "available.")
    private boolean mUseAospFallback = false;

    private boolean mUsedFallback = false;

    private boolean mAdditionalFilesAlreadyDownloaded = false;
    private Future<BuildRetrievalError> futureDownload;
    private ExecutorService mFutureDownloadExecutorService;

    private Future<Exception> futureContentDownload;
    private ExecutorService mFutureContentDownloadExecutorService;

    private String mEntryForContent;
    private boolean mHasCasDetails = false;

    /** {@inheritDoc} */
    @Override
    public RemoteBuildInfo queryForBuild() throws BuildRetrievalError {
        RemoteBuildInfo tfBuild = super.queryForBuild();
        String packageToDownload = TF_PACKAGE_NAME;
        if (tfBuild != null) {
            String errorMessage =
                    String.format("Failed to locate TradeFed artifact %s", TF_PACKAGE_NAME);
            String remoteTfZipPath =
                    findFilePath(tfBuild.getAttribute(BuildAttributeKey.FILES), TF_PACKAGE_NAME);
            if (remoteTfZipPath == null && mUseAospFallback) {
                CLog.d(
                        "Fallback to %s since %s doesn't exist.",
                        AOSP_TF_PACKAGE_NAME, TF_PACKAGE_NAME);
                errorMessage +=
                        String.format(" and failed to locate fallback %s", AOSP_TF_PACKAGE_NAME);
                remoteTfZipPath =
                        findFilePath(
                                tfBuild.getAttribute(BuildAttributeKey.FILES),
                                AOSP_TF_PACKAGE_NAME);
                mUsedFallback = true;
                packageToDownload = AOSP_TF_PACKAGE_NAME;
            }
            if (remoteTfZipPath == null) {
                throw new BuildRetrievalError(
                        String.format(
                                "%s for branch '%s', build-id '%s' and flavor '%s'",
                                errorMessage, getBranch(), getBuildId(), getBuildFlavor()),
                        InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
            }
            tfBuild.addAttribute(BuildAttributeKey.TF, remoteTfZipPath);
            if (shouldReportContentInformation()) {
                mHasCasDetails = ContentBasedHelper.hasCasDetails(tfBuild);
            }
        }
        return tfBuild;
    }

    /** {@inheritDoc} */
    @Override
    public IBuildInfo downloadBuildFiles(
            RemoteBuildInfo remoteBuild,
            String testTargetName,
            String buildName,
            IFileDownloader downloader)
            throws BuildRetrievalError {
        IFolderBuildInfo localBuild = new FolderBuildInfo(remoteBuild.getBuildId(), buildName);
        String remoteTfZipPath = remoteBuild.getAttribute(BuildAttributeKey.TF);
        File tfDownloaded = null;

        try {
            downloadAdditionalFiles(remoteBuild, downloader, localBuild);
            tfDownloaded = downloader.downloadFile(remoteTfZipPath);
            if (tfDownloaded.isDirectory()) {
                localBuild.setRootDir(tfDownloaded);
            } else {
                File localTfRootDir =
                        extractZip(
                                tfDownloaded,
                                String.format(
                                        "%s_%s_", getBuildFlavor(), remoteBuild.getBuildId()));
                localBuild.setRootDir(localTfRootDir);
                FileUtil.deleteFile(tfDownloaded);
            }
            if (futureDownload != null) {
                BuildRetrievalError error = null;
                try {
                    error = futureDownload.get();
                } catch (InterruptedException | ExecutionException e) {
                    error = new BuildRetrievalError(e.getMessage(), e);
                }
                if (error != null) {
                    throw error;
                }
            }
            if (futureContentDownload != null) {
                Exception error = null;
                try {
                    error = futureContentDownload.get();
                } catch (InterruptedException | ExecutionException e) {
                    error = new BuildRetrievalError(e.getMessage(), e);
                }
                if (error != null) {
                    throw new BuildRetrievalError(error.getMessage(), error);
                }
            }
            return localBuild;
        } catch (BuildRetrievalError e) {
            e.setBuildInfo(localBuild);
            FileUtil.recursiveDelete(tfDownloaded);
            if (futureDownload != null) {
                futureDownload.cancel(true);
            }
            if (futureContentDownload != null) {
                futureContentDownload.cancel(true);
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            // clear the cache before returning
            deleteCacheEntry(remoteTfZipPath);
            localBuild.cleanUp();
            FileUtil.recursiveDelete(tfDownloaded);
            if (futureDownload != null) {
                futureDownload.cancel(true);
            }
            if (futureContentDownload != null) {
                futureContentDownload.cancel(true);
            }
            throw new BuildRetrievalError("Failed to extract " + TF_PACKAGE_NAME, e, localBuild);
        } finally {
            if (mFutureDownloadExecutorService != null) {
                mFutureDownloadExecutorService.shutdown();
            }
            if (mFutureContentDownloadExecutorService != null) {
                mFutureContentDownloadExecutorService.shutdown();
            }
        }
    }

    /**
     * Search for expected file in the file names returned by remote build.
     *
     * @param lcFileInfo A comma separated string of all files in the build output.
     * @param fileName Name of the file to look for.
     */
    String findFilePath(String lcFileInfo, String fileName) {
        if (lcFileInfo.isEmpty()) {
            return null;
        }
        String[] files = lcFileInfo.split(",");
        for (int i = 0; i < files.length; i++) {
            // The lcFileInfo is parsed from Launch Control API return value, and the path separator
            // is fixed as "/".
            if (files[i].endsWith("/" + fileName)) {
                return files[i];
            }
        }
        return null;
    }

    @Override
    protected void downloadAdditionalFiles(
            RemoteBuildInfo remoteBuild, IFileDownloader downloader, IBuildInfo localBuild)
            throws BuildRetrievalError {
        if (mAdditionalFilesAlreadyDownloaded) {
            return;
        }
        mAdditionalFilesAlreadyDownloaded = true;
        if (!isSecondaryParallelDownload()) {
            BuildRetrievalError e = wrapDownload(remoteBuild, downloader, localBuild);
            if (e != null) {
                throw e;
            }
        } else {
            ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
            ThreadFactory factory =
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(currentGroup, r, "TfLCP-download");
                            t.setDaemon(true);
                            return t;
                        }
                    };
            mFutureDownloadExecutorService =
                    TracePropagatingExecutorService.create(
                            Executors.newFixedThreadPool(1, factory));
            futureDownload =
                    CompletableFuture.supplyAsync(
                            () -> wrapDownload(remoteBuild, downloader, localBuild),
                            mFutureDownloadExecutorService);
        }
    }

    private Exception wrapContent(RemoteBuildInfo remoteBuild) {
        try {
            if (!mHasCasDetails) {
                if (mEntryForContent != null) {
                    ContentAnalysisContext sandboxContext =
                            new ContentAnalysisContext(
                                    mEntryForContent, null, AnalysisMethod.SANDBOX_WORKDIR);
                    sandboxContext.invalidateAnalysis();
                    getConfiguration().getSkipManager().setTestArtifactsAnalysis(sandboxContext);
                }
            } else {
                ContentInformation info =
                        ContentBasedHelper.getContentInformation(
                                remoteBuild, getBranch(), getBuildFlavor(), getBuildId());
                if (info != null) {
                    if (mEntryForContent != null) {
                        CLog.d("Creating analysis context for entry: %s", mEntryForContent);
                        ContentAnalysisContext sandboxContext =
                                new ContentAnalysisContext(
                                        mEntryForContent, info, AnalysisMethod.SANDBOX_WORKDIR);
                        if (ContentMappings.WORKDIR_COMMON_DIR.containsKey(mEntryForContent)) {
                            sandboxContext.addCommonLocations(
                                    ContentMappings.WORKDIR_COMMON_DIR.get(mEntryForContent));
                        } else {
                            sandboxContext.invalidateAnalysis();
                        }
                        if (!info.isValid()) {
                            sandboxContext.invalidateAnalysis();
                        }
                        getConfiguration()
                                .getSkipManager()
                                .setTestArtifactsAnalysis(sandboxContext);
                    }
                    ContentAnalysisContext tfContext =
                            new ContentAnalysisContext(
                                    (wasFallbackUsed() ? AOSP_TF_PACKAGE_NAME : TF_PACKAGE_NAME),
                                    info,
                                    AnalysisMethod.FILE);
                    tfContext.addIgnoreChanges(IGNORED_TF_FILES);
                    if (!info.isValid()) {
                        tfContext.invalidateAnalysis();
                    }
                    getConfiguration().getSkipManager().setTestArtifactsAnalysis(tfContext);
                }
            }
        } catch (Exception e) {
            return e;
        }
        return null;
    }

    private BuildRetrievalError wrapDownload(
            RemoteBuildInfo remoteBuild, IFileDownloader downloader, IBuildInfo localBuild) {
        if (shouldReportContentInformation()) {
            ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
            ThreadFactory factory =
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(currentGroup, r, "TfLCP-content");
                            t.setDaemon(true);
                            return t;
                        }
                    };
            mFutureContentDownloadExecutorService =
                    TracePropagatingExecutorService.create(
                            Executors.newFixedThreadPool(1, factory));
            futureContentDownload =
                    CompletableFuture.supplyAsync(
                            () -> wrapContent(remoteBuild), mFutureContentDownloadExecutorService);
        }
        try {
            super.downloadAdditionalFiles(remoteBuild, downloader, localBuild);
        } catch (BuildRetrievalError e) {
            return e;
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void cleanUp(IBuildInfo info) {
        info.cleanUp();
    }

    /** Whether or not to fallback to getting the aosp version of the tradefed package. */
    public void setAospFallback(boolean fallbackToAosp) {
        mUseAospFallback = fallbackToAosp;
    }

    public boolean wasFallbackUsed() {
        return mUsedFallback;
    }

    public void addConsideredEntryForContent(String entryName) {
        if (ContentMappings.WORKDIR_COMMON_DIR.containsKey(entryName)) {
            mEntryForContent = entryName;
        } else if (mEntryForContent == null) {
            // If it's the first entry reporting, set it even if it's not tracked to ensure the
            // logic abort analysis
            mEntryForContent = entryName;
        }
    }

    @Override
    public Set<String> reportTestZipFileFilter() {
        Set<String> zips = super.reportTestZipFileFilter();
        zips.add(TF_PACKAGE_NAME);
        zips.add(AOSP_TF_PACKAGE_NAME);
        // Report targets for compatibility with build commands names
        zips.add("tradefed-all.zip");
        zips.add("google-tradefed-all.zip");
        return zips;
    }
}
