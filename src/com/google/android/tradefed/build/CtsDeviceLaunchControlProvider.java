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
import com.android.tradefed.build.DeviceFolderBuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.observatory.IDiscoverDependencies;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;
import com.google.android.tradefed.config.remote.AndroidBuildRemoteFileResolver;
import com.google.common.base.Strings;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * A {@link LaunchControlProvider} for {@link DeviceFolderBuildInfo}.
 *
 * <p>Retrieves both a CTS build and a device build from the Android build server.
 *
 * <p>The device build is retrieved first, and thus the build-flavor, etc arguments provided to this
 * class should match the device build you want to retrieve.
 *
 * <p>By default, this class will attempt to download a CTS build with same build-id and same branch
 * as device build. The cts* options may be used to retrieve a different CTS build.
 */
@OptionClass(alias = "cts-device-launch-control")
public class CtsDeviceLaunchControlProvider extends DeviceLaunchControlProvider
        implements IDiscoverDependencies {

    // note: --branch option is defined in super class
    @Option(name = "cts-branch", description = "the branch of CTS build. Defaults to --branch.")
    private String mCtsBranch = null;

    @Option(
            name = "cts-build-id",
            description = "the id of CTS build. Defaults to build id of device build.")
    private String mCtsBuildId = null;

    @Option(name = "cts-build-flavor", description = "the build flavor of CTS. Defaults to cts")
    private String mCtsBuildFlavor = "cts";

    @Option(
            name = "cts-package-name",
            description =
                    "the filename of CTS package to download. " + "Defaults to \"android-cts.zip\"")
    private String mCtsPackageName = "android-cts.zip";

    @Option(
            name = "cts-extra-artifact",
            description =
                    "the filename of CTS extra artefact to download, may be repearted for multiple"
                            + " fetches.")
    private List<String> mCtsExtra = new ArrayList<String>();

    @Option(
            name = "cts-extra-alt-path",
            description =
                    "Alternative location where to look for "
                            + "extra artifact if they are not found on the build server.")
    private File mExtraAltPath = null;

    @Option(
            name = "throw-if-extra-not-found",
            description =
                    "Throw an exception if one of the "
                            + "extra specified file by cts-extra-artifact is not found.")
    private boolean mThrowIfExtraNotFound = true;

    @Option(name = "cts-query-type", description = "the query type to use for CTS build.")
    private QueryType mCtsQueryType = QueryType.NOTIFY_TEST_BUILD;

    @Option(
            name = "enable-cts-direct-download",
            description = "Whether or not to enable the CTS part of the download.")
    private boolean mEnableCtsDirectDownload = true;

    @Option(
            name = "cts-use-partial-download",
            description =
                    "whether to use partial download to "
                            + "download only necessary test modules from the xts zip")
    private boolean mCtsUsePartialDownload = false;

    @Option(
            name = "enable-partial-download-default-rollout",
            description = "Whether to apply the default rollout of partial download.")
    private boolean mEnableDefaultRollout = true;

    @Option(
            name = "cts-force-partial-download-regex",
            description =
                    "A set of regex which represents a list of file that cts partial download"
                            + " always try to download.")
    private Set<String> mCtsForcePartialDownloadRegex = new HashSet<>();

    @Option(
            name = "download-cts-in-parallel",
            description = "Download CTS in parallel of device image.")
    private boolean mDownloadCtsParallel = true;

    @Option(
            name = "delay-download-in-sharding",
            description = "Delay the download of module until after sharding.")
    private boolean mDelayDownloadInSharding = false;

    @Option(
            name = "partial-download-default-rollout",
            description =
                    "List of package_name:branch combinations where partial download will be used.")
    private Set<String> mPartialDownloadDefaultRollout = new LinkedHashSet<>();

    @Option(
            name = "cts-force-test-discovery-metadata-mode",
            description =
                    "Force the test discovery to be in metadata mode.")
    private boolean mCtsForceTestDiscoveryMetadataMode = false;

    @Option(
            name = "partial-download-rollout-artifact",
            description =
                    "The main artifact name (e.g., android-cts.zip) currently being targeted for"
                        + " partial download rollout. This option is primarily to scope the effect"
                        + " of force-disable-partial-download.")
    private String mPartialDownloadRolloutArtifact = null;

    @Option(
            name = "force-disable-partial-download-experiment",
            description =
                    "If true, partial download experiment is disabled for the artifact specified in"
                            + " --partial-download-rollout-artifact on the current branch. Legacy"
                            + " rollout logic will be used instead. This is used for experimental"
                            + " control and quick mitigations.")
    private boolean mForceDisablePartialDownloadExperiment = false;

    @Option(
            name = "partial-download-disallow-list",
            description =
                    "A set of 'artifact:branch' pairs for which partial download should always be"
                            + " disabled. Example: 'android-cts.zip:git_main'")
    private Set<String> mPartialDownloadDisallowList = new HashSet<>();

    private CtsLaunchControlProvider mCtsLcProvider;
    private IFileDownloader mCtsDownloader;

    @Override
    public RemoteBuildInfo queryForBuild() throws BuildRetrievalError {
        // First query launch control to determine device build to test
        RemoteBuildInfo deviceBuild = super.queryForBuild();
        if (!mEnableCtsDirectDownload) {
            return deviceBuild;
        }
        if (deviceBuild != null) {
            // now query launch control again to get CTS build
            // TODO: this is a bit of a hack. consider moving this logic to launch control server
            mCtsLcProvider = createCtsLcProvider();
            mCtsLcProvider.setTestTag(getTestTag());
            mCtsLcProvider.setCtsForceTestDiscoveryMetadataMode(
                    mCtsForceTestDiscoveryMetadataMode);
            if (mCtsBranch == null) {
                // get CTS build from same branch as device build
                mCtsBranch = getBranch();
            }
            mCtsLcProvider.setBranch(mCtsBranch);

            if (mCtsBuildId == null && QueryType.NOTIFY_TEST_BUILD.equals(mCtsQueryType)) {
                // get same CTS build id as device build
                mCtsBuildId = deviceBuild.getBuildId();
            }
            mCtsLcProvider.setTestLogger(getTestLogger());
            mCtsLcProvider.setBuildId(mCtsBuildId);
            mCtsLcProvider.setQueryType(mCtsQueryType);
            mCtsLcProvider.setBuildFlavor(mCtsBuildFlavor);
            mCtsLcProvider.setCtsPackageName(mCtsPackageName);
            mCtsLcProvider.setCtsExtra(mCtsExtra);
            mCtsLcProvider.setCtsExtraAltPath(mExtraAltPath);
            mCtsLcProvider.setThrowIfExtraNotFound(mThrowIfExtraNotFound);
            mCtsLcProvider.setConfiguration(getConfiguration());
            mCtsLcProvider.setInvocationContext(getInvocationContext());
            mCtsLcProvider.setCtsUsePartialDownload(mCtsUsePartialDownload);
            mCtsLcProvider.setCtsForcePartialDownloadRegex(mCtsForcePartialDownloadRegex);
            mCtsLcProvider.setDefaultRollout(mEnableDefaultRollout);
            mCtsLcProvider.setDelayDownloadinSharding(mDelayDownloadInSharding);
            if (!mPartialDownloadDefaultRollout.isEmpty()) {
                mCtsLcProvider.setPartialDownloadDefaultRollout(mPartialDownloadDefaultRollout);
            }
            // Cas arguments
            mCtsLcProvider.setReportContentInformation(getReportContentInformation());
            mCtsLcProvider.setReportContentBasedRollout(getReportContentBasedRollout());
            // passing LC host params down
            mCtsLcProvider.setLcHostname(getLcHostname());
            mCtsLcProvider.setUseSsoClient(getUseSsoClient());
            mCtsLcProvider.setLcProtocol(getLcProtocol());
            mCtsLcProvider.setEnableResolveSymlinks(mEnableResolveSymlinks);
            mCtsLcProvider.setUseBuildApi(shouldUseBuildApi());
            mCtsLcProvider.setContentBasedReportingDefault(getContentBasedReportingDefault());
            mCtsLcProvider.setPartialDownloadRolloutArtifact(mPartialDownloadRolloutArtifact);
            mCtsLcProvider.setForceDisablePartialDownloadExperiment(
                    mForceDisablePartialDownloadExperiment);
            mCtsLcProvider.setPartialDownloadDisallowList(mPartialDownloadDisallowList);
            RemoteBuildInfo ctsBuild = queryCtsBuild(deviceBuild.getBuildId(), mCtsLcProvider);
            if (ctsBuild != null) {
                if (mCtsLcProvider.getForceCas()) {
                    forceUseCas(mCtsLcProvider.getForceCas());
                }
                mCtsDownloader = mCtsLcProvider.createLaunchControlDownloader(ctsBuild);
                // copy cts attributes to the device remote build
                deviceBuild.addAttribute(
                        BuildAttributeKey.CTS, ctsBuild.getAttribute(BuildAttributeKey.CTS));
                return deviceBuild;
            }
        }
        return null;
    }

    /**
     * Factory method for creating a {@link CtsLaunchControlProvider} to query/download CTS builds.
     *
     * <p>Exposed for unit testing.
     */
    CtsLaunchControlProvider createCtsLcProvider() {
        return new CtsLaunchControlProvider();
    }

    /**
     * Query launch control for a CTS build to test.
     *
     * @param deviceBuildId build id from the device build
     * @param ctsLcProvider a launch control provider for cts build
     * @return the {link RemoteBuildInfo} of the Cts build
     * @throws BuildRetrievalError
     */
    private RemoteBuildInfo queryCtsBuild(
            String deviceBuildId, CtsLaunchControlProvider ctsLcProvider)
            throws BuildRetrievalError {
        RemoteBuildInfo ctsBuild = null;
        try {
            ctsBuild = ctsLcProvider.queryForBuild();
        } finally {
            if (ctsBuild == null) {
                CLog.i("Failed to retrieve CTS build %s on branch %s", mCtsBuildId, mCtsBranch);
                // reset the device build
                resetTestBuild(deviceBuildId);
            }
        }
        return ctsBuild;
    }

    @Override
    public IBuildInfo downloadBuildFiles(
            RemoteBuildInfo remoteBuild,
            String testTargetName,
            String buildName,
            IFileDownloader downloader)
            throws BuildRetrievalError {
        DeviceFolderBuildInfo ctsAndDeviceBuild = null;
        IDeviceBuildInfo deviceBuild = null;
        if (getSkipDownloadKeys().contains(BuildAttributeKey.DEVICE_IMAGE)) {
            mDownloadCtsParallel = false;
            CLog.d("No device image download, skip parallel download");
        }

        if (shouldDownload(BuildAttributeKey.TESTS_ZIP)) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DOWNLOAD_BOTH_ZIPS_AND_TS, 1);
        }

        if (mDownloadCtsParallel) {
            CtsDownloadThread cThread =
                    new CtsDownloadThread(
                            Thread.currentThread().getThreadGroup(),
                            remoteBuild,
                            testTargetName,
                            buildName,
                            downloader);
            cThread.start();
            try {
                deviceBuild =
                        (IDeviceBuildInfo)
                                super.downloadBuildFiles(
                                        remoteBuild, testTargetName, buildName, downloader);
            } catch (BuildRetrievalError | RuntimeException e) {
                cThread.interrupt();
                if (cThread.output != null) {
                    cThread.output.cleanUp();
                }
                throw e;
            }
            try {
                cThread.join(20 * 1000 * 1000);
            } catch (InterruptedException e) {
                throw new BuildRetrievalError(e.getMessage(), e);
            }
            if (cThread.exception != null) {
                deviceBuild.cleanUp();
                if (cThread.exception instanceof BuildRetrievalError) {
                    throw (BuildRetrievalError) cThread.exception;
                }
                throw new HarnessRuntimeException(
                        cThread.exception.getMessage(),
                        cThread.exception,
                        InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
            }
            ctsAndDeviceBuild = cThread.output;
            ctsAndDeviceBuild.setDeviceBuild(deviceBuild);
            String buildChangeAttribute =
                    deviceBuild.getBuildAttributes().get("DEVICE_IMAGE_NOT_CHANGED");
            if (!Strings.isNullOrEmpty(buildChangeAttribute)) {
                ctsAndDeviceBuild.addBuildAttribute(
                        "DEVICE_IMAGE_NOT_CHANGED", buildChangeAttribute);
            }
            deviceBuild.unmountAll(); // No cleanUp() due to files sharing with ctsAndDeviceBuild.
            return ctsAndDeviceBuild;
        }

        if (deviceBuild != null) {
            deviceBuild.cleanUp();
        }
        deviceBuild =
                (IDeviceBuildInfo)
                        super.downloadBuildFiles(
                                remoteBuild, testTargetName, buildName, downloader);
        try {
            ctsAndDeviceBuild = downloadCts(remoteBuild, testTargetName, buildName, downloader);
        } catch (BuildRetrievalError e) {
            CLog.i("Failed to download CTS build %s on branch %s", mCtsBuildId, mCtsBranch);
            deviceBuild.cleanUp();
            throw e;
        }
        ctsAndDeviceBuild.setDeviceBuild(deviceBuild);
        String buildChangeAttribute =
                deviceBuild.getBuildAttributes().get("DEVICE_IMAGE_NOT_CHANGED");
        if (!Strings.isNullOrEmpty(buildChangeAttribute)) {
            ctsAndDeviceBuild.addBuildAttribute("DEVICE_IMAGE_NOT_CHANGED", buildChangeAttribute);
        }
        deviceBuild.unmountAll(); // No cleanUp() due to files sharing with ctsAndDeviceBuild.
        return ctsAndDeviceBuild;
    }

    private DeviceFolderBuildInfo downloadCts(
            RemoteBuildInfo remoteBuild,
            String testTargetName,
            String buildName,
            IFileDownloader downloader)
            throws BuildRetrievalError {
        DeviceFolderBuildInfo ctsAndDeviceBuild;
        if (mEnableCtsDirectDownload) {
            IFileDownloader dl = mCtsDownloader;
            if (dl == null) {
                dl = downloader;
            }
            IFolderBuildInfo ctsBuild =
                    (IFolderBuildInfo)
                            mCtsLcProvider.downloadBuildFiles(
                                    remoteBuild, testTargetName, buildName, dl);
            ctsAndDeviceBuild =
                    new DeviceFolderBuildInfo(
                            remoteBuild.getBuildId(), buildName, ctsBuild.shouldUseFuseZip());
            ctsAndDeviceBuild.setFolderBuild(ctsBuild);
            if (ctsBuild.getBuildAttributes().get(ITestSuite.SKIP_STAGING_ARTIFACTS) != null) {
                ctsAndDeviceBuild.addBuildAttribute(
                        ITestSuite.SKIP_STAGING_ARTIFACTS,
                        ctsBuild.getBuildAttributes().get(ITestSuite.SKIP_STAGING_ARTIFACTS));
            }
        } else {
            ctsAndDeviceBuild = new DeviceFolderBuildInfo(remoteBuild.getBuildId(), buildName);
        }
        ctsAndDeviceBuild.setTestTag(testTargetName);
        return ctsAndDeviceBuild;
    }

    /** {@inheritDoc} */
    @Override
    public void cleanUp(IBuildInfo info) {
        super.cleanUp(info);
        // Clean up extras if any
        if (mCtsLcProvider != null) {
            mCtsLcProvider.cleanExtra();
        }
    }

    private class CtsDownloadThread extends Thread {

        private final RemoteBuildInfo mRemoteBuild;
        private final String mTestTargetName;
        private final String mBuildName;
        private final IFileDownloader mDownloader;

        DeviceFolderBuildInfo output;
        Exception exception;

        public CtsDownloadThread(
                ThreadGroup group,
                RemoteBuildInfo remoteBuild,
                String testTargetName,
                String buildName,
                IFileDownloader downloader) {
            super(group, "CtsDownloadThread");
            setDaemon(true);
            mRemoteBuild = remoteBuild;
            mTestTargetName = testTargetName;
            mBuildName = buildName;
            mDownloader = downloader;
        }

        @Override
        public void run() {
            try {
                output = downloadCts(mRemoteBuild, mTestTargetName, mBuildName, mDownloader);
            } catch (BuildRetrievalError | RuntimeException e) {
                exception = e;
            }
        }
    }

    @Override
    public Set<String> reportTestZipFileFilter() {
        Set<String> filters = new HashSet<>();
        filters.addAll(super.reportTestZipFileFilter());
        filters.add(mCtsPackageName);
        if (mExtraAltPath != null) {
            String path = mExtraAltPath.getPath();
            Matcher m = AndroidBuildRemoteFileResolver.DOWNLOAD_PATTERN.matcher(path);
            if (!m.matches()) {
                return null;
            }
            String pattern = m.group(AndroidBuildRemoteFileResolver.BUILD_PATTERN_STRING);

            // Extract the file from the pattern.
            String fileRegex = pattern.split("\\?")[0];
            if (fileRegex.endsWith(".zip")) {
                filters.add(fileRegex);
            }
        }
        return filters;
    }
}
