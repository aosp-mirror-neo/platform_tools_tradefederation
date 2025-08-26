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
package com.google.android.tradefed.sandbox;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.build.VersionedFile;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.DynamicRemoteFileResolver;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.error.IHarnessException;
import com.android.tradefed.invoker.ExecutionFiles;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.observatory.TestDiscoveryInvoker;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.sandbox.SandboxOptions;
import com.android.tradefed.sandbox.TradefedSandbox;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.ZipUtil2;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;
import com.android.tradefed.util.keystore.IKeyStoreClient;

import com.google.android.tradefed.build.LCUtil;
import com.google.android.tradefed.build.LaunchControlProvider;
import com.google.android.tradefed.build.TfLaunchControlProvider;
import com.google.android.tradefed.util.ClasspathLauncherUtil;
import com.google.android.tradefed.util.TestDiscoveryInfo;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Internal specialization of {@link TradefedSandbox} to be used in the lab infrastructure. This
 * address the download need of the proper version associated with the build. We might be able to
 * move this to AOSP if one day we can rely on AOSP API to download builds.
 */
public class GoogleTradefedSandbox extends TradefedSandbox {

    /** Key of extra_builds coming from ATP */
    private static final String EXTRA_BUILDS_KEY = "extra_builds";

    private static final Map<String, List<String>> PURE_DISCOVERY_DOWNLOAD =
            ImmutableMap.of(
                    "host-unit-tests.zip",
                    Arrays.asList("host/testcases/lib/", "host/testcases/lib64/"),
                    "robolectric-tests.zip",
                    Arrays.asList("host/testcases/android-all/"),
                    "art-host-tests.zip",
                    Arrays.asList("host/testcases/lib/", "host/testcases/lib64/"),
                    "ravenwood-tests.zip",
                    Arrays.asList(
                            "host/testcases/ravenwood-utils/",
                            "host/testcases/ravenwood-runtime/"));
    private static final Map<String, List<String>> PARTIAL_DOWNLOAD_REPLACEMENTS = new HashMap<>();
    private static final Map<String, List<String>> PARTIAL_DOWNLOAD_OPTIONAL_REPLACEMENTS =
            new HashMap<>();

    static {
        PARTIAL_DOWNLOAD_REPLACEMENTS.put(
                "general-tests.zip", Arrays.asList("general-tests_configs.zip"));
        PARTIAL_DOWNLOAD_OPTIONAL_REPLACEMENTS.put(
                "general-tests.zip", Arrays.asList("general-tests_host-shared-libs.zip"));
        PARTIAL_DOWNLOAD_REPLACEMENTS.put(
                "automotive-general-tests.zip",
                Arrays.asList(
                        "automotive-general-tests_configs.zip",
                        "automotive-general-tests_host-shared-libs.zip"));
    }

    private static final Map<String, List<String>> REPLACE_REQUIRED_DOWNLOAD =
            ImmutableMap.of(
                    "general-tests.zip",
                    Arrays.asList("host/testcases/lib/", "host/testcases/lib64/"));

    private File mWorkingDir = null;
    private File mInProgressWorkingDir = null;
    private IFolderBuildInfo mDownloadedBuild = null;
    private boolean mWasFallbackUsed = false;
    private boolean mDiscoveryRan = false;
    private Map<String, List<String>> mDiscoveryResults;
    private Map<File, String> mClasspathCache = new HashMap<>();
    private Set<String> mExtraFilters = new HashSet<>();

    private static final String TRADEFED_MAIN_JAR = "^tradefed.jar";
    private static final List<String> TRADEFED_JARS =
            new ArrayList<>(
                    Arrays.asList(
                            // Loganalysis
                            "^loganalysis.jar",
                            "^loganalysis-tests.jar",
                            // Aosp Tf jars
                            "^tradefed.jar",
                            "^tradefed-test-framework.jar",
                            "^tradefed-tests.jar",
                            // AVD util test jar
                            "^tradefed-avd-util-tests.jar",
                            // libs
                            "^tools-common-prebuilt.jar",
                            // Aosp contrib jars
                            "^tradefed-contrib.jar",
                            "^tf-contrib-tests.jar",
                            // Google Tf jars
                            "^google-tf-prod-tests.jar",
                            "^google-tf-prod-metatests.jar",
                            "^google-tradefed.jar",
                            "^google-tradefed-tests.jar",
                            // Google contrib jars
                            "^google-tradefed-contrib.jar"));
    private static final List<String> EXCLUDED_CP_PATTERNS = new ArrayList<String>();

