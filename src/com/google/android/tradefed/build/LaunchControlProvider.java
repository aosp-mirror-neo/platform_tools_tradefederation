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

import static com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper.BUILD_ENV_INVOCATION_CONTEXT_KEY;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildInfoKey;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.FileDownloadCache;
import com.android.tradefed.build.FileDownloadCacheFactory;
import com.android.tradefed.build.FileDownloadCacheWrapper;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.content.ContentAnalysisContext;
import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionUpdateRule;
import com.android.tradefed.config.remote.ExtendedFile;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.error.IHarnessException;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.host.IHostOptions.PermitLimitType;
import com.android.tradefed.invoker.ExecutionFiles.FilesKey;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.CurrentInvocation.InvocationInfo;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.observatory.IDiscoverDependencies;
import com.android.tradefed.result.ITestLoggerReceiver;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.FlashingResourcesParser;
import com.android.tradefed.targetprep.IFlashingResourcesParser;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.CasUtil;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.TimeUtil;
import com.android.tradefed.util.ZipUtil2;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;
import com.android.tradefed.util.net.HttpHelper;
import com.android.tradefed.util.net.IHttpHelper;
import com.android.tradefed.util.net.IHttpHelper.DataSizeException;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;
import com.google.android.tradefed.build.RemoteBuildInfo.InvalidResponseException;
import com.google.android.tradefed.build.content.ContentBasedHelper;
import com.google.android.tradefed.config.remote.AndroidBuildRemoteFileResolver;
import com.google.android.tradefed.util.CasFileDownloader;
import com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper;
import com.google.api.client.util.Strings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * A {@link IBuildProvider} implementation that queries the Google launch control servers for a
 * build to test.
 *
 * <p>Subclassing is discouraged. Use extra-target-downloads and --additional-files-filter to
 * specify artifacts to download.
 *
 * <p>TODO: refactor out the launch control client and download logic, and merge it with the future
 * "One Common Device Flasher Library To Rule Them All" (tm)
 */
