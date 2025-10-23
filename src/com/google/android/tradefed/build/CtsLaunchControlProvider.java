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
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.config.remote.ExtendedFile;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.observatory.DiscoveryExitCode;
import com.android.tradefed.observatory.IDiscoverDependencies;
import com.android.tradefed.observatory.TestDiscoveryException;
import com.android.tradefed.observatory.TestDiscoveryInvoker;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.BaseTestSuite;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.FuseUtil;
import com.android.tradefed.util.TimeUtil;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;
import com.google.android.tradefed.build.content.ContentBasedHelper;
import com.google.android.tradefed.testtype.CtsTestLauncher;
import com.google.android.tradefed.util.TestDiscoveryInfo;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link LaunchControlProvider} for a CTS build.
 *
 * <p>Retrieves and extracts a CTS zip from the Android build server. The extracted CTS is
 * represented as a {@link IFolderBuildInfo}.
 */
@OptionClass(alias = "cts-launch-control")
public class CtsLaunchControlProvider extends LaunchControlProvider
        implements IDiscoverDependencies {

    public static final Set<String> JARS_AND_CONFIG_REGEX = ImmutableSet.of(".*.config", ".*.jar");
    public static final Set<String> CONFIG_ONLY_REGEX = ImmutableSet.of(".*.config");

    @Option(
            name = "cts-package-name",
            description =
                    "the filename of CTS package to download. " + "Defaults to \"android-cts.zip\"")
    private String mCtsPackageName = "android-cts.zip";

    @Option(
            name = "cts-use-fusezip",
            description = "use fusezip for CTS package " + "Defaults to false")
    private boolean mCtsUseFusezip = false;

    @Option(
            name = "cts-extra-artifact",
            description =
                    "the filename of CTS extra artifact to "
                            + "download, may be repearted for multiple fetches.")
    private List<String> mExtraArtifact = new ArrayList<String>();

    @Option(
            name = "cts-extra-alt-path",
            description =
                    "Alternative location where to look for extra artifact if they are not found"
                            + " on the build server.")
    private File mExtraAltPath = null;

    @Option(
            name = "throw-if-extra-not-found",
            description =
                    "Throw an exception if one of the "
                            + "extra specified file by cts-extra-artifact is not found.")
    private boolean mThrowIfExtraNotFound = true;

    @Option(
            name = "cts-use-partial-download",
            description =
                    "whether to use partial download to "
                            + "download only necessary test modules from the xts zip")
    private boolean mCtsUsePartialDownload = false;

    @Option(
            name = "delay-download-in-sharding",
            description = "Delay the download of module until after sharding.")
    private boolean mDelayDownloadInSharding = false;

    @Option(
            name = "cts-force-test-discovery-metadata-mode",
            description =
                    "Force the test discovery to be in metadata mode.")
    private boolean mCtsForceTestDiscoveryMetadataMode = false;

    private Map<String, String> mExtraWithPath = new HashMap<String, String>();
    private List<File> mFileCreated = new ArrayList<File>();
    private final boolean mIsFuseUtilAvailable = getFuseUtil().canMountZip();
    private TestDiscoveryInvoker mTestDiscoveryInvoker = null;
    private PartialDownloadMode mPartialDownloadMode = PartialDownloadMode.PartialDownloadNotInUse;
    private Set<String> mForcePartialDownloadRegex = new HashSet<>();
    private String mPartialDownloadFullFallbackReason = "Partial download is not in use.";

    private RemoteBuildInfo mCtsRemoteBuild = null;
    private boolean mApplyDefaultRollout = false;
    private boolean mForceCas = false;
    private boolean mHasCasDetails = false;

    @Option(
            name = "partial-download-default-rollout",
            description =
                    "List of package_name:branch combinations where partial download will be used.")
    private Set<String> mPartialDownloadDefaultRollout = new LinkedHashSet<>();

    public enum PartialDownloadMode {
        PartialDownloadNotInUse,
        PartialDownloadTestModules,
        PartialDownloadTestModulesWithDelay,
        PartialDownloadJarsAndConfigs,
        PartialDownloadFallbackToFullDownload;
    }

    public CtsLaunchControlProvider() {
        super();
        setBuildFlavor("cts");
    }

    @VisibleForTesting
    CtsLaunchControlProvider(TestDiscoveryInvoker testDiscoveryInvoker) {
        this();
        this.mTestDiscoveryInvoker = testDiscoveryInvoker;
    }

    public void setCtsPackageName(String ctsZipName) {
        mCtsPackageName = ctsZipName;
    }

    public void setCtsExtra(List<String> ctsExtra) {
        mExtraArtifact = ctsExtra;
    }

    public void setThrowIfExtraNotFound(boolean shouldThrow) {
        mThrowIfExtraNotFound = shouldThrow;
    }

    public void setCtsExtraAltPath(File altPath) {
        mExtraAltPath = altPath;
    }

    public void setCtsUsePartialDownload(boolean shouldUsePartialDownload) {
        mCtsUsePartialDownload = shouldUsePartialDownload;
    }

    public void setCtsForcePartialDownloadRegex(Set<String> ctsForcePartialDownloadRegex) {
        mForcePartialDownloadRegex = ctsForcePartialDownloadRegex;
    }

    public void setDefaultRollout(boolean defaultRollout) {
        mApplyDefaultRollout = defaultRollout;
    }

    public void setDelayDownloadinSharding(boolean delayDownload) {
        mDelayDownloadInSharding = delayDownload;
    }

    public void setPartialDownloadDefaultRollout(Set<String> partialDownloadDefaultRollout) {
        mPartialDownloadDefaultRollout.addAll(partialDownloadDefaultRollout);
    }

    public boolean getForceCas() {
        return mForceCas;
    }

    @VisibleForTesting
    PartialDownloadMode getPartialDownloadMode() {
        return mPartialDownloadMode;
    }

    @VisibleForTesting
    FuseUtil getFuseUtil() {
        return new FuseUtil();
    }

    @VisibleForTesting
    Set<String> getTestDiscoveryForcedDownloadFiles(List<String> modules) {
        return TestDiscoveryInfo.getAlwaysDownloadFilesList(modules);
    }

    @VisibleForTesting
    void setBuildRemoteFile(IFolderBuildInfo localBuild, String remoteCtsZipPath) {
        localBuild.setFile(
                IBuildInfo.REMOTE_FILE_PREFIX + remoteCtsZipPath,
                new File(LCUtil.createResolvableFilePath(remoteCtsZipPath)),
                IBuildInfo.REMOTE_FILE_VERSION);
    }

    @Override
    protected RemoteBuildInfo queryForBuild() throws BuildRetrievalError {
        RemoteBuildInfo ctsBuild = super.queryForBuild();
        if (ctsBuild != null) {
            if (shouldReportContentInformation()) {
                mHasCasDetails = ContentBasedHelper.hasCasDetails(ctsBuild);
            }
            mCtsRemoteBuild = ctsBuild;
            String remoteCtsZipPath =
                    findFilePath(ctsBuild.getAttribute(BuildAttributeKey.FILES), mCtsPackageName);
            ctsBuild.addAttribute(BuildAttributeKey.CTS, remoteCtsZipPath);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.AB_TEST_ZIP_NAME, getBuildFlavor() + ":" + mCtsPackageName);

            // Build list of remote extra with path
            for (String extra : mExtraArtifact) {
                String remoteCtsExtraPath =
                        findFilePath(ctsBuild.getAttribute(BuildAttributeKey.FILES), extra);
                if (remoteCtsExtraPath == null) {
                    if (mThrowIfExtraNotFound) {
                        throw new BuildRetrievalError(
                                String.format("Artifact %s is not found on build server.", extra),
                                InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
                    } else {
                        CLog.w("Artifact %s is not found on build server.", extra);
                        continue;
                    }
                }
                mExtraWithPath.put(extra, remoteCtsExtraPath);
            }

            if (useCasDefaultRollout()) {
                forceUseCas(true);
                mForceCas = true;
            }
        }
        return ctsBuild;
    }

    @Override
    File extractZip(File zippedFile, String prefix) throws IOException {
        if (!mCtsUseFusezip || !mIsFuseUtilAvailable) {
            return super.extractZip(zippedFile, prefix);
        }
        long startTime = System.currentTimeMillis();
        CLog.d("Start mounting zip file with fuse-zip %s.", zippedFile);
        // Extract inside the workfolder so everything is in one place.
        File extracted = FileUtil.createTempDir(prefix, getWorkFolder());
        FuseUtil fuseUtil = getFuseUtil();
        try {
            fuseUtil.mountZip(zippedFile, extracted);
        } catch (HarnessRuntimeException e) {
            fuseUtil.unmountZip(extracted);
            throw e;
        }
        long elapsedTime = System.currentTimeMillis() - startTime;
        CLog.d(
                "Done mounting zip file %s at mount point %s with fuse-zip in %s "
                        + "[zip file size: %s]",
                zippedFile,
                extracted,
                TimeUtil.formatElapsedTime(elapsedTime),
                zippedFile.length());
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricLogger.InvocationMetricKey.UNZIP_TESTS_DIR_TIME, elapsedTime);
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricLogger.InvocationMetricKey.UNZIP_TESTS_DIR_COUNT, 1);
        return extracted;
    }

    String findFilePath(String lcFileInfo, String fileName) {
        if (lcFileInfo.isEmpty()) {
            return null;
        }
        String[] files = lcFileInfo.split(",");
        for (int i = 0; i < files.length; i++) {
            if (files[i].endsWith(fileName)) {
                return files[i];
            }
        }
        return null;
    }

    private void xtsPartialDownload(
            IFolderBuildInfo localBuild,
            File localCtsRootDir,
            String remoteCtsZipPath,
            IFileDownloader downloader)
            throws BuildRetrievalError, IOException {
        mPartialDownloadMode = PartialDownloadMode.PartialDownloadTestModules;
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricLogger.InvocationMetricKey.XTS_PARTIAL_DOWNLOAD_TOTAL_COUNT, 1);
        boolean metadataMode = mCtsForceTestDiscoveryMetadataMode;
        String commandLine = getConfiguration().getCommandLine();
        if ((commandLine.contains(ITestSuite.MODULE_METADATA_INCLUDE_FILTER)
                        || commandLine.contains("multi-device-count"))
                && !commandLine.contains("--" + BaseTestSuite.INCLUDE_FILTER_OPTION)
                && !commandLine.contains(
                        "--compatibility:" + BaseTestSuite.INCLUDE_FILTER_OPTION)) {
            metadataMode = true;
        }

        CLog.d("metadataMode: %s", metadataMode);
        // Partial download necessities from the cts zip first
        List<String> necessities = new ArrayList<>();
        String ctsPackageName = mCtsPackageName.substring(0, mCtsPackageName.length() - 4);
        // Always reduce to the filename to avoid download sub-directory issue.
        ctsPackageName = new File(ctsPackageName).getName();
        necessities.add(ctsPackageName + "/tools");
        if (metadataMode) {
            necessities.addAll(CONFIG_ONLY_REGEX);
        }
        // TODO: Support optionally downloading the jdk
        CLog.d(
                "Attempting partial download of %s for its necessities %s",
                remoteCtsZipPath, necessities);

        downloader.downloadZippedFiles(
                localCtsRootDir, remoteCtsZipPath, new ArrayList<>(necessities), new ArrayList<>());

        // Discover test modules, then partial download those modules from the cts zip
        Map<String, List<String>> testDependenciesMap;
        List<String> testModuleNames = null;
        List<String> testDependencyNames = new ArrayList<>();
        boolean fallbackMetadata = false;
        boolean noPossibleDiscovery = false;
        try {
            if (mTestDiscoveryInvoker == null) {
                mTestDiscoveryInvoker =
                        new TestDiscoveryInvoker(
                                getConfiguration(),
                                getCtsDefaultConfigName(),
                                localCtsRootDir,
                                metadataMode,
                                false);
                mTestDiscoveryInvoker.setTestLogger(getTestLogger());
            }
            testDependenciesMap = mTestDiscoveryInvoker.discoverTestDependencies();
            CLog.d(
                    "Tradefed Observatory discovered test dependencies needed for this"
                            + " run: %s",
                    testDependenciesMap);
            testModuleNames =
                    testDependenciesMap.getOrDefault(
                            TestDiscoveryInvoker.TEST_MODULES_LIST_KEY, new ArrayList<>());
            testDependencyNames =
                    testDependenciesMap.getOrDefault(
                            TestDiscoveryInvoker.TEST_DEPENDENCIES_LIST_KEY, new ArrayList<>());
            if (testDependenciesMap.containsKey(TestDiscoveryInvoker.PARTIAL_FALLBACK_KEY)) {
                fallbackMetadata = true;
            }
            if (testDependenciesMap.containsKey(
                    TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY)) {
                noPossibleDiscovery = true;
            }
        } catch (ConfigurationException e) {
            CLog.e("Tradefed observatory failed with config exception:");
            CLog.e(e);
            throw new BuildRetrievalError(e.getMessage(), e);
        } catch (IOException | JSONException | TestDiscoveryException e) {
            CLog.e("Failed to invoke Tradefed Observatory:");
            CLog.e(e);
            if (e instanceof TestDiscoveryException
                    && DiscoveryExitCode.COMPONENT_METADATA.equals(
                            ((TestDiscoveryException) e).exitCode())) {
                CLog.d("Discovery detected metadata filters, using dynamic download staging.");
            } else {
                mPartialDownloadMode = PartialDownloadMode.PartialDownloadFallbackToFullDownload;
                mPartialDownloadFullFallbackReason = "Failed to invoke Tradefed Observatory";
                return;
            }
        }

        List<String> partialDownloadList = new ArrayList<>();
        // If we didn't discovery anything because it wasn't supported, fallback to partial
        // download.
        // If it was supported, don't fallback, we just found no tests.
        if (testModuleNames == null || (testModuleNames.isEmpty() && noPossibleDiscovery)) {
            CLog.i(
                    "Unable to discover test module names for partial download, will"
                            + " download all jars and configs, and let subprocess do test"
                            + " module download later.");
            mPartialDownloadMode = PartialDownloadMode.PartialDownloadJarsAndConfigs;

            partialDownloadList.addAll(JARS_AND_CONFIG_REGEX);
            partialDownloadList.addAll(testDependencyNames);
        } else {
            // Test modules are discovered.
            CLog.i(
                    "Discovered test module names for partial download, will"
                            + " download all necessary test modules.");
            if (mDelayDownloadInSharding
                    && getConfiguration().getCommandOptions().getShardCount() != null) {
                for (String moduleName : testModuleNames) {
                    partialDownloadList.add(String.format("/%s/.*\\.jar", moduleName));
                    partialDownloadList.add(String.format("/%s/.*\\.config", moduleName));
                }
                mPartialDownloadMode = PartialDownloadMode.PartialDownloadTestModulesWithDelay;
            } else {
                mPartialDownloadMode = PartialDownloadMode.PartialDownloadTestModules;
                partialDownloadList.addAll(testModuleNames);
            }
            partialDownloadList.addAll(testDependencyNames);
            partialDownloadList.addAll(mForcePartialDownloadRegex);
            // TODO: Always stage host_required. Reevaluate when b/262397555 resolve
            partialDownloadList.addAll(getTestDiscoveryForcedDownloadFiles(testModuleNames));
        }
        // Do partial download
        CLog.d(
                "Attempting partial download of %s with include filter regex %s",
                remoteCtsZipPath, partialDownloadList);
        downloader.downloadZippedFiles(
                localCtsRootDir,
                remoteCtsZipPath,
                new ArrayList<>(partialDownloadList),
                new ArrayList<>(necessities));
        File testcasesDir = new File(new File(localCtsRootDir, ctsPackageName), "testcases");
        if (fallbackMetadata) {
            Set<File> allConfigs = FileUtil.findFilesObject(testcasesDir, ".*\\.config");
            for (File f : allConfigs) {
                if (!testModuleNames.contains(FileUtil.getBaseName(f.getName()))) {
                    CLog.w("Removing %s module config as it won't run with current filters.", f);
                    FileUtil.deleteFile(f);
                }
            }
        }
        // Check if we got all test modules from partial download,
        // since it's not guaranteed that all test modules are
        // unflattened
        Set<String> ctsStagedDirectoryNameSet = new HashSet<String>();
        for (File file : testcasesDir.listFiles()) {
            if (file.isDirectory()) {
                ctsStagedDirectoryNameSet.add(file.getName());
            }
        }

        // Check if partial downloaded files are staged in cts root
        validateStagedFiles(
                ctsStagedDirectoryNameSet, testDependencyNames, testModuleNames, testcasesDir);

        CLog.i(
                String.format(
                        "Partial download is completed in mode %s.", mPartialDownloadMode.name()));
        // Check if fallback to full download is needed
        if (mPartialDownloadMode.equals(
                PartialDownloadMode.PartialDownloadFallbackToFullDownload)) {
            CLog.e(
                    String.format(
                            "Partial download XTS failed due to %s, fall back to full"
                                    + " download",
                            mPartialDownloadFullFallbackReason));
            FileUtil.recursiveDelete(localCtsRootDir);
        } else {
            CLog.d(
                    String.format(
                            "Partial download finished successfully, set Cts root"
                                    + " directory to %s",
                            localCtsRootDir));
            localBuild.setRootDir(localCtsRootDir);
        }
    }

    private void instrumentPartialDownloadResult(
            IFolderBuildInfo localBuild, String remoteCtsZipPath) {
        switch (mPartialDownloadMode) {
            case PartialDownloadTestModules:
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricLogger.InvocationMetricKey
                                .XTS_PARTIAL_DOWNLOAD_SUCCESS_COUNT,
                        1);
                setBuildRemoteFile(localBuild, remoteCtsZipPath);
                // In pure partial download avoid redownloading in subprocess
                localBuild.addBuildAttribute(ITestSuite.SKIP_STAGING_ARTIFACTS, "true");
                break;
            case PartialDownloadTestModulesWithDelay:
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricLogger.InvocationMetricKey
                                .XTS_PARTIAL_DOWNLOAD_SUCCESS_COUNT,
                        1);
                setBuildRemoteFile(localBuild, remoteCtsZipPath);
                break;
            case PartialDownloadJarsAndConfigs:
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricLogger.InvocationMetricKey
                                .XTS_PARTIAL_DOWNLOAD_UNSUPPORTED_FILTER_FALLBACK_COUNT,
                        1);
                setBuildRemoteFile(localBuild, remoteCtsZipPath);
                break;
            case PartialDownloadFallbackToFullDownload:
                CLog.e(
                        String.format(
                                "Partial download failed because of %s, fall back to full download",
                                mPartialDownloadFullFallbackReason));
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricLogger.InvocationMetricKey
                                .XTS_PARTIAL_DOWNLOAD_FALLBACK_COUNT,
                        1);
                break;
            case PartialDownloadNotInUse:
                break;
        }
    }

    private void validateStagedFiles(
            Set<String> ctsStagedDirectoryNameSet,
            List<String> testDependencyNames,
            List<String> testModuleNames,
            File testcasesDir) {
        if (mPartialDownloadMode.equals(PartialDownloadMode.PartialDownloadTestModules)
                || mPartialDownloadMode.equals(
                        PartialDownloadMode.PartialDownloadTestModulesWithDelay)) {
            // Only validate test modules if we were able to do the all partial download
            Set<String> unfound = new HashSet<>();
            for (String testModule : testModuleNames) {
                if (!ctsStagedDirectoryNameSet.contains(testModule)) {
                    unfound.add(testModule);
                }
            }
            if (!unfound.isEmpty()) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.XTS_PARTIAL_DOWNLOAD_UNFOUND_MODULES,
                        unfound.toString());
                CLog.w("Found some unmatched filters: %s", unfound);
            }
        }

        // Always validate test dependency files
        for (String testDependencyFileName : testDependencyNames) {
            if (FileUtil.findFile(testcasesDir, testDependencyFileName) == null) {
                mPartialDownloadMode = PartialDownloadMode.PartialDownloadFallbackToFullDownload;
                mPartialDownloadFullFallbackReason =
                        "Staged test dependency file is missing from the test directory after"
                                + " partial download completion";
                CLog.e(
                        String.format(
                                "Unable to locate test dependency file %s at %s after"
                                        + " partial download, test might not been"
                                        + " unflattened",
                                testDependencyFileName, testcasesDir.getAbsolutePath()));
                return;
            }
        }
    }

    @Override
    protected IBuildInfo downloadBuildFiles(
            RemoteBuildInfo remoteBuild,
            String testTargetName,
            String buildName,
            IFileDownloader downloader)
            throws BuildRetrievalError {
        ContentDownloaderThread contentThread = null;
        if (shouldReportContentInformation() && mHasCasDetails) {
            contentThread =
                    new ContentDownloaderThread(
                            Thread.currentThread().getThreadGroup(), mCtsRemoteBuild);
            contentThread.start();
        }
        // use a generic FolderBuildInfo to represent the CTS build, to avoid making
        // google_tradefederation dependent on CTS
        IFolderBuildInfo localBuild =
                new FolderBuildInfo(
                        remoteBuild.getBuildId(),
                        buildName,
                        mCtsUseFusezip && mIsFuseUtilAvailable);
        localBuild.setTestTag(testTargetName);
        String remoteCtsZipPath = remoteBuild.getAttribute(BuildAttributeKey.CTS);
        File ctsZip = null;

        try {
            File localCtsRootDir = null;
            long startTime = System.currentTimeMillis();
            if (remoteCtsZipPath == null) {
                throw new BuildRetrievalError(
                        String.format(
                                "No '%s' in the remoteBuild infos to be downloaded: %s",
                                BuildAttributeKey.CTS, remoteBuild.toString()),
                        InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
            }
            if (mApplyDefaultRollout) {
                if (mPartialDownloadDefaultRollout.contains(
                        String.format("%s:%s", mCtsPackageName, getBranch()))) {
                    CLog.d("Apply rollout strategy to partial download");
                    mCtsUsePartialDownload = true;
                }
            }
            if (mCtsUsePartialDownload) {
                localCtsRootDir =
                        FileUtil.createTempDir(
                                String.format("%s_%s_", getBuildFlavor(), remoteBuild.getBuildId()),
                                getWorkFolder());
                xtsPartialDownload(localBuild, localCtsRootDir, remoteCtsZipPath, downloader);
                instrumentPartialDownloadResult(localBuild, remoteCtsZipPath);
            }
            if (mPartialDownloadMode.equals(
                    PartialDownloadMode.PartialDownloadFallbackToFullDownload)) {
                FileUtil.recursiveDelete(localCtsRootDir);
            }
            if (mPartialDownloadMode.equals(
                            PartialDownloadMode.PartialDownloadFallbackToFullDownload)
                    || mPartialDownloadMode.equals(PartialDownloadMode.PartialDownloadNotInUse)) {
                // Full download to get main package
                ctsZip = downloader.downloadFile(remoteCtsZipPath);
                // If the returned path is a directory, the artifact is downloaded from CAS, and
                // is already extracted. Directly use the path as the root dir.
                if (ctsZip.isDirectory()) {
                    localCtsRootDir = ctsZip;
                } else {
                    localCtsRootDir =
                            extractZip(
                                    ctsZip,
                                    String.format(
                                            "%s_%s_", getBuildFlavor(), remoteBuild.getBuildId()));
                }
                localBuild.setRootDir(localCtsRootDir);
            }
            Set<String> extras = new HashSet<>();
            extras.addAll(mExtraArtifact);
            // Get extras from build server
            for (String extraName : mExtraWithPath.keySet()) {
                File ctsExtra = downloader.downloadFile(mExtraWithPath.get(extraName));
                localBuild.setFile(extraName, ctsExtra, remoteBuild.getBuildId());
                mFileCreated.add(ctsExtra);
                extras.remove(extraName);
            }
            // if we have an alternative folder to look for the extra
            if (mExtraAltPath != null) {
                for (String extraName : extras) {
                    File ctsExtra = new File(mExtraAltPath, extraName);
                    if (ctsExtra.exists()) {
                        String version = "1";
                        if (mExtraAltPath instanceof ExtendedFile) {
                            version = ((ExtendedFile) mExtraAltPath).getBuildId();
                        }
                        localBuild.setFile(extraName, ctsExtra, version);
                        mFileCreated.add(ctsExtra);
                    } else {
                        CLog.w(
                                "%s was not found on build server and in alternate location: %s",
                                extraName, mExtraAltPath);
                    }
                }
            }
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricLogger.InvocationMetricKey.XTS_STAGE_TESTS_TIME,
                    System.currentTimeMillis() - startTime);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricLogger.InvocationMetricKey.XTS_STAGE_TESTS_BYTES,
                    FileUtil.sizeOfDirectory(localCtsRootDir));

            return localBuild;
        } catch (BuildRetrievalError e) {
            e.setBuildInfo(localBuild);
            throw e;
        } catch (IOException e) {
            // clear the cache before returning
            deleteCacheEntry(remoteCtsZipPath);
            throw new BuildRetrievalError("Failed to extract " + mCtsPackageName, e, localBuild);
        } finally {
            FileUtil.deleteFile(ctsZip);
            if (contentThread != null) {
                try {
                    contentThread.join();
                } catch (InterruptedException e) {
                    CLog.e(e);
                }
            }
        }
    }

    private String getCtsDefaultConfigName() throws ConfigurationException {
        List<IRemoteTest> testList = getConfiguration().getTests();
        // For XTS tests, there should be only one IRemoteTest in the parent process's test list.
        IRemoteTest testLauncher = testList.get(0);
        if (testLauncher instanceof CtsTestLauncher) {
            return ((CtsTestLauncher) testLauncher).getConfigName();
        } else {
            throw new ConfigurationException(
                    "Failed to cast to CtsTestLauncher, unable to get the default test config"
                            + " name.");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void cleanUp(IBuildInfo info) {
        info.cleanUp();
        cleanExtra();
    }

    public void cleanExtra() {
        // Clean up extras if any
        for (File f : mFileCreated) {
            if (f.exists()) {
                FileUtil.deleteFile(f);
            }
        }
    }

    private class ContentDownloaderThread extends Thread {

        private RemoteBuildInfo remoteBuild;

        public ContentDownloaderThread(ThreadGroup group, RemoteBuildInfo remoteBuild) {
            super(group, "ContentDownloaderThread");
            setDaemon(true);
            this.remoteBuild = remoteBuild;
        }

        @Override
        public void run() {
            ContentInformation info =
                    ContentBasedHelper.getContentInformation(
                            remoteBuild, getBranch(), getBuildFlavor(), remoteBuild.getBuildId());
            ContentAnalysisContext context =
                    new ContentAnalysisContext(mCtsPackageName, info, AnalysisMethod.MODULE_XTS);
            String rootPackage = mCtsPackageName.replaceAll(".zip", "");
            context.addCommonLocation(rootPackage + "/tools/");
            context.addIgnoreChange(rootPackage + "/tools/version.txt");
            if (info == null || !info.isValid()) {
                context.invalidateAnalysis();
            }
            getConfiguration().getSkipManager().setTestArtifactsAnalysis(context);
        }
    }

    @Override
    public Set<String> reportTestZipFileFilter() {
        Set<String> filters = new HashSet<>();
        filters.addAll(super.reportTestZipFileFilter());
        filters.add(mCtsPackageName);
        return filters;
    }
}