    static {
        EXCLUDED_CP_PATTERNS.addAll(TRADEFED_JARS);
        EXCLUDED_CP_PATTERNS.add("^art-run-test.*");
        EXCLUDED_CP_PATTERNS.add("^art_standalone.*");
        EXCLUDED_CP_PATTERNS.add("^art-gtest.*");
        EXCLUDED_CP_PATTERNS.add("^Robolectric_.*");
    }

    private boolean mIsAosp = false;

    // A list of extra build artifacts to be staged for sandbox.
    private final Set<String> mExtraBuildArtifacts = new LinkedHashSet<>();

    private Set<String> mListModules = new LinkedHashSet<>();

    // Get the extra build artifacts that will be tried to be unziped in working directory.
    @VisibleForTesting
    Set<String> getExtraBuildArtifacts() {
        return mExtraBuildArtifacts;
    }

    @Override
    protected File dumpGlobalConfig(IConfiguration config, Set<String> exclusionPatterns)
            throws IOException, ConfigurationException {
        Set<String> filter = new HashSet<>();
        filter.addAll(exclusionPatterns);
        if (mIsAosp) {
            filter.add("com\\.google\\.android\\.tradefed.*");
        }
        return super.dumpGlobalConfig(config, filter);
    }

    @Override
    public IFolderBuildInfo fetchSandboxExtraArtifacts(
            IInvocationContext context, IConfiguration nonVersionedConfig, String[] args)
            throws BuildRetrievalError, ConfigurationException, IOException {
        if (mDownloadedBuild != null) {
            return mDownloadedBuild;
        }
        mInProgressWorkingDir = FileUtil.createTempDir("tmp-working-dir");
        SandboxOptions options =
                (SandboxOptions)
                        nonVersionedConfig.getConfigurationObject(
                                Configuration.SANBOX_OPTIONS_TYPE_NAME);
        try (CloseableTraceScope ignored = new CloseableTraceScope("download sandbox artifacts")) {
            TfLaunchControlProvider provider = createBuildProvider();

            // First copy all the options received to ensure we download from the same end-point
            IBuildProvider primaryNonVersionedProvider =
                    nonVersionedConfig.getDeviceConfig().get(0).getBuildProvider();
            OptionCopier.copyOptions(primaryNonVersionedProvider, provider);
            provider.setConfiguration(nonVersionedConfig);
            provider.setInvocationContext(context);
            // Then ensure the additional-file-filters are reset. Everything in the filter should
            // have already been downloaded.
            provider.setAdditionalFilesFilters(new ArrayList<>());
            // Always inspect aosp too if google-tradefed.zip is missing for any reason.
            provider.setAospFallback(true);
            provider.setPartialDownloadForZip(options.shouldUsePartialDownload());
            provider.setParallelSecondaryDownload(options.shouldUseParallelDownload());
            provider.clearExtraDownloads();
            // Do not re-inspect the cloud args
            provider.setSkipCloudContent(true);

            String buildId = null;
            String branch = null;
            String buildTarget = null;
            String buildOs = null;
            // If extra_builds are available, use them.
            List<ExtraBuildsData> extraBuilds = null;
            if (context.getAttributes().containsKey(EXTRA_BUILDS_KEY)) {
                // TODO: handle multiple extra_builds
                try {
                    extraBuilds =
                            ExtraBuildsData.parseFromJson(
                                    context.getAttributes().get(EXTRA_BUILDS_KEY).get(0));
                } catch (JSONException e) {
                    throw new ConfigurationException(
                            "Failed to parse the extra_builds invocation data.", e);
                }
                buildId = extraBuilds.get(0).mBuildId;
                branch = extraBuilds.get(0).mBranch;
                buildTarget = extraBuilds.get(0).mBuildTarget;
                buildOs = extraBuilds.get(0).mBuildOs;
            }
            // If we don't have extra_builds we try to download from the original target since
            // that's most likely the test_suites target we want (Otherwise it would be in
            // extra_builds).
            if (extraBuilds != null) {
                // Then override necessary fields.
                provider.setBuildId(buildId);
                provider.setBranch(branch);
                // We should always use "build_target"
                provider.setBuildFlavor(buildTarget);
                provider.setBuildOs(buildOs);
            } else {
                // Still set the branch so the aosp check works
                branch = provider.getBranch();
            }
            checkIfAosp(branch);
            provider.setTestTag(context.getTestTag());
            // Add possible additional targets
            addAdditionalDownloadTargets(args, provider, options);

            mDownloadedBuild = (IFolderBuildInfo) provider.getBuild();
            if (mDownloadedBuild == null) {
                FileUtil.recursiveDelete(mInProgressWorkingDir);
                throw new ConfigurationException("Failed to download the versioned Tradefed build");
            }
            mWasFallbackUsed = provider.wasFallbackUsed();
        }
        FileUtil.recursiveHardlink(mDownloadedBuild.getRootDir(), mInProgressWorkingDir);
        if (!context.getBuildInfos().isEmpty()) {
            FileUtil.recursiveHardlink(
                    mDownloadedBuild.getRootDir(), getOrCreateTestsDir(context), true);
        }
        CLog.d("Linking extra sandbox targets to working dir");
        getExtraTargets(mInProgressWorkingDir, mDownloadedBuild, getExtraBuildArtifacts());
        if (!context.getBuildInfos().isEmpty()) {
            FileUtil.recursiveHardlink(mInProgressWorkingDir, getOrCreateTestsDir(context), true);
        }
        return mDownloadedBuild;
    }