public class LaunchControlProvider
        implements IBuildProvider,
                IInvocationContextReceiver,
                IConfigurationReceiver,
                ITestLoggerReceiver,
                IDiscoverDependencies {

    protected static final String LC_BASE_URL = "%s://%s/buildbot-update";
    // the names of the Url parameters to provide to launch control
    protected static final String BUILD_ID_PARAM = "bid";
    protected static final String BUILD_NAME_PARAM = "id";
    protected static final String TEST_TAG_PARAM = "test_tag";
    protected static final String QUERY_TYPE_PARAM = "op";
    protected static final String MIN_BUILD_ID_PARAM = "min_build_id";
    protected static final String QUERY_WINDOW_PARAM = "query_window";

    private static final String PROTOCOL = "protocol";

    @Option(name = PROTOCOL, description = "protocol for launch control requests. (http/https)")
    private String mLcProtocol = "https";

    private static final String HOSTNAME = "hostname";

    @Option(name = HOSTNAME, description = "host name for launch control requests")
    private String mLcHostname = "atp-manager.googleplex.com";

    private static final String TEST_TAG_NAME = "test-tag";
    private String mTestTag = null;

    private static final String QUERY_TYPE_NAME = "query-type";

    @Option(
            name = QUERY_TYPE_NAME,
            description =
                    "type of launch control query to perform when "
                            + "requesting new builds to test.")
    private QueryType mQueryType = QueryType.LATEST_GREEN_BUILD;

    @Option(
            name = "query-window",
            description =
                    "How far back in time to go when querying builds. This looks at the build"
                        + " timestamp as specified by the Android Build API. The param can be"
                        + " specified as a signed sequence of decimal number and a unit suffix,"
                        + " such as 300ms or 2h45m. Valid time units are ns, us (or µs), ms, s, m,"
                        + " h. For example: if we specify 24h, we are only interested in builds"
                        + " that were built in the last 24 hours. This param is only valid when"
                        + " querying Launch Control Proxy and only for LATEST-WHITELISTED-CL and"
                        + " LATEST-GREEN-CL query types")
    private String mQueryWindow = null;

    private static final String BUILD_ID_NAME = "build-id";

    @Option(
            name = BUILD_ID_NAME,
            description = "Build id to query. Only used for " + "NOTIFY_TEST_BUILD query.")
    private String mBuildId = null;

    private static final String BUILD_ARCHIVE_PATH = "build-archive-path";

    @Option(name = BUILD_ARCHIVE_PATH, description = "MDB path where the build archive resides")
    private String mBuildArchivePath = "/namespace/android/builds";

    @Option(
            name = "min-build-id",
            description =
                    "Minimum build id to test. " + "Builds less than this value will be skipped.",
            updateRule = OptionUpdateRule.GREATEST)
    private Integer mMinBuildId = -1;

    private static final String BRANCH_NAME = "branch";

    @Option(
            name = BRANCH_NAME,
            description = "the build branch to test.",
            importance = Importance.IF_UNSET)
    private String mBranch = null;

    @Option(name = "build-os", description = "the OS platform the build is made on.")
    private String mOsPlatform = "linux";

    private static final String BUILD_FLAVOR_NAME = "build-flavor";

    @Option(name = BUILD_FLAVOR_NAME, description = "the build flavor e.g. passion-userdebug.")
    private String mBuildFlavor = null;

    private static final String ITERATIONS_NAME = "iterations";

    @Option(
            name = "test-iterations",
            description =
                    "the number of iterations to run for. Only available with the Launch Control"
                            + " Proxy. Deprecated as LCP is sunsetted.")
    @Deprecated
    private Integer mIterations = null;

    private static final String FALLBACK_NAME = "fallback";

    @Option(
            name = FALLBACK_NAME,
            description =
                    "whether or not we should fall back to the next available build, or just test"
                            + " the latest one. Only available with the Launch Control Proxy."
                            + " Deprecated as LCP is sunsetted.")
    @Deprecated
    private Boolean mFallback = null;

    @Option(
            name = "use-kerberos-with-bigstore",
            description =
                    "whether to use kerberos for the bigstore downloader.  Ignored unless bigstore"
                            + " downloader is enabled.")
    private boolean mUseKrbForBigstore = false;

    @Option(
            name = "query-timeout",
            description = "Maximum number of seconds to wait for a single LC query to return.")
    private int mQueryTimeoutSec = 180;

    @Option(
            name = "query-max-time",
            description =
                    "Maximum number of minutes to try to query LC. Note an escalting backoff is"
                            + " used.")
    private int mQueryRetryMin = 31;

    @Option(
            name = "use-sso-client",
            description = "whether or not we should query LC with " + "sso_client.")
    private boolean mUseSsoClient = true;

    @Option(
            name = "sso-client-path",
            description =
                    "The location of the sso_client binary, " + "default use the one in $PATH")
    private String mSsoBinary = "sso_client";

    @Option(
            name = "sso-client-cert",
            description = "The location of the sso_client cert to " + "use if any.")
    private String mSsoCert = null;

    @Option(name = "fetch-signed-build", description = "whether or not to fetch a signed build")
    private boolean mFetchSigned = false;

    @Option(
            name = "fetch-artifact-arg",
            description =
                    "Extra arguments to pass through to fetch_artifact script, which is a tool used"
                            + " for downloading build artifacts.")
    private List<String> mFetchArtifactArgs = new LinkedList<>();

    @Option(
            name = "download-build-key",
            description = "Key names of files to download from the build server. May be repeated.")
    private Collection<BuildAttributeKey> mDownloadKeys = new HashSet<BuildAttributeKey>();

    @Option(
            name = "additional-files-filter",
            description =
                    "Additional file(s) to retrieve. If set, files matching one or more of these"
                        + " filters will be retrieved. An exception will be thrown if no files are"
                        + " found matching a given pattern.")
    private Collection<String> mAdditionalFilesFilters = new ArrayList<String>();

    @Option(
            name = "download-timeout",
            description =
                    "Max timeout allowed for each artifact to be downloaded, "
                            + "defaults to 30 minutes. Supports abbreviated time notation"
                            + "such as 2h, 3m.",
            isTimeVal = true)
    private long mDownloadTimeout = 30 * 60 * 1000;

    @Option(
            name = "use-build-api",
            description = "Whether to use AndroidBuildDownloader or BigStoreFileDownloader.")
    private boolean mUseBuildApi = true;

    @Option(
            name = "download-exclusion-patterns",
            description =
                    "List of file patterns that will be excluded from candidate download list")
    private Set<String> mDownloadExclusionPatterns = new LinkedHashSet<>();

    /**
     * Special map of files meant for dynamic downloads: Files from this map will be place in the
     * BuildInfo object under the key name.
     */
    @Option(
            name = "extra-target-downloads",
            description =
                    "Extra files that will be added to the BuildInfo under the key of the map. "
                            + "For example: --extra-target-downloads key=gs://bucket/path/file")
    private MultiMap<String, File> mExtraTargetDownloads = new MultiMap<>();

    @Option(
            name = "test-config-only-zip",
            description =
                    "Zip file pattern containing only test config files. This enables "
                            + "test zip to be partially downloaded for necessary test modules.")
    private String mTestConfigOnlyZip = null;

    @Option(
            name = "secondary-parallel-download",
            description = "Enable parallel download on secondary (non-device images) files.")
    private boolean mSecondaryFileParallelDownload = false;

    @Option(
            name = "force-disable-cas",
            description = "Don't use cas for downloading test artifacts.")
    private boolean mForceDisableCas = false;

    @Option(
            name = "cas-auto-discovery",
            description = "Attempt to discover cas manifest automatically.")
    private boolean mCasAutoDiscovery = true;

    @Option(name = "cas-manifest-file", description = "Path to the CAS manifest file.")
    private File mCasManifestFile;

    @Option(
            name = "force-disable-cas-fuse-mount",
            description = "Keed chunks around after cas download for chunked artifacts.")
    private boolean mForceDisableCasFuseMount = false;

    @Deprecated
    @Option(
            name = "cas-keep-chunks",
            description =
                    "Keed chunks around after cas download for chunked artifacts, for Tradefed"
                            + " feature rollout only.")
    private boolean mCasKeepChunks = false;

    /** Deprecated. Use force-disable-cas-fuse-mount to disable cas fuse mount. */
    @Deprecated
    @Option(
            name = "cas-chunks-only",
            description =
                    "Download chunks only for chunked artifacts, for Tradefed feature rollout"
                            + " only.")
    private boolean mCasChunksOnly = true;

    @Option(
            name = "force-disable-cas-chunk-dir",
            description =
                    "Whether to disable cas-chunk-dir feature. Opt out for tests that are "
                            + "not compatible with the feature.")
    private boolean mForceDisableChunkingUncompressed = false;

    @Option(
            name = "enable-zip-partial-download-cache",
            description = "Enable caching for the zipped partial download.")
    private boolean mEnableZipPartialDownloadCache = true;

    @Option(
            name = "enable-content-based-reporting",
            description = "Enable the fetching and reporting of the content information.")
    private boolean mReportContentbasedInfo = false;

    @Option(
            name = "enable-content-based-reporting-rollout",
            description = "Enable the rollout of content based reporting")
    private boolean mReportContentbasedInfoRollout = true;

    @Option(name = "enable-resolve-sym-links", description = "Enable symlinks resolving")
    protected boolean mEnableResolveSymlinks = true;

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

    // Remote zip files to be downloaded partially based on test config.
    private Set<String> mRemoteZipFilters = new HashSet<>();
    private Set<String> mOptionalAdditionalFilesFilters = new HashSet<>();

    /** Build environment to download files from */
    private String mBuildEnvironment = "prod";

    private IConfiguration mConfiguration = null;
    private IInvocationContext mInvocationContext = null;
    private ITestLogger mLogger = null;

    private boolean mSkipCloudContent = false;

    @Option(
            name = "content-based-reporting-default",
            description = "A set of branches where content based reporting is enabled by default.")
    private Set<String> mContentBasedReportingDefault = new LinkedHashSet<>();

    /** Standard platforms */
    private static final Set<String> STANDARD_PLATFORMS =
            ImmutableSet.of("linux", "mac", "mac_arm64");

    @Override
    public void setTestLogger(ITestLogger testLogger) {
        mLogger = testLogger;
    }

    public ITestLogger getTestLogger() {
        return mLogger;
    }

    public void setAdditionalFilesFilters(Collection<String> additionalFilesFilters) {
        mAdditionalFilesFilters = additionalFilesFilters;
    }

    public void addFileFilter(String filter) {
        mAdditionalFilesFilters.add(filter);
    }

    public void addOptionalFileFilter(String optionalFilter) {
        mOptionalAdditionalFilesFilters.add(optionalFilter);
    }

    public Collection<String> getAdditionalFilesFilters() {
        return mAdditionalFilesFilters;
    }

    /** Set the Launch Control hostname to use in query */
    public void setLcHostname(String lcHostname) {
        this.mLcHostname = lcHostname;
    }

    /** Get the Launch Control hostname to use in query */
    public String getLcHostname() {
        return mLcHostname;
    }

    /**
     * Set the Launch Control protocol to use in query
     *
     * @param lcProtocol
     */
    public void setLcProtocol(String lcProtocol) {
        mLcProtocol = lcProtocol;
    }

    /** Get the Launch Control protocol to use in query */
    public String getLcProtocol() {
        return mLcProtocol;
    }

    public boolean shouldFetchSigned() {
        return mFetchSigned;
    }

    public void setFetchSigned(boolean fetchSigned) {
        mFetchSigned = fetchSigned;
    }

    /** Set if sso client should be used */
    public void setUseSsoClient(boolean useSsoClient) {
        mUseSsoClient = useSsoClient;
    }

    /** Get if sso client should be used */
    public boolean getUseSsoClient() {
        return mUseSsoClient;
    }

    /**
     * Set the path to the build artifacts archive
     *
     * @param buildArchivePath
     */
    public void setBuildArchivePath(String buildArchivePath) {
        this.mBuildArchivePath = buildArchivePath;
    }

    /** Get the path to the build artifacts archive */
    public String getBuildArchivePath() {
        return mBuildArchivePath;
    }

    /** Add a filter of a remote zip file that can be downloaded partially. */
    public void addRemoteZipFilter(String fileFilter) {
        mRemoteZipFilters.add(fileFilter);
    }

    /** Get a set of filters for zip files that can be downloaded partially. */
    public Set<String> getRemoteZipFilters() {
        return mRemoteZipFilters;
    }

    /** {@inheritDoc} */
    @Override
    public void setInvocationContext(IInvocationContext invocationContext) {
        mTestTag = invocationContext.getTestTag();
        List<String> buildEnvironments =
                invocationContext.getAttributes().get(BUILD_ENV_INVOCATION_CONTEXT_KEY);
        if (buildEnvironments != null && !buildEnvironments.isEmpty()) {
            mBuildEnvironment = buildEnvironments.get(0);
        }
        mInvocationContext = invocationContext;
    }

    public IInvocationContext getInvocationContext() {
        return mInvocationContext;
    }

    /** Set the test-tag to be used. */
    public void setTestTag(String tag) {
        mTestTag = tag;
    }

    /** Get the test tag to use in query */
    public String getTestTag() {
        return mTestTag;
    }

    /**
     * Set the build id to query
     *
     * @param buildId
     */
    public void setBuildId(String buildId) {
        mBuildId = buildId;
    }

    /** Get the build id to query */
    public String getBuildId() {
        return mBuildId;
    }

    /**
     * Set the branch to query
     *
     * @param branch
     */
    public void setBranch(String branch) {
        mBranch = branch;
    }

    /**
     * Get the currently specified branch to query
     *
     * @return the branch
     */
    public String getBranch() {
        return mBranch;
    }

    /**
     * Set the build-flavor to query
     *
     * @param flavor
     */
    public void setBuildFlavor(String flavor) {
        mBuildFlavor = flavor;
    }

    /** Get the build-flavor to query */
    public String getBuildFlavor() {
        return mBuildFlavor;
    }

    /**
     * Set the query type to be used
     *
     * @param queryType
     */
    public void setQueryType(QueryType queryType) {
        mQueryType = queryType;
    }

    /** Get the query type to be used */
    public QueryType getQueryType() {
        return mQueryType;
    }

    public String getBuildOs() {
        return mOsPlatform;
    }

    public void setBuildOs(String os) {
        mOsPlatform = os;
    }

    /** Set the min build id to use. */
    public void setMinBuildId(int minBuildId) {
        mMinBuildId = minBuildId;
    }

    /** Get the test only config zip. */
    public String getTestConfigOnlyZip() {
        return mTestConfigOnlyZip;
    }

    /** {@inheritDoc} */
    @Override
    public IBuildInfo getBuild() throws BuildRetrievalError {
        if (mBuildFlavor == null) {
            throw new BuildRetrievalError(
                    "Failed to specify build-flavor",
                    InfraErrorIdentifier.ARTIFACT_UNSUPPORTED_PATH);
        }
        RemoteBuildInfo remoteBuild = null;
        try {
            remoteBuild = getRemoteBuild();

            if (remoteBuild != null) {
                IBuildInfo build = fetchRemoteBuild(remoteBuild);
                // Copy the dynamically obtained files to the build info
                List<Entry<String, File>> entries =
                        new ArrayList<>(getExtraDownloadMap().entries());
                // Copy in reverse orders so that last specified on command line is first set on
                // BuildInfo (last-win behavior).
                Collections.reverse(entries);
                handleExtraTargetDownloads(build, entries);
                if (build.getFile(BuildInfoFileKey.TESTDIR_IMAGE) != null) {
                    if (CurrentInvocation.getInvocationFiles() != null) {
                        CurrentInvocation.getInvocationFiles()
                                .put(
                                        FilesKey.TESTS_DIRECTORY,
                                        build.getFile(BuildInfoFileKey.TESTDIR_IMAGE));
                    }
                }
                if (shouldReportContentInformation() && !mSkipCloudContent) {
                    ContentAnalysisContext context =
                            ContentBasedHelper.obtainCloudParameters(
                                    getBranch(),
                                    getConfiguration().getDeviceConfig().get(0).getDeviceOptions());
                    if (context != null) {
                        getConfiguration()
                                .getSkipManager()
                                .setImageAnalysis(mInvocationContext.getDevices().get(0), context);
                    }
                }
                if (!getExtraDownloadMap().isEmpty()) {
                    boolean hasExtendedFile =
                            getExtraDownloadMap().values().stream()
                                    .anyMatch(f -> f instanceof ExtendedFile);
                    if (hasExtendedFile) {
                        boolean shouldRunContentAnalysis =
                                getExtraDownloadMap().values().parallelStream()
                                        .anyMatch(
                                                f ->
                                                        (f instanceof ExtendedFile
                                                                && shouldReportContentInformation(
                                                                        ((ExtendedFile) f)
                                                                                .getBranch())));
                        if (shouldRunContentAnalysis) {
                            CLog.d(
                                    "Content analysis for extra target downloads. Count: %s",
                                    getExtraDownloadMap().size());
                            ContentBasedHelper.analyzeExtraDownloads(
                                    getConfiguration().getSkipManager(),
                                    mInvocationContext.getDevices().get(0),
                                    getExtraDownloadMap());
                        } else {
                            ContentAnalysisContext context =
                                    new ContentAnalysisContext(
                                            "extra-target-downloads",
                                            null,
                                            AnalysisMethod.DEVICE_IMAGE);
                            context.invalidateAnalysis(
                                    "No content information for the extra target downloads");
                            getConfiguration()
                                    .getSkipManager()
                                    .setImageAnalysis(
                                            mInvocationContext.getDevices().get(0), context);
                        }
                    }
                }
                return build;
            }
            return null;
        } catch (BuildRetrievalError e) {
            // Add additional attributes describing the type of build
            IBuildInfo buildInfo = e.getBuildInfo();

            if (IBuildInfo.UNKNOWN_BUILD_ID.equals(buildInfo.getBuildId())) {
                if (mBuildId != null) {
                    buildInfo.setBuildId(mBuildId);
                } else if (remoteBuild != null
                        && !RemoteBuildInfo.UNKNOWN_BUILD_ID.equals(remoteBuild.getBuildId())) {
                    buildInfo.setBuildId(remoteBuild.getBuildId());
                }
            }
            buildInfo.setBuildFlavor(mBuildFlavor);
            buildInfo.setBuildBranch(mBranch);
            insertTargetAttribute(buildInfo);
            throw e;
        }
    }

    /** Handles files dynamically download to backfill their metadata */
    public static void handleExtraTargetDownloads(
            IBuildInfo build, List<Entry<String, File>> entries) throws BuildRetrievalError {
        IFlashingResourcesParser flashingResourcesParser = null;
        String deviceImageFlavor = null;
        for (Entry<String, File> entry : entries) {
            if (!entry.getValue().exists()) {
                if (entry.getValue() instanceof ExtendedFile
                        && ((ExtendedFile) entry.getValue()).isDownloadingInParallel()) {
                    CLog.d(
                            "File '%s' for extra-target-downloads key '%s' is"
                                    + " downloading in parallel and doesn't exist yet.",
                            entry.getValue(), entry.getKey());
                } else {
                    CLog.d(
                            "File '%s' for extra-target-downloads key '%s' doesn't exist",
                            entry.getValue(), entry.getKey());
                    continue;
                }
            }
            String buildId = "downloaded";
            if (entry.getValue() instanceof ExtendedFile) {
                ExtendedFile extFile = ((ExtendedFile) entry.getValue());
                String bid = extFile.getBuildId();
                if (!Strings.isNullOrEmpty(bid)) {
                    buildId = bid;
                }
                // Special handling for flashing-related files
                // TODO(b/137204136): Refactor this in a routine that would download and
                // handle all files required for flashing.
                if (BuildInfoKey.BuildInfoFileKey.DEVICE_IMAGE
                        .getFileKey()
                        .equals(entry.getKey())) {
                    if (!extFile.isDoneDownloadingInParallel()) {
                        AndroidBuildDownloader fileDownloader = new AndroidBuildDownloader();
                        fileDownloader.setBuildEnvironment("prod");
                        File androidInfo =
                                fileDownloader.downloadFile(
                                        String.format(
                                                "%s-linux-%s/%s/android-info.txt",
                                                extFile.getBranch(),
                                                extFile.getBuildTarget(),
                                                extFile.getBuildId()));
                        try (BufferedReader reader =
                                new BufferedReader(
                                        new InputStreamReader(new FileInputStream(androidInfo)))) {
                            flashingResourcesParser = new FlashingResourcesParser(reader);
                        } catch (IOException e) {
                            throw new BuildRetrievalError(
                                    "Failed to get android-info.txt",
                                    e,
                                    InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
                        } finally {
                            FileUtil.deleteFile(androidInfo);
                        }
                    } else {
                        try {
                            flashingResourcesParser = new FlashingResourcesParser(extFile);
                        } catch (TargetSetupError e) {
                            throw new BuildRetrievalError(
                                    "Failed to get image info from android-info.txt",
                                    e,
                                    InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
                        }
                    }
                    deviceImageFlavor = extFile.getBuildTarget();
                }
                if (BuildInfoKey.BuildInfoFileKey.BOOTLOADER_IMAGE
                        .getFileKey()
                        .equals(entry.getKey())) {
                    if (flashingResourcesParser != null) {
                        buildId = flashingResourcesParser.getRequiredBootloaderVersion();
                        CLog.d(
                                "Bootloader from file: %s with version: %s",
                                entry.getValue(), buildId);
                    }
                }
                if (BuildInfoKey.BuildInfoFileKey.BASEBAND_IMAGE
                        .getFileKey()
                        .equals(entry.getKey())) {
                    if (flashingResourcesParser != null) {
                        buildId = flashingResourcesParser.getRequiredBasebandVersion();
                    }
                }
            }
            if (build instanceof IDeviceBuildInfo && deviceImageFlavor != null) {
                ((IDeviceBuildInfo) build).setDeviceBuildFlavor(deviceImageFlavor);
            }
            CLog.d(
                    "extra-target-downloads. key='%s', value='%s'",
                    entry.getKey(), entry.getValue());
            build.setFile(entry.getKey(), entry.getValue(), buildId);
            // Track the files as key
            if (CurrentInvocation.getInvocationFiles() != null) {
                CurrentInvocation.getInvocationFiles().put(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public void cleanUp(IBuildInfo info) {
        info.cleanUp();
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

    /**
     * Get the launch control response without downloading all the files.
     *
     * <p>Made public so specializations outside of this package can use it directly. Normally
     * {@link #getBuild()} should be used instead.
     *
     * @return the {@link RemoteBuildInfo} response from launch control
     * @throws BuildRetrievalError if build info failed to be retrieved due to an unexpected error
     */
    public RemoteBuildInfo getRemoteBuild() throws BuildRetrievalError {
        final RemoteBuildInfo remoteBuild = queryForBuild();
        if (remoteBuild != null) {
            try {
                if (mBuildId == null && Integer.valueOf(remoteBuild.getBuildId()) < mMinBuildId) {
                    // Assume that minBuildId is a valid build, and reject any build lower than that
                    // except that when an explicit build id is specified
                    return null;
                }
            } catch (NumberFormatException e) {
                CLog.w(
                        "Remote build %s was not an integer, so not bounding it with min build %d",
                        remoteBuild.getBuildId(), mMinBuildId);
            }
            if (shouldFetchSigned()) {
                convertBuildToSigned(remoteBuild);
            }
            return remoteBuild;
        }
        return null;
    }

    /**
     * Modify parameters in this {@link RemoteBuildInfo} to describe a signed build.
     *
     * @param remoteBuild the {@link RemoteBuildInfo} to modify
     * @throws BuildRetrievalError
     */
    protected void convertBuildToSigned(RemoteBuildInfo remoteBuild) throws BuildRetrievalError {
        throw new UnsupportedOperationException(
                "LaunchControlProvider does not implement convertBuildToSigned");
    }

    protected void replaceParamWithSigned(RemoteBuildInfo buildInfo, BuildAttributeKey key)
            throws BuildRetrievalError {
        buildInfo.addAttribute(key, createSignedParameter(buildInfo.getAttribute(key)));
    }

    /**
     * Modify a single parameter to represent a signed parameter If the parameter is already in the
     * signed format, it is returned unchanged.
     *
     * @param param the initial parameter (can be unsigned or already signed)
     * @return the signed parameter
     * @throws BuildRetrievalError if the parameter is malformed and cannot be processed
     */
    protected String createSignedParameter(String param) throws BuildRetrievalError {
        String delim = "/";
        String[] paramSegments = param.split(delim);

        // Check if the parameter is <build name>/<build id>/signed/signed-<filename>
        if (paramSegments.length == 4
                && "signed".equals(paramSegments[2])
                && paramSegments[3].startsWith("signed-")) {
            // Parameter is already signed, return it as is.
            CLog.i("Parameter '%s' is already signed.", param);
            return param;
        }

        if (paramSegments.length == 3) {
            // This is an unsigned parameter, convert it to signed format.
            return ArrayUtil.join(
                    delim,
                    paramSegments[0], // <build name>
                    paramSegments[1], // <build id>
                    "signed", // the "signed" directory
                    "signed-" + paramSegments[2]); // signed-<filename>
        }

        // If the parameter doesn't match either expected format, it's malformed.
        CLog.e("Unexpected or malformatted build parameter: %s", param);
        throw new BuildRetrievalError("Malformatted build parameter for signing: " + param);
    }

    /**
     * Convert the launch control response into a local {@link IBuildInfo} containing all the
     * necessary files.
     *
     * <p>Made public so specializations outside of this package can use it directly. Normally
     * {@link #getBuild()} should be used instead.
     *
     * @param remoteBuild the launch control server response
     * @return the {@link IBuildInfo} populated with local versions of build files
     * @throws BuildRetrievalError if build files could not be downloaded
     */
    public IBuildInfo fetchRemoteBuild(RemoteBuildInfo remoteBuild) throws BuildRetrievalError {
        IBuildInfo localBuild = null;
        IFileDownloader downloader = null;
        try {
            downloader = createLaunchControlDownloader(remoteBuild);
            long startTime = System.currentTimeMillis();
            try (CloseableTraceScope ignore = new CloseableTraceScope("wait_for_download_permit")) {
                getHostOptions().takePermit(PermitLimitType.CONCURRENT_DOWNLOAD);
            }
            long latency = System.currentTimeMillis() - startTime;
            CLog.d("Got download permit after %s", TimeUtil.formatElapsedTime(latency));
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DOWNLOAD_PERMIT_LATENCY, latency);

            localBuild = downloadBuildFiles(remoteBuild, mTestTag, createBuildName(), downloader);
            localBuild.setBuildFlavor(mBuildFlavor);
            localBuild.setBuildBranch(mBranch);
            String buildAlias = remoteBuild.getAttribute(BuildAttributeKey.BUILD_ALIAS);
            if (buildAlias != null && !buildAlias.trim().isEmpty()) {
                // convert to upper case since that's the typical representation
                localBuild.addBuildAttribute("build_alias", buildAlias.toUpperCase());
            }
            String buildType = remoteBuild.getAttribute(BuildAttributeKey.BUILD_TYPE);
            if (buildType != null && !buildType.trim().isEmpty()) {
                localBuild.addBuildAttribute("build_type", buildType);
            }
            String buildAttemptId = remoteBuild.getAttribute(BuildAttributeKey.BUILD_ATTEMPT_ID);
            if (buildAttemptId != null && !buildAttemptId.trim().isEmpty()) {
                localBuild.addBuildAttribute("build_attempt_id", buildAttemptId.toLowerCase());
            }
            downloadAdditionalFiles(remoteBuild, downloader, localBuild);
            insertTargetAttribute(localBuild);
            return localBuild;
        } catch (BuildRetrievalError e) {
            // downloadBuildFiles failed, build cannot be tested
            resetTestBuild(remoteBuild.getBuildId());
            // If some basic info are missing use the known local build for reporting
            if (localBuild != null
                    && IBuildInfo.UNKNOWN_BUILD_ID.equals(e.getBuildInfo().getBuildId())) {
                e.setBuildInfo(localBuild);
            }
            throw e;
        } catch (RuntimeException e) {
            // downloadBuildFiles failed, build cannot be tested
            resetTestBuild(remoteBuild.getBuildId());
            if (localBuild == null) {
                throw e;
            } else {
                BuildRetrievalError bre = new BuildRetrievalError(e.getMessage(), e, localBuild);
                throw bre;
            }
        } finally {
            getHostOptions().returnPermit(PermitLimitType.CONCURRENT_DOWNLOAD);
        }
    }

    private void insertTargetAttribute(IBuildInfo localBuild) {
        // Insert build target into build attribute. Build Target is used by the new
        // Android Build API. (this can be different from the build flavor).
        // This is to handle special cases like fastbuild targets.
        String buildTarget = mBuildFlavor;
        if (!STANDARD_PLATFORMS.contains(mOsPlatform)) {
            buildTarget += "_" + mOsPlatform;
        }
        localBuild.addBuildAttribute("build_target", buildTarget);
    }

    /**
     * Performs a {@link QueryType#RESET_TEST_BUILD} query to requests launch control to remove
     * configured build from 'tested builds' set.
     *
     * <p>Has limited error handling and retry mechanisms Deprecated as LCProxy has sunsetted.
     */
    @Deprecated
    public void resetTestBuild() {
        if (mBuildId == null) {
            throw new IllegalArgumentException(String.format("Missing %s option", BUILD_ID_NAME));
        }
        resetTestBuild(mBuildId);
    }

    /**
     * Performs a {@link QueryType#RESET_TEST_BUILD} query to requests launch control to remove
     * given build from 'tested builds' set.
     *
     * <p>Has limited error handling and retry mechanisms Deprecated as LCProxy is sunsetted.
     *
     * @param buildId the build id to reset
     */
    @Deprecated
    public void resetTestBuild(String buildId) {
        if (mUseBuildApi) {
            CLog.w("resetTestBuild not supported when using build API, ignored.");
            return;
        }
        CLog.i("Resetting build %s", buildId);
        final MultiMap<String, String> params = buildParamMap(QueryType.RESET_TEST_BUILD, buildId);
        try {
            IHttpHelper helper = createHttpHelper();
            helper.doGetIgnore(helper.buildUrl(getUrl(), params));
        } catch (IOException e) {
            CLog.e("Reset test build failed for %s", buildId);
            CLog.e(e);
        }
    }

    @Deprecated
    public void decrementTestBuild(String buildId) {
        if (mUseBuildApi) {
            CLog.w("decrementTestBuild not supported when using build API, ignored.");
            return;
        }
        CLog.i("Decrementing build %s", buildId);
        MultiMap<String, String> params = buildParamMap(QueryType.DECREMENT_TEST_COUNT, buildId);
        try {
            IHttpHelper helper = createHttpHelper();
            helper.doGetIgnore(helper.buildUrl(getUrl(), params));
        } catch (IOException e) {
            CLog.e("Decrement test build failed for %s", buildId);
            CLog.e(e);
        }
    }

    /**
     * Factory method for creating a {@link HttpHelper}.
     *
     * <p>Exposed so it can be mocked for unit testing.
     */
    protected IHttpHelper createHttpHelper() {
        IHttpHelper helper = null;
        if (mUseSsoClient) {
            helper = new SsoClientHttpHelper(mSsoBinary, mSsoCert);

        } else {
            helper = new HttpHelper();
        }
        helper.setOpTimeout(mQueryTimeoutSec * 1000);
        helper.setMaxTime(mQueryRetryMin * 60 * 1000);
        return helper;
    }

    /**
     * Creates the map of request parameters to provide to launch control.
     *
     * @return a {@link Map} of key-value parameter pairs
     * @throws IllegalArgumentException if any of the required parameter values have not been
     *     provided, or the type of query provided is not recognized.
     */
    private MultiMap<String, String> buildParamMap() {
        return buildParamMap(mQueryType, mBuildId);
    }

    /**
     * Creates the map of request parameters to provide to launch control.
     *
     * @param queryEnum the {@link QueryType} to include
     * @param buildId the optional build id. If <code>null</code> will be excluded.
     * @return a {@link Map} of key-value parameter pairs
     * @throws IllegalArgumentException if any of the required parameter values have not been
     *     provided,
     */
    private MultiMap<String, String> buildParamMap(QueryType queryEnum, String buildId) {
        if (mTestTag == null) {
            throw createMissingOptionException(TEST_TAG_NAME);
        }
        if (mBranch == null) {
            throw createMissingOptionException(BRANCH_NAME);
        }
        if (mBuildFlavor == null) {
            throw createMissingOptionException(BUILD_FLAVOR_NAME);
        }
        MultiMap<String, String> paramMap = new MultiMap<String, String>();
        paramMap.put(QUERY_TYPE_PARAM, queryEnum.getRemoteValue());
        paramMap.put(TEST_TAG_PARAM, mTestTag);
        paramMap.put(BUILD_NAME_PARAM, createBuildName());
        paramMap.put(MIN_BUILD_ID_PARAM, mMinBuildId.toString());
        if (buildId != null) {
            paramMap.put(BUILD_ID_PARAM, buildId);
        }
        if (mIterations != null && mIterations > 1) {
            String iterations = mIterations.toString();
            paramMap.put(ITERATIONS_NAME, iterations);
        }
        if (mFallback != null) {
            paramMap.put(FALLBACK_NAME, String.valueOf(mFallback));
        }
        if (mQueryWindow != null) {
            paramMap.put(QUERY_WINDOW_PARAM, mQueryWindow);
        }
        return paramMap;
    }

    /**
     * Construct the name of the build as defined by launch control.
     *
     * @return {@link String} name of build
     */
    private String createBuildName() {
        return String.format("%s-%s-%s", mBranch, mOsPlatform, mBuildFlavor);
    }

    private HarnessRuntimeException createMissingOptionException(String optionName) {
        return new HarnessRuntimeException(
                String.format("Missing required option %s", optionName),
                InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
    }

    /**
     * Perform the launch control query, retrying multiple times in case of error.
     *
     * @return the {@link RemoteBuildInfo} retrieved from launch control
     */
    protected RemoteBuildInfo queryForBuild() throws BuildRetrievalError {
        // if build id is specified, do a NOTIFY_TEST_BUILD query
        if (mBuildId != null) {
            mQueryType = QueryType.NOTIFY_TEST_BUILD;
        }
        if (mUseBuildApi) {
            if (QueryType.LATEST_GREEN_BUILD.equals(mQueryType)) {
                CLog.i(
                        "Overriding query type from LATEST_GREEN_BUILD to QUERY_LATEST_BUILD "
                                + "due to build API being used.");
                mQueryType = QueryType.QUERY_LATEST_BUILD;
            }
            return queryForBuildWithBuildApi();
        } else {
            IHttpHelper helper = createHttpHelper();
            final MultiMap<String, String> params = buildParamMap();
            return queryForBuild(helper, getUrl(), params);
        }
    }

    protected void performValidityCheckForBuildApi() {
        try (CloseableTraceScope ignored =
                new CloseableTraceScope("performValidityCheckForBuildApi")) {
            // performs sanity check on whether the query can be made with build API
            if (mBuildFlavor == null) {
                throw createMissingOptionException(BUILD_FLAVOR_NAME);
            }
            if (mBuildId == null && mBranch == null) {
                throw new HarnessRuntimeException(
                        "When using build API, must specify a build id or branch.",
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            // ignored checks on query type: this concept does not really apply anymore check for
            // unsupported params
            if (mIterations != null || mFallback != null || mQueryWindow != null) {
                throw new HarnessRuntimeException(
                        "These parameters are not supported "
                                + "when using build API: iteration, fallback, query-window",
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            if (!QueryType.NOTIFY_TEST_BUILD.equals(mQueryType)
                    && !QueryType.QUERY_LATEST_BUILD.equals(mQueryType)) {
                throw new HarnessRuntimeException(
                        String.format(
                                "When using build API, only query types supported are: %s, %s",
                                QueryType.QUERY_LATEST_BUILD, QueryType.NOTIFY_TEST_BUILD),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
        } catch (Throwable t) {
            CLog.e(
                    "Current build query parameters: branch=%s, build-flavor=%s, build-id=%s, "
                            + "iterations=%d, fallback=%s, query-type=%s",
                    mBranch, mBuildFlavor, mBuildId, mIterations, mFallback, mQueryType);
            throw t;
        }
    }

    /**
     * Performs the build query against build API.
     *
     * <p>Note: the input params are implied by various class scoped variables
     *
     * @return The {@link RemoteBuildInfo} parsed from the response.
     * @throws BuildRetrievalError
     */
    protected RemoteBuildInfo queryForBuildWithBuildApi() throws BuildRetrievalError {
        performValidityCheckForBuildApi();
        return performBuildApiQuery();
    }

    protected Set<String> getGlobalDownloadExclusionPatterns() {
        return AndroidBuildAPIHelper.getAndroidBuildAPIOptions().getDownloadExclusionPatterns();
    }

    protected RemoteBuildInfo performBuildApiQuery() throws BuildRetrievalError {
        mDownloadExclusionPatterns.addAll(getGlobalDownloadExclusionPatterns());
        AndroidBuildArtifactHelper helper = getBuildArtifactHelper();
        if (mBuildId == null) {
            return helper.queryForLatestBuild(mBranch, mBuildFlavor, mOsPlatform);
        } else {
            return helper.queryForBuildById(
                    mBranch, mBuildId, mBuildFlavor, mOsPlatform, null, mDownloadExclusionPatterns);
        }
    }

    protected AndroidBuildArtifactHelper getBuildArtifactHelper() throws BuildRetrievalError {
        return new AndroidBuildArtifactHelper(mBuildEnvironment);
    }

    /**
     * Perform the launch control query, retrying multiple times in case of error.
     *
     * @param params parameters for launch control
     * @return the {@link RemoteBuildInfo} retrieved from launch control
     */
    protected RemoteBuildInfo queryForBuild(MultiMap<String, String> params)
            throws BuildRetrievalError {
        IHttpHelper helper = createHttpHelper();
        return queryForBuild(helper, getUrl(), params);
    }

    /**
     * Perform the launch control query, retrying multiple times in case of error.
     *
     * @param helper the {@link HttpHelper} to use to make query.
     * @param lcRequestUrl the complete url with parameter string used to retrieve build.
     * @return the {@link RemoteBuildInfo}
     */
    private RemoteBuildInfo queryForBuild(
            IHttpHelper helper, String lcRequestUrl, MultiMap<String, String> params)
            throws BuildRetrievalError {
        if (mUseBuildApi) {
            // sanity check
            throw new IllegalStateException(
                    "BUG: should not hit this code path when using " + "build API");
        }
        CLog.d("Starting launch control query %s", lcRequestUrl);
        try {
            String response = helper.doGetWithRetry(helper.buildUrl(lcRequestUrl, params));
            CLog.i("launch control query response: " + response);
            return RemoteBuildInfo.parseRemoteBuildInfo(response);
        } catch (IOException e) {
            CLog.i("IOException %s when contacting launch control", e.getMessage());
            throw new BuildRetrievalError(
                    "Failed to query build", e, InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
        } catch (DataSizeException e) {
            CLog.i("Unexpected oversized response when contacting launch control");
            throw new BuildRetrievalError(
                    "Failed to query build", e, InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
        } catch (InvalidResponseException e) {
            CLog.i("Invalid response when contacting launch control");
            throw new BuildRetrievalError(
                    "Failed to query build", e, InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
        } catch (RuntimeException e) {
            throw new BuildRetrievalError(
                    "Failed to query build", e, InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
        }
    }

    /**
     * Get {@link IRunUtil} to use.
     *
     * <p>Exposed so unit tests can mock.
     */
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /**
     * Download the necessary file keys as configured in --download-build-key from the
     * <var>remoteBuild</var>, and store them in a {@link IBuildInfo}.
     *
     * @param remoteBuild the launch control server response
     * @param testTargetName the test target name
     * @param buildName the name of the build, as defined by launch control
     * @param downloader the {@link IFileDownloader} to use to download build files
     * @return the {@link IBuildInfo} populated with local versions of build files
     * @throws BuildRetrievalError if build files could not be downloaded
     */
    protected IBuildInfo downloadBuildFiles(
            RemoteBuildInfo remoteBuild,
            String testTargetName,
            String buildName,
            IFileDownloader downloader)
            throws BuildRetrievalError {
        String buildId = remoteBuild.getBuildId();
        IBuildInfo localBuild = new BuildInfo(buildId, buildName);
        localBuild.setBuildFlavor(getBuildFlavor());
        localBuild.setBuildBranch(getBranch());
        localBuild.setTestTag(testTargetName);
        for (BuildAttributeKey downloadKey : mDownloadKeys) {
            localBuild.setFile(
                    downloadKey.name(),
                    downloader.downloadFile(remoteBuild.getAttribute(downloadKey)),
                    buildId);
        }
        return localBuild;
    }

    protected void downloadAdditionalFiles(
            RemoteBuildInfo remoteBuild, IFileDownloader downloader, IBuildInfo localBuild)
            throws BuildRetrievalError {
        // download any additional files; expect comma-separated file paths.
        final String filesPathCSVString = remoteBuild.getAttribute(BuildAttributeKey.FILES);
        if (filesPathCSVString == null) {
            return;
        }
        String[] filePaths;
        if (filesPathCSVString.isEmpty()) {
            filePaths = new String[] {};
        } else {
            filePaths = filesPathCSVString.split(",");
        }
        List<DownloadFileDescriptor> toBeDownloaded = new ArrayList<>();
        Set<String> fileFilters = new LinkedHashSet<String>(mAdditionalFilesFilters);
        fileFilters.addAll(mOptionalAdditionalFilesFilters);
        for (String pattern : fileFilters) {
            boolean foundMatch = false;
            for (String filePath : filePaths) {
                if (filePath.matches(pattern)) {
                    toBeDownloaded.add(
                            new DownloadFileDescriptor(
                                    filePath,
                                    new File(filePath).getName(),
                                    true,
                                    remoteBuild.getBuildId(),
                                    false));
                    foundMatch = true;
                }
            }
            if (!foundMatch) {
                if (mOptionalAdditionalFilesFilters.contains(pattern)) {
                    CLog.w(
                            "Could not find file matching optional pattern '%s' in %s",
                            pattern, filesPathCSVString);
                } else {
                    throw new BuildRetrievalError(
                            String.format(
                                    "Could not find file matching pattern '%s' in %s",
                                    pattern, filesPathCSVString),
                            null,
                            InfraErrorIdentifier.ARTIFACT_NOT_FOUND,
                            localBuild);
                }
            }
        }
        if (!mSecondaryFileParallelDownload) {
            for (DownloadFileDescriptor download : toBeDownloaded) {
                String label = download.mKeyInBuildInfo;
                CLog.d("label for %s is %s", download.mDownloadPath, label);
                localBuild.setFile(
                        label, downloader.downloadFile(download.mDownloadPath), download.mVersion);
            }
        } else {
            List<Entry<DownloadFileDescriptor, File>> downloaded =
                    downloadFilesNonBlocking(downloader, toBeDownloaded);
            for (Entry<DownloadFileDescriptor, File> entry : downloaded) {
                String label = entry.getKey().mKeyInBuildInfo;
                CLog.d("label for %s is %s", entry.getKey().mDownloadPath, label);
                localBuild.setFile(label, entry.getValue(), entry.getKey().mVersion);
            }
        }
        for (String pattern : mRemoteZipFilters) {
            boolean foundMatch = false;
            for (String filePath : filePaths) {
                if (filePath.matches(pattern)) {
                    CLog.d("Delay downloading remote zip file: %s", filePath);
                    File remoteFile = new File(LCUtil.createResolvableFilePath(filePath));
                    localBuild.setFile(
                            IBuildInfo.REMOTE_FILE_PREFIX + remoteFile.toString(),
                            remoteFile,
                            IBuildInfo.REMOTE_FILE_VERSION);
                    foundMatch = true;
                }
            }
            if (!foundMatch) {
                throw new BuildRetrievalError(
                        String.format("Could not find file matching pattern '%s'", pattern),
                        null,
                        InfraErrorIdentifier.ARTIFACT_NOT_FOUND,
                        localBuild);
            }
        }
    }

    /** Factory method for creating a downloader to use for launch control files. */
    public IFileDownloader createLaunchControlDownloader(RemoteBuildInfo remoteBuild) {
        if (mBuildArchivePath == null || mBuildArchivePath.isEmpty()) {
            throw createMissingOptionException(BUILD_ARCHIVE_PATH);
        }
        IFileDownloader downloader;
        if (mUseBuildApi) {
            AndroidBuildDownloader fileDownloader = new AndroidBuildDownloader();
            fileDownloader.enablePartialDownloadCache(mEnableZipPartialDownloadCache);
            fileDownloader.setResolveSymlinks(mEnableResolveSymlinks);
            fileDownloader.setBuildEnvironment(mBuildEnvironment);
            downloader =
                    new FileDownloadCacheWrapper(
                            getHostOptions().getDownloadCacheDir(), fileDownloader);
        } else {
            throw new HarnessRuntimeException(
                    "Only use-build-api is supported.",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        // Force to disable CAS
        if (mForceDisableCas || CasFileDownloader.getCasOptions().isForceDisabled()) {
            return downloader;
        }

        boolean autoDiscovery = (mCasAutoDiscovery && !mForceDisableCas);
        if (remoteBuild != null
                && DeviceLaunchControlProvider.getPathForPattern(remoteBuild, ".*cas_digests.json")
                        == null) {
            CLog.d("Disabling cas auto-discovery. No digest in: %s", remoteBuild);
            autoDiscovery = false;
        }
        // Always go through the cas downloader when enabled even if we end
        // up falling back to standard download.
        CasFileDownloader cas =
                new CasFileDownloader(
                        downloader,
                        mCasManifestFile,
                        autoDiscovery,
                        true,
                        !mForceDisableChunkingUncompressed);
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
        return cas;
    }

    /**
     * Remove the file from the cache in case it is corrupted.
     *
     * @param remoteFilePath Same remote file as specified in {@link
     *     IFileDownloader#downloadFile(String)}.
     */
    protected void deleteCacheEntry(String remoteFilePath) {
        FileDownloadCache cache =
                FileDownloadCacheFactory.getInstance()
                        .getCache(getHostOptions().getDownloadCacheDir());
        cache.deleteCacheEntry(remoteFilePath);
    }

    /** The URL of the build server to talk to. Exposed for mocking from unit tests. */
    String getUrl() {
        if (mLcHostname == null || mLcHostname.isEmpty()) {
            throw createMissingOptionException(HOSTNAME);
        }
        return String.format(LC_BASE_URL, mLcProtocol, mLcHostname);
    }

    /** Return if the build provider should use Android Build API */
    public boolean shouldUseBuildApi() {
        return mUseBuildApi;
    }

    /** Sets the value to control if the build provider should use Android Build API */
    public void setUseBuildApi(boolean useBuildApi) {
        mUseBuildApi = useBuildApi;
    }

    public void setPartialDownloadForZip(boolean enableZipPartialDownload) {
        mEnableZipPartialDownloadCache = enableZipPartialDownload;
    }

    public void setEnableResolveSymlinks(boolean enableResolveSymlinks) {
        mEnableResolveSymlinks = enableResolveSymlinks;
    }

    /**
     * Extract the given zip file to a local dir.
     *
     * @param zippedFile The File to unzip
     * @param prefix the prefix of the temporary directory where to unzip
     * @return the {@link File} referencing the zip output.
     * @throws IOException
     */
    File extractZip(File zippedFile, String prefix) throws IOException {
        try (CloseableTraceScope ignored =
                new CloseableTraceScope("unzip_" + zippedFile.getName())) {
            long startTime = System.currentTimeMillis();
            CLog.d("Start unziping %s.", zippedFile);
            // Extract inside the workfolder so everything is in one place.
            File extracted = FileUtil.createTempDir(prefix, getWorkFolder());
            try (ZipFile zip = new ZipFile(zippedFile)) {
                ZipUtil2.extractZip(zip, extracted);
            } catch (IOException e) {
                FileUtil.recursiveDelete(extracted);
                throw e;
            }
            long elapsedTime = System.currentTimeMillis() - startTime;
            CLog.d(
                    "Done unzipping %s in %s [zip file size: %s]",
                    zippedFile, TimeUtil.formatElapsedTime(elapsedTime), zippedFile.length());
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.UNZIP_TESTS_DIR_TIME, elapsedTime);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.UNZIP_TESTS_DIR_COUNT, 1);
            return extracted;
        }
    }

    /** Returns the current invocation workfolder. */
    protected final File getWorkFolder() {
        File workfolder = CurrentInvocation.getInfo(InvocationInfo.WORK_FOLDER);
        if (workfolder == null || !workfolder.exists()) {
            CLog.w("Workfolder isn't set");
            return null;
        }
        return workfolder;
    }

    /** Returns the map of extra downloaded targets. */
    protected MultiMap<String, File> getExtraDownloadMap() {
        return mExtraTargetDownloads;
    }

    public void clearExtraDownloads() {
        mExtraTargetDownloads.clear();
    }

    /** Whether parallel download is enabled or not. */
    public boolean isSecondaryParallelDownload() {
        return mSecondaryFileParallelDownload;
    }

    public void setParallelSecondaryDownload(boolean parallel) {
        mSecondaryFileParallelDownload = parallel;
    }

    /** Download the set of file paths and enforce them if they are mandatory. */
    protected final List<Entry<DownloadFileDescriptor, File>> downloadFilesNonBlocking(
            IFileDownloader downloader, List<DownloadFileDescriptor> toBeDownloaded)
            throws BuildRetrievalError {
        if (toBeDownloaded.isEmpty()) {
            return new ArrayList<>();
        }
        ParallelDeviceExecutor<Entry<DownloadFileDescriptor, File>> executor =
                new ParallelDeviceExecutor<>(Math.max(toBeDownloaded.size(), 10));
        List<Callable<Entry<DownloadFileDescriptor, File>>> callableTasks = new ArrayList<>();
        for (DownloadFileDescriptor download : toBeDownloaded) {
            Callable<Entry<DownloadFileDescriptor, File>> callableTask =
                    () -> {
                        try (CloseableTraceScope ignored =
                                new CloseableTraceScope("download " + download.mDownloadPath)) {
                            File f = null;
                            if (download.mHandleDirectory) {
                                f =
                                        handleDirectoryDownload(
                                                downloader.downloadFile(download.mDownloadPath));
                            } else {
                                f = downloader.downloadFile(download.mDownloadPath);
                            }
                            return new SimpleEntry<>(download, f);
                        } catch (BuildRetrievalError e) {
                            if (!download.mMandatory) {
                                CLog.w(
                                        "Ignoring %s missing download. Non-mandatory file.",
                                        download.mDownloadPath);
                                return new SimpleEntry<>(download, null);
                            }
                            CLog.e(e);
                            throw e;
                        }
                    };
            callableTasks.add(callableTask);
        }
        List<Entry<DownloadFileDescriptor, File>> results =
                executor.invokeAll(callableTasks, 120L, TimeUnit.MINUTES);
        if (executor.hasErrors()) {
            List<Throwable> errors = executor.getErrors();
            for (Throwable error : errors) {
                CLog.e(error);
                if (error instanceof BuildRetrievalError) {
                    throw (BuildRetrievalError) error;
                }
                if (error instanceof IHarnessException) {
                    throw new HarnessRuntimeException(
                            error.getMessage(), (IHarnessException) error);
                }
                throw new RuntimeException(error);
            }
        }
        if (results.removeIf(e -> e.getValue() == null)) {
            CLog.w("Some non-mandatory files didn't get downloaded.");
        }
        return results;
    }

    /** {@inheritDoc} */
    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    /** Return the injected {@link IConfiguration} in use. */
    public IConfiguration getConfiguration() {
        return mConfiguration;
    }

    public class DownloadFileDescriptor {
        public final String mDownloadPath;
        public final String mKeyInBuildInfo;
        public final boolean mMandatory;
        public final String mVersion;
        public final boolean mHandleDirectory;

        public DownloadFileDescriptor(
                String downloadPath,
                String key,
                boolean mandatory,
                String version,
                boolean directoryHandle) {
            mDownloadPath = downloadPath;
            mKeyInBuildInfo = key;
            mMandatory = mandatory;
            mVersion = version;
            mHandleDirectory = directoryHandle;
        }
    }

    public void forceUseCas(boolean useCas) {
        useCas = useCas && !mForceDisableCas;
        mCasAutoDiscovery = useCas;
    }

    protected final File handleDirectoryDownload(File downloadedFile) {
        // TODO: Clean up when CAS download supports files better
        if (downloadedFile.isDirectory()) {
            File[] children = downloadedFile.listFiles();
            if (children.length == 1) {
                File child = children[0];
                if (child.isDirectory() && child.getName().equals(CasUtil.CHUNKS_DIR_NAME)) {
                    CLog.w("Downloaded a fast-cdc chunked directory.");
                    return downloadedFile;
                }
                return child;
            }
            CLog.w(
                    "Downloaded a directory with more than one child: %s",
                    Arrays.toString(children));
        }
        return downloadedFile;
    }

    protected boolean useCasDefaultRollout() {
        return !mForceDisableCas;
    }

    public boolean isChunkingUncompressedDisabled() {
        return mForceDisableChunkingUncompressed;
    }

    public boolean shouldReportContentInformation() {
        return shouldReportContentInformation(mBranch);
    }

    private boolean shouldReportContentInformation(String branch) {
        if (mReportContentbasedInfo) {
            return true;
        }
        if (mReportContentbasedInfoRollout && mContentBasedReportingDefault.contains(branch)) {
            CLog.d("Using default rollout of enable-content-based-reporting for %s", branch);
            return true;
        }
        return false;
    }

    public void setReportContentInformation(boolean reportContentInfo) {
        mReportContentbasedInfo = reportContentInfo;
    }

    public boolean getReportContentInformation() {
        return mReportContentbasedInfo;
    }

    public void setReportContentBasedRollout(boolean reportContentBasedRollout) {
        mReportContentbasedInfoRollout = reportContentBasedRollout;
    }

    public boolean getReportContentBasedRollout() {
        return mReportContentbasedInfoRollout;
    }

    public void setSkipCloudContent(boolean skipCloudContent) {
        mSkipCloudContent = skipCloudContent;
    }

    @Override
    public Set<String> reportTestZipFileFilter() {
        Set<String> filters = new HashSet<>();
        // Report test config zip if it is set.
        if (mTestConfigOnlyZip != null) {
            filters.add(mTestConfigOnlyZip);
        }

        // Report zip filters from additional files.
        for (String filter : getAdditionalFilesFilters()) {
            if (filter != null && filter.endsWith(".zip")) {
                filters.add(filter);
            }
        }

        // Report zip filters from extra downloads.
        for (File filePath : getExtraDownloadMap().values()) {
            // Extract the file pattern string from the path.
            String path = filePath.getPath();
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

        // Report additional file filters.
        filters.addAll(getAdditionalFilesFilters());
        return filters;
    }

    public Set<String> getContentBasedReportingDefault() {
        return mContentBasedReportingDefault;
    }

    public void setContentBasedReportingDefault(Set<String> contentBasedReportingDefault) {
        mContentBasedReportingDefault = contentBasedReportingDefault;
    }
}
