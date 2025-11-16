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
package com.google.android.tradefed.build.content;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.FileDownloadCacheWrapper;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.content.ContentAnalysisContext;
import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.build.content.ContentInformation;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.remote.ExtendedFile;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.skipped.SkipManager;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;

import com.google.android.tradefed.build.AndroidBuildArtifactHelper;
import com.google.android.tradefed.build.AndroidBuildDownloader;
import com.google.android.tradefed.build.DeviceLaunchControlProvider;
import com.google.android.tradefed.build.RemoteBuildInfo;
import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/** Helper to obtain all the content information surrounding a build and its base. */
public class ContentBasedHelper {

    private static final String CAS_DETAILS_JSON = "cas_content_details.json";
    private static final String LAST_BUILD_INFO = "last_build.info";
    private static final String REF_BUILD = "REF-BUILD";

    // Ignore some of the extra-target-downloads key as they are already downloaded
    // or handle in with other artifacts or of non-consequences on tests.
    private static final Set<String> IGNORED_EXTRA_KEYS =
            ImmutableSet.of(
                    "device-tests_configs.zip",
                    "android-cts-tests_list.zip",
                    "android-vts-tests_list.zip",
                    "test_mappings.zip",
                    "jacoco-report-classes-all.jar");

    /** Help checking whether we have the details to analyze. */
    public static boolean hasCasDetails(RemoteBuildInfo info) {
        String path = DeviceLaunchControlProvider.getPathForPattern(info, ".*/" + CAS_DETAILS_JSON);
        if (path == null) {
            return false;
        }
        return true;
    }

    private static LastBuildInfo getLastBuildInfo(
            IFileDownloader downloader,
            RemoteBuildInfo info,
            String branch,
            String buildFlavor,
            String buildId)
            throws BuildRetrievalError, IOException {
        File refBuildFile = null;
        try {
            if (info == null) {
                return null;
            }
            if (DeviceLaunchControlProvider.getPathForPattern(info, ".*/" + LAST_BUILD_INFO)
                    != null) {
                String refBuildPath =
                        String.format(
                                "%s-linux-%s/%s/logs/" + LAST_BUILD_INFO,
                                branch,
                                buildFlavor,
                                buildId);
                refBuildFile = downloader.downloadFile(refBuildPath);
                // Override flavor in last_build.info because trident can use an image from another
                // flavor in some cases but for content comparison we need the same one
                LastBuildInfo lastInfo = LastBuildInfo.parseFile(refBuildFile, buildFlavor);
                if (lastInfo == null) {
                    CLog.d(
                            "Failed to parse last_build.info: %s",
                            FileUtil.readStringFromFile(refBuildFile));
                    return null;
                }
                return lastInfo;
            } else if (DeviceLaunchControlProvider.getPathForPattern(info, ".*/" + REF_BUILD)
                    != null) {
                String refBuildPath =
                        String.format(
                                "%s-linux-%s/%s/logs/" + REF_BUILD, branch, buildFlavor, buildId);
                refBuildFile = downloader.downloadFile(refBuildPath);
                String lastBuildId = FileUtil.readStringFromFile(refBuildFile).trim();
                return new LastBuildInfo(branch, buildFlavor, lastBuildId);
            }
        } finally {
            FileUtil.deleteFile(refBuildFile);
        }
        return null;
    }