    @Override
    public Map<String, List<String>> discoverTests(
            IInvocationContext context, IConfiguration nonVersionedConfig, ITestLogger logger) {
        if (mDiscoveryRan) {
            return mDiscoveryResults;
        }
        mDiscoveryRan = true;
        // Create sandbox tests dir as early as possible, but only when we are sure that
        // fetchBuild has run so BuildInfo exists
        if (!context.getBuildInfos().isEmpty()) {
            try {
                File testsDir = getOrCreateTestsDir(context);
                FileUtil.recursiveHardlink(testsDir, mInProgressWorkingDir, true);
                // Sync the rootDir to testsDir
                FileUtil.recursiveHardlink(mDownloadedBuild.getRootDir(), testsDir, true);
            } catch (IOException e) {
                CLog.e(e);
            }
        }
        // Copy secondary build remote-files to primary
        IBuildInfo primaryBuild = context.getBuildInfos().get(0);
        for (File artifact : mDownloadedBuild.getRemoteFiles()) {
            primaryBuild.setFile(
                    IBuildInfo.REMOTE_FILE_PREFIX + artifact.toString(),
                    artifact,
                    IBuildInfo.REMOTE_FILE_VERSION);
        }
        if (primaryBuild.getRemoteFiles().isEmpty()) {
            CLog.d("No remote files configured.");
            return null;
        }
        CLog.d("Remote files configured: %s", primaryBuild.getRemoteFiles());
        SandboxOptions options =
                (SandboxOptions)
                        nonVersionedConfig.getConfigurationObject(
                                Configuration.SANBOX_OPTIONS_TYPE_NAME);
        if (!options.shouldUseTestDiscovery()) {
            return null;
        }
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.SANDBOX_JAR_STAGING_PARTIAL_DOWNLOAD_FEATURE_COUNT, 1);
        TestDiscoveryInvoker invoker =
                new TestDiscoveryInvoker(
                        nonVersionedConfig, null, mInProgressWorkingDir, true, mWasFallbackUsed);
        invoker.setTestLogger(logger);
        invoker.setBuildInfo(primaryBuild);
        File testMappingZip = primaryBuild.getFile("test_mappings.zip");
        if (testMappingZip != null) {
            invoker.setTestMappingZip(testMappingZip);
        }
        try {
            mDiscoveryResults = invoker.discoverTestMappingDependencies();
            return mDiscoveryResults;
        } catch (Exception e) {
            CLog.e(
                    "Tradefed Observatory failed to discovery test modules"
                            + " for sandbox jar staging");
            CLog.e(e);
        }
        return null;
    }

    @Override
    public File getTradefedSandboxEnvironment(
            IInvocationContext context,
            IConfiguration nonVersionedConfig,
            ITestLogger logger,
            String[] args)
            throws Exception {
        if (mWorkingDir != null) {
            return mWorkingDir;
        }

        long startTime = System.currentTimeMillis();
        SandboxOptions options =
                (SandboxOptions)
                        nonVersionedConfig.getConfigurationObject(
                                Configuration.SANBOX_OPTIONS_TYPE_NAME);
        try {
            IBuildProvider primaryNonVersionedProvider =
                    nonVersionedConfig.getDeviceConfig().get(0).getBuildProvider();
            LaunchControlProvider originalLauncher = null;
            if (primaryNonVersionedProvider instanceof LaunchControlProvider) {
                originalLauncher = (LaunchControlProvider) primaryNonVersionedProvider;
            }
            mDownloadedBuild = fetchSandboxExtraArtifacts(context, nonVersionedConfig, args);
            mWorkingDir = mInProgressWorkingDir;

            if (!context.getBuildInfos().isEmpty()) {
                IBuildInfo primaryBuild = context.getBuildInfos().get(0);
                // Copy the remote zip artifact to the primary build. The primary build is used to
                // build the working dir of the sandbox.
                if (!mDownloadedBuild.getRemoteFiles().isEmpty()
                        || !primaryBuild.getRemoteFiles().isEmpty()) {
                    Map<String, List<String>> testDiscoveryResult =
                            discoverTests(context, nonVersionedConfig, logger);
                    mInProgressWorkingDir = null; // This variable isn't needed after this.
                    Boolean isTestDiscoverySucceed = false;
                    boolean noDiscovery = false;
                    // if discovery failed or reported no possible discovery
                    if (testDiscoveryResult == null
                            || testDiscoveryResult.containsKey(
                                    TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY)) {
                        noDiscovery = true;
                    }
                    if (options.shouldUseTestDiscovery()
                            && testDiscoveryResult != null
                            && !noDiscovery) {
                        try {
                            List<String> testModuleNames =
                                    testDiscoveryResult.getOrDefault(
                                            TestDiscoveryInvoker.TEST_MODULES_LIST_KEY,
                                            new ArrayList<>());
                            if (testDiscoveryResult.containsKey(
                                    TestDiscoveryInvoker.PARTIAL_FALLBACK_KEY)) {
                                mListModules.addAll(testModuleNames);
                            }
                            boolean delayDownload =
                                    options.delayDownloadAfterSharding()
                                            && nonVersionedConfig
                                                            .getCommandOptions()
                                                            .getShardCount()
                                                    != null;
                            boolean dynamicSharding =
                                    nonVersionedConfig
                                            .getCommandOptions()
                                            .shouldRemoteDynamicShard();
                            List<String> stagedFiles = new ArrayList<>();
                            // Stage the module artifacts
                            for (String moduleName : testModuleNames) {
                                if (delayDownload) {
                                    stagedFiles.add(String.format("/%s/.*\\.jar", moduleName));
                                    stagedFiles.add(String.format("/%s/.*\\.config", moduleName));
                                } else {
                                    stagedFiles.add(String.format("/%s/", moduleName));
                                }
                            }
                            stagedFiles.addAll(mExtraFilters);
                            stagedFiles.add("host/tools/.*\\.jar");
                            // TODO: Always stage host_required. Reevaluate when b/262397555 resolve
                            stagedFiles.addAll(
                                    TestDiscoveryInfo.getAlwaysDownloadFilesList(
                                            testModuleNames, primaryBuild.getBuildBranch()));
                            stagedFiles.addAll(options.getForcePartialDownloadFileRegexList());
                            stageJars(primaryBuild, stagedFiles, options, originalLauncher);
                            isTestDiscoverySucceed = true;
                            if (delayDownload) {
                                // For dynamic sharding move the download per-module
                                if (dynamicSharding) {
                                    primaryBuild.addBuildAttribute(
                                            ITestSuite.SKIP_STAGING_ARTIFACTS, "true");
                                    primaryBuild.addBuildAttribute(
                                            ITestSuite.STAGE_MODULE_ARTIFACTS, "true");
                                }
                                // Otherwise it will stage once before suite
                            } else {
                                // This ensure no staging in sandbox
                                primaryBuild.addBuildAttribute(
                                        ITestSuite.SKIP_STAGING_ARTIFACTS, "true");
                            }
                        } catch (BuildRetrievalError bre) {
                            CLog.e("Failed to stage discovered modules.");
                            CLog.e(bre);
                        }
                    }
                    if (!isTestDiscoverySucceed) {
                        // Fallback to stage all jars and sometimes config
                        if (noDiscovery) {
                            List<String> filters = new ArrayList<>(mExtraFilters);
                            filters.add("[.]jar$");
                            filters.add("[.]config$");
                            stageJars(primaryBuild, filters, options, originalLauncher);
                        } else {
                            List<String> filters = new ArrayList<>(mExtraFilters);
                            filters.add("[.]jar$");
                            stageJars(primaryBuild, filters, options, originalLauncher);
                        }
                    } else {
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey
                                        .SANDBOX_JAR_STAGING_PARTIAL_DOWNLOAD_SUCCESS_COUNT,
                                1);
                    }
                }

                VersionedFile generalTestZip =
                        mDownloadedBuild.getVersionedFile(TradefedSandbox.GENERAL_TESTS_ZIP);
                if (generalTestZip != null) {
                    primaryBuild.setFile(
                            TradefedSandbox.GENERAL_TESTS_ZIP,
                            generalTestZip.getFile(),
                            generalTestZip.getVersion());
                }
            }
            for (IBuildInfo build : context.getBuildInfos()) {
                if (build instanceof IFolderBuildInfo) {
                    IFolderBuildInfo folderBuildInfo = (IFolderBuildInfo) build;
                    File rootDir = folderBuildInfo.getRootDir();
                    CLog.d("Linking root directory to working dir");
                    FileUtil.recursiveHardlink(rootDir, mWorkingDir, true);
                }
            }
            // Unset extra downloads to avoid re-downloads in subprocess
            if (primaryNonVersionedProvider instanceof LaunchControlProvider) {
                ((LaunchControlProvider) primaryNonVersionedProvider)
                        .setAdditionalFilesFilters(new ArrayList<>());
            }
        } catch (RuntimeException | IOException | BuildRetrievalError e) {
            FileUtil.recursiveDelete(mWorkingDir);
            FileUtil.recursiveDelete(mInProgressWorkingDir);
            CLog.e(e);
            if (e instanceof IHarnessException) {
                throw e;
            }
            throw new HarnessRuntimeException(
                    String.format("failed to prepare environment: %s", e.getMessage()),
                    e,
                    InfraErrorIdentifier.UNDETERMINED);
        }
        // Only log metrics data for test runs with partial download enabled.
        if (!mDownloadedBuild.getRemoteFiles().isEmpty()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.STAGE_TESTS_TIME, System.currentTimeMillis() - startTime);
        }
        CLog.logAndDisplay(
                LogLevel.DEBUG, "Content of working dir: %s", Arrays.asList(mWorkingDir.list()));
        return mWorkingDir;
    }

    /**
     * Stage all the jar files from the remote zip artifacts in the working directory.
     *
     * @param buildInfo a {@link IBuildInfo} of the build used for the invocation.
     * @throws BuildRetrievalError if failed to download remote files.
     */
    private void stageJars(
            IBuildInfo buildInfo,
            List<String> includeFilters,
            SandboxOptions options,
            LaunchControlProvider originalProvider)
            throws BuildRetrievalError {
        if (buildInfo.getRemoteFiles().isEmpty()) {
            return;
        }
        ParallelDeviceExecutor<Boolean> executor =
                new ParallelDeviceExecutor<>(buildInfo.getRemoteFiles().size());
        List<Callable<Boolean>> callableTasks = new ArrayList<>();

        for (File artifact : buildInfo.getRemoteFiles()) {
            Callable<Boolean> callableTask =
                    () -> {
                        downloadFiles(
                                artifact.toString(), includeFilters, options, originalProvider);
                        return true;
                    };
            callableTasks.add(callableTask);
        }
        executor.invokeAll(callableTasks, 0L, TimeUnit.SECONDS);
        if (executor.hasErrors()) {
            for (Throwable t : executor.getErrors()) {
                if (t instanceof BuildRetrievalError) {
                    throw (BuildRetrievalError) t;
                }
                throw new RuntimeException(t);
            }
        }
    }

    private void downloadFiles(
            String remoteLink,
            List<String> includeFilters,
            SandboxOptions options,
            LaunchControlProvider originalProvider)
            throws BuildRetrievalError {
        try (CloseableTraceScope ignored = new CloseableTraceScope("staging " + remoteLink)) {
            String remotePath = LCUtil.reverseDynamicAbPath(remoteLink);
            if (remotePath == null || originalProvider == null) {
                DynamicRemoteFileResolver resolver = new DynamicRemoteFileResolver();
                if (options.shouldUsePartialDownload()) {
                    resolver.addExtraArgs(
                            Map.of(
                                    "use-partial-download-cache", "true",
                                    "enable-resolve-sym-links", "true"));
                }
                resolver.resolvePartialDownloadZip(
                        mWorkingDir, remoteLink, includeFilters, new ArrayList<String>());
            } else {
                File downloadDir = null;
                try {
                    downloadDir = FileUtil.createTempDir("temp_sandbox_download");
                    IFileDownloader downloader =
                            originalProvider.createLaunchControlDownloader(null);
                    downloader.downloadZippedFiles(
                            downloadDir, remotePath, includeFilters, new ArrayList<String>());
                    FileUtil.recursiveHardlink(downloadDir, mWorkingDir, true);
                    if (!options.isValidationTest()) {
                        logModulesFromDownload(downloadDir, remotePath);
                    }
                } catch (IOException e) {
                    throw new BuildRetrievalError(e.getMessage(), e);
                } finally {
                    FileUtil.recursiveDelete(downloadDir);
                }
            }
        }
    }

    private void logModulesFromDownload(File downloadDir, String remotePath) {
        String zipName = new File(remotePath).getName();
        List<String> moduleName = new ArrayList<String>();
        File targetTestCases = new File(downloadDir, "target/testcases/");
        if (targetTestCases.exists() && targetTestCases.isDirectory()) {
            String[] moduleFolders = targetTestCases.list();
            if (moduleFolders != null) {
                moduleName.addAll(Arrays.asList(moduleFolders));
            }
        }
        File hostTestCases = new File(downloadDir, "host/testcases/");
        if (hostTestCases.exists() && hostTestCases.isDirectory()) {
            String[] moduleFolders = hostTestCases.list();
            if (moduleFolders != null) {
                moduleName.addAll(Arrays.asList(moduleFolders));
            }
        }
        if (!moduleName.isEmpty()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.AB_MODULE_IN_ZIP,
                    zipName + "=" + Joiner.on(";").join(moduleName));
        }
    }

    private void checkIfAosp(String branch) {
        // TODO: Find a better way to determine if a branch is AOSP or not.
        if (branch != null
                && (branch.contains("aosp")
                        || branch.contains("-partner")
                        || branch.contains("-qcom"))) {
            mIsAosp = true;
        }
    }

    /** Google Tradefed Sandbox only use the jars inside the working dir. */
    @Override
    public String createClasspath(File workingDir) throws ConfigurationException {
        if (mClasspathCache.containsKey(workingDir)) {
            return mClasspathCache.get(workingDir);
        }
        try (CloseableTraceScope ignored = new CloseableTraceScope("createClasspath")) {
            // Include all jars on the classpath
            String classpath = "";
            // Include Tradefed jar first always since that's how they are in Atest
            List<String> tfJars = new ArrayList<>();
            File mainTfFileJar = FileUtil.findFile(workingDir, TRADEFED_MAIN_JAR);
            if (mainTfFileJar != null) {
                for (String tfJar : TRADEFED_JARS) {
                    String realName = tfJar.substring(1);
                    File tfFileJar = new File(mainTfFileJar.getParentFile(), realName);
                    if (tfFileJar.exists()) {
                        tfJars.add(tfFileJar.getAbsolutePath());
                    }
                }
            }
            classpath = Joiner.on(":").join(tfJars);

            try {
                List<File> jars = ClasspathLauncherUtil.getJars(workingDir, EXCLUDED_CP_PATTERNS);
                String nonTfClasspath = Joiner.on(":").join(jars);
                if (!Strings.isNullOrEmpty(nonTfClasspath)) {
                    classpath += ":" + nonTfClasspath;
                }
            } catch (IOException e) {
                throw new ConfigurationException("Failed to createClasspath", e);
            }
            mClasspathCache.put(workingDir, classpath);
            return classpath;
        }
    }

    @Override
    protected File prepareContext(IInvocationContext context, IConfiguration config)
            throws IOException {
        // We copy the artifact from the extra build targets to the test dir to be picked up.
        IBuildInfo primaryBuild = context.getBuildInfos().get(0);
        if (primaryBuild instanceof IDeviceBuildInfo) {
            File testsDir = getOrCreateTestsDir(context);
            FileUtil.recursiveHardlink(mWorkingDir, testsDir, true);
            if (!mListModules.isEmpty()) {
                Set<File> allConfigs = FileUtil.findFilesObject(testsDir, ".*\\.config");
                for (File f : allConfigs) {
                    if (!mListModules.contains(FileUtil.getBaseName(f.getName()))) {
                        CLog.d(
                                "Removing %s module config as it won't run with current filters.",
                                f);
                        FileUtil.deleteFile(f);
                    }
                }
                mListModules.clear();
            }
        }

        return super.prepareContext(context, config);
    }

    @Override
    public void tearDown() {
        try {
            super.tearDown();
        } finally {
            mClasspathCache.clear();
            FileUtil.recursiveDelete(mWorkingDir);
            if (mDownloadedBuild != null) {
                mDownloadedBuild.cleanUp();
            }
        }
    }

    @VisibleForTesting
    TfLaunchControlProvider createBuildProvider() {
        return new TfLaunchControlProvider();
    }

    /** Attempt to make an educated guess on extra needed artifacts. */
    @VisibleForTesting
    void addAdditionalDownloadTargets(
            String[] args, TfLaunchControlProvider provider, SandboxOptions options) {
        CLog.logAndDisplay(LogLevel.DEBUG, "args:%s", Arrays.asList(args));
        String configName = args[0];
        if (options.getTestsZips().isEmpty() && options.downloadDefaultZips()) {
            mExtraBuildArtifacts.addAll(matchSandboxExtraBuildTargetByConfigName(configName));
        }
        mExtraBuildArtifacts.addAll(options.getTestsZips());
        for (String possibleTarget : mExtraBuildArtifacts) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.AB_TEST_ZIP_NAME,
                    provider.getBuildFlavor() + ":" + possibleTarget);
            if (PARTIAL_DOWNLOAD_REPLACEMENTS.containsKey(possibleTarget)
                    && provider.getTestConfigOnlyZip() != null) {
                provider.addRemoteZipFilter(".*/" + possibleTarget);
                List<String> extraPartialDownload =
                        PARTIAL_DOWNLOAD_REPLACEMENTS.get(possibleTarget);
                List<String> optionalExtraPartialDownload =
                        PARTIAL_DOWNLOAD_OPTIONAL_REPLACEMENTS.get(possibleTarget);
                CLog.d(
                        "Partial download is enabled, downloading extra artifacts: %s",
                        extraPartialDownload);
                for (String target : extraPartialDownload) {
                    // Add additional files to be downloaded in provider.
                    provider.addFileFilter(".*/" + target);
                    mExtraBuildArtifacts.add(target);
                }
                if (optionalExtraPartialDownload != null) {
                    for (String target : optionalExtraPartialDownload) {
                        // Add additional files to be downloaded in provider.
                        provider.addOptionalFileFilter(".*/" + target);
                        mExtraBuildArtifacts.add(target);
                    }
                }
                // Lab related zip shall not be among the extra download build artifacts, as
                // it will be downloaded partially later.
                mExtraBuildArtifacts.remove(possibleTarget);
                if (REPLACE_REQUIRED_DOWNLOAD.get(possibleTarget) != null) {
                    mExtraFilters.addAll(REPLACE_REQUIRED_DOWNLOAD.get(possibleTarget));
                }
            } else if (options.shouldUseTestDiscovery()
                    && PURE_DISCOVERY_DOWNLOAD.containsKey(possibleTarget)) {
                CLog.logAndDisplay(LogLevel.DEBUG, "Delaying download of %s", possibleTarget);
                provider.addRemoteZipFilter(".*/" + possibleTarget);
                mExtraBuildArtifacts.remove(possibleTarget);
                mExtraFilters.addAll(PURE_DISCOVERY_DOWNLOAD.get(possibleTarget));
            } else {
                CLog.logAndDisplay(LogLevel.DEBUG, "Downloading extra: %s", possibleTarget);
                provider.addFileFilter(".*/" + possibleTarget);
            }
            provider.addConsideredEntryForContent(possibleTarget);
        }
    }

    /** If the extra targets are zip file, unpack them in the working dir. */
    private static void getExtraTargets(
            File workingDir, IBuildInfo info, Set<String> extraBuildArtifacts) throws IOException {
        for (String extraTarget : extraBuildArtifacts) {
            CLog.logAndDisplay(
                    LogLevel.DEBUG, "extra:%s - %s", extraTarget, info.getFile(extraTarget));
            if (info.getFile(extraTarget) != null && extraTarget.endsWith(".zip")) {
                File target = info.getFile(extraTarget);
                if (target.isDirectory()) {
                    try (CloseableTraceScope ignored =
                            new CloseableTraceScope("hardlink " + target.getName())) {
                        FileUtil.recursiveHardlink(info.getFile(extraTarget), workingDir, true);
                    }
                } else {
                    try (CloseableTraceScope ignored =
                            new CloseableTraceScope("unzip " + target.getName())) {
                        long startTime = System.currentTimeMillis();
                        CLog.logAndDisplay(LogLevel.DEBUG, "extracting %s", extraTarget);
                        try (ZipFile zip = new ZipFile(target)) {
                            ZipUtil2.extractZip(zip, workingDir);
                        }
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.UNZIP_TESTS_DIR_TIME, elapsedTime);
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.UNZIP_TESTS_DIR_COUNT, 1);
                    }
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public IConfiguration createThinLauncherConfig(
            String[] args, IKeyStoreClient keyStoreClient, IRunUtil runUtil, File globalConfig) {
        CLog.d("Using GoogleTradefedSandbox thin launcher");
        SandboxThinLauncher thinLauncher =
                new SandboxThinLauncher(this, null, args, runUtil, globalConfig);
        return thinLauncher.createConfiguration(keyStoreClient);
    }

    private File getOrCreateTestsDir(IInvocationContext context) {
        IBuildInfo primaryBuild = context.getBuildInfos().get(0);
        File testsDir = primaryBuild.getFile(BuildInfoFileKey.TESTDIR_IMAGE);
        if (testsDir == null) {
            try {
                testsDir = FileUtil.createTempDir("google-sandbox-tests-dir");
                primaryBuild.setFile(BuildInfoFileKey.TESTDIR_IMAGE, testsDir, "1");
            } catch (IOException e) {
                CLog.e(e);
                throw new RuntimeException(e);
            }
        }
        if (CurrentInvocation.getInvocationFiles() != null) {
            CurrentInvocation.getInvocationFiles()
                    .put(ExecutionFiles.FilesKey.TESTS_DIRECTORY, testsDir);
        }
        return testsDir;
    }
}