    /**
     * Fetch all the pieces necessary to obtain content information relevant to a build compared to
     * its base.
     */
    public static ContentInformation getContentInformation(
            RemoteBuildInfo info, String branch, String buildFlavor, String buildId) {
        AndroidBuildDownloader downloader = new AndroidBuildDownloader();
        downloader.setUseMd5(false);
        IFileDownloader cacheDownloader =
                new FileDownloadCacheWrapper(getHostOptions().getDownloadCacheDir(), downloader);
        File currentContent = null;
        File lastContent = null;
        try (CloseableTraceScope ignored = new CloseableTraceScope("getContentInformation")) {
            ContentInformation cacheInfo =
                    ContentInformationCache.getDefaultCache()
                            .getContentInformation(branch, buildId, buildFlavor);
            if (cacheInfo != null) {
                CLog.d("Using cached ContentInformation.");
                return cacheInfo;
            }
            String currentContentPath =
                    String.format(
                            "%s-linux-%s/%s/logs/" + CAS_DETAILS_JSON,
                            branch,
                            buildFlavor,
                            buildId);
            LastBuildInfo lastInfo =
                    getLastBuildInfo(cacheDownloader, info, branch, buildFlavor, buildId);
            // Download both current and last content information if available
            currentContent = cacheDownloader.downloadFile(currentContentPath);
            if (lastInfo != null) {
                String lastContentPath =
                        String.format(
                                "%s-linux-%s/%s/logs/" + CAS_DETAILS_JSON,
                                lastInfo.branch,
                                lastInfo.buildFlavor,
                                lastInfo.buildId);
                RemoteBuildInfo lastRemoteBuild =
                        new AndroidBuildArtifactHelper("prod")
                                .queryForBuildById(
                                        lastInfo.branch,
                                        lastInfo.buildId,
                                        lastInfo.buildFlavor,
                                        "linux",
                                        ".*" + CAS_DETAILS_JSON,
                                        null);
                if (lastRemoteBuild == null) {
                    CLog.e("no RemoteBuildInfo for %s", lastContentPath);
                } else {
                    if (DeviceLaunchControlProvider.getPathForPattern(
                                    lastRemoteBuild, ".*/" + CAS_DETAILS_JSON)
                            == null) {
                        CLog.e(
                                "Base build '%s' doesn't have cas_content_details.json",
                                lastContentPath);
                    } else {
                        lastContent = cacheDownloader.downloadFile(lastContentPath);
                    }
                }
            }
            String lastBuildid = null;
            if (lastInfo != null) {
                lastBuildid = lastInfo.buildId;
            }
            ContentInformation contentInfo =
                    new ContentInformation(lastContent, lastBuildid, currentContent, buildId);
            if (contentInfo.isValid()) {
                // Only cache if it's valid
                ContentInformationCache.getDefaultCache()
                        .trackContent(branch, buildId, buildFlavor, contentInfo);
            }
            return contentInfo;
        } catch (BuildRetrievalError | RuntimeException | IOException e) {
            CLog.e(e);
            FileUtil.deleteFile(currentContent);
            FileUtil.deleteFile(lastContent);
            return null;
        }
    }

    public static boolean hasHostPackageArgs(TestDeviceOptions options) {
        List<String> params = options.getGceDriverParams();
        if (params.isEmpty()) {
            return false;
        }
        for (int i = 0; i < params.size(); i++) {
            switch (params.get(i)) {
                case "--host_package_build_target":
                    return true;
                case "--host_package_build_id":
                    return true;
            }
        }
        return false;
    }

    /** Handle the content analysis of embedded cloud parameters. */
    public static ContentAnalysisContext obtainCloudParameters(
            String branch, TestDeviceOptions options) throws BuildRetrievalError {
        List<String> params = options.getGceDriverParams();
        if (params.isEmpty()) {
            return null;
        }
        String hostPackageTarget = null, hostPackageBuildId = null;
        String kernelBranch = null, kernelBuildTarget = null, kernelBuildId = null;
        String systemBranch = null, systemBuildTarget = null, systemBuildId = null;
        String cfBranch = null, cfTarget = null, cfBuildId = null;
        for (int i = 0; i < params.size(); i++) {
            switch (params.get(i)) {
                case "--host_package_build_target":
                    hostPackageTarget = params.get(i + 1);
                    break;
                case "--host_package_build_id":
                    hostPackageBuildId = params.get(i + 1);
                    break;
                case "--kernel-branch":
                    kernelBranch = params.get(i + 1);
                    break;
                case "--kernel-build-target":
                    kernelBuildTarget = params.get(i + 1);
                    break;
                case "--kernel-build-id":
                    kernelBuildId = params.get(i + 1);
                    break;
                case "--system-branch":
                    systemBranch = params.get(i + 1);
                    break;
                case "--system-build-target":
                    systemBuildTarget = params.get(i + 1);
                    break;
                case "--system-build-id":
                    systemBuildId = params.get(i + 1);
                    break;
                case "--branch":
                    cfBranch = params.get(i + 1);
                    break;
                case "--build-target":
                    cfTarget = params.get(i + 1);
                    break;
                case "--build-id":
                    cfBuildId = params.get(i + 1);
                    break;
            }
        }
        // TODO: Eventually support getting the content information for each and populate the entry
        // key properly
        if (hostPackageTarget != null && hostPackageBuildId != null) {
            AndroidBuildArtifactHelper helper = new AndroidBuildArtifactHelper("prod");
            RemoteBuildInfo info = null;
            try {
                // Do not throw exception if host package build info failed to be retrieved, as that
                // will fail the whole test. hostPackageBuildId could be the name of the branch for
                // cvd to use the LKGB.
                info =
                        helper.queryForBuildById(
                                null, hostPackageBuildId, hostPackageTarget, "linux", null, null);
            } catch (BuildRetrievalError e) {
                CLog.e(e);
            }
            if (info == null) {
                CLog.w("Failed to query info for the host package target.");
                return null;
            }
            ContentInformation information =
                    ContentBasedHelper.getContentInformation(
                            info,
                            info.getAttribute(BuildAttributeKey.BRANCH),
                            hostPackageTarget,
                            hostPackageBuildId);
            ContentAnalysisContext context =
                    new ContentAnalysisContext(
                            "cvd-host_package.tar.gz", information, AnalysisMethod.BUILD_KEY);
            if (information == null || !information.isValid()) {
                context.invalidateAnalysis();
            }
            return context;
        }
        if (kernelBranch != null && kernelBuildTarget != null && kernelBuildId != null) {
            ContentInformation information =
                    new ContentInformation(null, null, null, kernelBuildId);
            ContentAnalysisContext context =
                    new ContentAnalysisContext(
                            "kernel_image", information, AnalysisMethod.BUILD_KEY);
            context.invalidateAnalysis();
            return context;
        }
        if (systemBranch != null && systemBuildTarget != null && systemBuildId != null) {
            ContentInformation information =
                    new ContentInformation(null, null, null, systemBuildId);
            ContentAnalysisContext context =
                    new ContentAnalysisContext(
                            "system_image", information, AnalysisMethod.BUILD_KEY);
            context.invalidateAnalysis();
            return context;
        }
        if (cfBranch != null && !branch.equals(cfBranch)) {
            if (cfBuildId == null) {
                ContentInformation information = new ContentInformation(null, null, null, "LATEST");
                ContentAnalysisContext context =
                        new ContentAnalysisContext(
                                cfTarget, information, AnalysisMethod.DEVICE_IMAGE);
                context.invalidateAnalysis();
                return context;
            }
        }
        return null;
    }

    /**
     * Analyze extra target downloads to ensure we handle all the test dependencies.
     *
     * @param skipManager {@link SkipManager}
     * @param extraTargetDownloads
     */
    public static void analyzeExtraDownloads(
            SkipManager skipManager,
            ITestDevice device,
            MultiMap<String, File> extraTargetDownloads) {
        try (CloseableTraceScope ignored = new CloseableTraceScope("analyzeExtraDownloads")) {
            for (Entry<String, File> entry : extraTargetDownloads.entries()) {
                if (!(entry.getValue() instanceof ExtendedFile)) {
                    continue;
                }
                if (IGNORED_EXTRA_KEYS.contains(entry.getKey())) {
                    continue;
                }
                ExtendedFile ef = (ExtendedFile) entry.getValue();
                if (ef.getRemoteFilePath() == null) {
                    continue;
                }
                ContentInformation information = null;
                AnalysisMethod method = AnalysisMethod.BUILD_KEY;
                if (entry.getKey().equals("testsdir")) {
                    method = AnalysisMethod.FILE;
                }
                try {
                    AndroidBuildArtifactHelper helper = new AndroidBuildArtifactHelper("prod");
                    RemoteBuildInfo info =
                            helper.queryForBuildById(
                                    ef.getBranch(),
                                    ef.getBuildId(),
                                    ef.getBuildTarget(),
                                    "linux",
                                    CAS_DETAILS_JSON + "|" + LAST_BUILD_INFO + "|" + REF_BUILD,
                                    null);
                    if (DeviceLaunchControlProvider.getPathForPattern(
                                    info, ".*/" + CAS_DETAILS_JSON)
                            != null) {
                        information =
                                ContentBasedHelper.getContentInformation(
                                        info, ef.getBranch(), ef.getBuildTarget(), ef.getBuildId());
                    }
                } catch (BuildRetrievalError e) {
                    CLog.e(e);
                }
                String entryName = ef.getRemoteFilePath();
                if (entryName.contains("-img-")) {
                    entryName = entryName.replaceAll("-img-", "-target_files-");
                }
                ContentAnalysisContext context =
                        new ContentAnalysisContext(entryName, information, method);
                if (information == null || !information.isValid()) {
                    context.invalidateAnalysis(
                            String.format(
                                    "issue with %s extra-target-download analysis.",
                                    entry.getValue()));
                }
                skipManager.setImageAnalysis(device, context);
            }
        }
    }

    private static IHostOptions getHostOptions() {
        return GlobalConfiguration.getInstance().getHostOptions();
    }
}
