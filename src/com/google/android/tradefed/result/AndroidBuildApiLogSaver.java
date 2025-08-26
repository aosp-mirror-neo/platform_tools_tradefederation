/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.tradefed.result;

import static com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper.BUILD_ENV_INVOCATION_CONTEXT_KEY;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.LogFileSaver;
import com.android.tradefed.sandbox.TradefedSandbox;
import com.android.tradefed.testtype.SubprocessTfLauncher;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunInterruptedException;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;

import com.google.android.tradefed.build.AbLocalBuildProvider;
import com.google.android.tradefed.build.RemoteBuildInfo;
import com.google.android.tradefed.result.teststorage.ObjectUpdater;
import com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.androidbuildinternal.Androidbuildinternal;
import com.google.api.services.androidbuildinternal.Androidbuildinternal.Testartifact;
import com.google.api.services.androidbuildinternal.model.BuildArtifactMetadata;
import com.google.api.services.androidbuildinternal.model.TestResult;
import com.google.api.services.androidbuildinternal.v3.model.Build;
import com.google.api.services.androidbuildinternal.v3.model.TestArtifactListResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.net.UrlEscapers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** A class that saves log files to the Android Build API. */
@OptionClass(alias = "android-build-api-log-saver")
public class AndroidBuildApiLogSaver implements ILogSaver {
    private static final String ANDROID_BUILD_API_PATHSEP = "/";
    private static final String BUILD_ATTEMPT_ID = "build_attempt_id";
    private static final String DEFAULT_BUILD_ATTEMPT_ID = "0";
    private static final String BUILD_TARGET = "build_target";
    private static final String TEST_RESULT_ID = "test_result_id";
    private static final int DEFAULT_MAX_UPLOAD_ATTEMPTS = 3;
    private static final int INIT_ATTEMPTS_WAIT_TIME = 500; // ms
    private static final long UPLOAD_THREAD_JOIN_TIMEOUT_MS = 10 * 60 * 1000;
    static final String URL_TEMPLATE =
            "https://android-build.googleplex.com/builds/%s/%s/%s/%s/tests/%s/";
    private static final String INVOCATION_ID_KEY = "invocation_id";
    private static final String WORK_UNIT_ID_KEY = "work_unit_id";
    // Key that determines if we are using legacy V2 api or only v3 api for logs
    private static final String ARTIFACT_UPLOAD_KEY = "use_artifact_v3_api";

    // Some test invocation (special subprocess invocation) do not have full build infos.
    // Without full build infos, uploading to Android Build Api may fail. Should use stub build
    // infos in this case.
    static final String DEFAULT_STUB_BUILD_ID = "3136476";
    static final String DEFAULT_STUB_BUILD_TARGET = "build";
    static final String DEFAULT_STUB_BUILD_BRANCH = "ub-treehugger-prod";
    static final String DEFAULT_STUB_BUILD_ATTEMPT_ID = "latest";

    @Option(
            name = "compress-files",
            description = "whether to compress files which are not already compressed")
    private boolean mCompressFiles = true;

    @Option(
            name = "log-root-path",
            description =
                    "root local path to hold logfiles during the invocation.  Files will be moved"
                            + " from here to android build api after the invocation completes.")
    private File mLogRootPath =
            new File(System.getProperty("java.io.tmpdir"), "stage-android-build-api");

    @Option(
            name = "remove-staged-files",
            description =
                    "Whether to remove staged log files after "
                            + "they were successfully exported to Android Build API.")
    private boolean mRemoveStagedFiles = true;

    @Option(
            name = "max-upload-attempts",
            description = "Max attempts to upload files to the Android Build API.")
    private int mMaxUploadAttempts = DEFAULT_MAX_UPLOAD_ATTEMPTS;

    @Option(name = "use-stub-build", description = "Report the test result to a stub build or not.")
    private boolean mUseStubBuild = false;

    @Option(name = "stub-build-id", description = "Use this build id if use stub build is enabled.")
    private String mStubBuildId = DEFAULT_STUB_BUILD_ID;

    @Option(
            name = "stub-build-target",
            description = "Use this build target if use stub build is enabled.")
    private String mStubBuildTarget = DEFAULT_STUB_BUILD_TARGET;

    @Option(
            name = "stub-build-branch",
            description = "Use this build branch if use stub build is enabled.")
    private String mStubBuildBranch = DEFAULT_STUB_BUILD_BRANCH;

    @Option(
            name = "stub-build-attempt-id",
            description = "Use this build attempt id if use stub build is enabled.")
    private String mStubBuildAttemptId = DEFAULT_STUB_BUILD_ATTEMPT_ID;

    @Option(
            name = "periodic-upload",
            description =
                    "Periodically upload a couple of files to avoid one large upload at the end.")
    private boolean mPeriodicUpload = true;

    /**
     * @deprecated remove when cleaned
     */
    @Deprecated
    @Option(
            name = "periodic-upload-file-count",
            description = "Max number of files to upload at each periodic upload.")
    private int mPeriodicUploadCount = 20;

    @Option(
            name = "periodic-upload-interval",
            isTimeVal = true,
            description = "Interval to which we run the periodic upload of files.")
    private long mPeriodicUploadFreq = 2 * 60 * 1000;

    @Option(name = "parallel-upload", description = "Whether or not to upload on path in parallel.")
    private boolean mParallelUpload = true;

    /** The base path used for showing in Android Build Api. */
    private String mRemotePath = null;

    /** Generated local staging directory for log files */
    private File mLogStagingDir = null;

    /** a {link {@link LogFileSaver} to save the log locally. */
    private LogFileSaver mLogFileSaver = null;

    /**
     * A counter to control access to methods which modify this class's directories. Acting as a
     * non-blocking reentrant lock, this int blocks access to sharded child invocations from
     * attempting to create or delete directories.
     */
    private int mShardingLock = 0;

    /** Thread in charge of uploading files. */
    private UploadThread mUploadThread = null;

    /** Track if the object has reach the end of its lifecycle */
    private boolean mLifecycleDone = false;

    private Androidbuildinternal mClient;
    private com.google.api.services.androidbuildinternal.v3.Androidbuildinternal mClientV3;
    private boolean mClientInitialized;
    private IBuildInfo mBuildInfo;
    private String mBuildEnvironment = "prod";
    private TestResultIdentity mTestResultIdentity;

    private File mHostLogFile = null;

    private String mInvocationId = "";
    private String mWorkUnitId = "";
    private boolean mUpdateMetadata = true;
    private Map<String, LogDataType> mFilePathToLogDataTypeMap = new HashMap<>();
    private boolean mUseLegacyMigration;
    private IInvocationContext mInvocationContext;

    /** Stores all the log files that have been already handled for upload to Android Build API. */
    private Set<String> mUploadedLogs = new LinkedHashSet<>();

    /**
     * This is a class for full test result identify. It include the build info for the test result
     * id and the test result id.
     */
    static class TestResultIdentity {
        public final Long mTestResultId;
        public final String mTestResultBuildId;
        public final String mTestResultBuildTarget;
        public final String mTestResultBuildAttemptId;
        public final RemoteBuildInfo.BuildType mTestResultBuildType;

        public TestResultIdentity(
                Long testResultId,
                String buildId,
                String buildTarget,
                String attemptId,
                RemoteBuildInfo.BuildType buildType) {
            mTestResultId = testResultId;
            mTestResultBuildId = buildId;
            mTestResultBuildTarget = buildTarget;
            mTestResultBuildAttemptId = attemptId;
            mTestResultBuildType = buildType;
        }

        @Override
        public String toString() {
            return String.format(
                    "%d %s %s", mTestResultId, mTestResultBuildTarget, mTestResultBuildId);
        }
    }

    @Override
    public void init(IInvocationContext context) {
        // buildinfo doesn't exist yet at this point, but LogFileSaver only really need some
        // metadata
        BuildInfo fakeBuildMetadata = new BuildInfo();
        fakeBuildMetadata.setTestTag(context.getTestTag());
        String buildId = context.getAttribute("test_result.build_id");
        if (Strings.isNullOrEmpty(buildId)) {
            buildId = "stub";
        }
        String flavor = context.getAttribute("test_result.build_target");
        if (Strings.isNullOrEmpty(flavor)) {
            buildId = "stub";
        }
        fakeBuildMetadata.setBuildId(buildId);
        fakeBuildMetadata.setBuildFlavor(flavor);
        initLogSaver(fakeBuildMetadata);
        if (mTestResultIdentity == null) {
            try {
                mTestResultIdentity = fetchTestResultId(context, mUseStubBuild);
                // Propagate the test_result_id for log saving
                if (mTestResultIdentity != null
                        && !context.getAttributes().containsKey(TEST_RESULT_ID)) {
                    context.addInvocationAttribute(
                            TEST_RESULT_ID, Long.toString(mTestResultIdentity.mTestResultId));
                }
            } catch (RuntimeException e) {
                CLog.e("Failed to init the test result id:");
                CLog.e(e);
                return;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void invocationStarted(IInvocationContext context) {
        try (CloseableTraceScope start =
                new CloseableTraceScope("ab-log-uploader:invocationStart")) {
            List<String> buildEnvironments =
                    context.getAttributes().get(BUILD_ENV_INVOCATION_CONTEXT_KEY);
            if (buildEnvironments != null && !buildEnvironments.isEmpty()) {
                mBuildEnvironment = buildEnvironments.get(0);
            }

            String subprocess = context.getAttribute(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
            if (!Strings.isNullOrEmpty(subprocess)) {
                // Don't update metadata in subprocess, let parent do it.
                mUpdateMetadata = false;
            }

            mInvocationContext = context;
            mUseLegacyMigration = !"false".equals(context.getAttribute(ARTIFACT_UPLOAD_KEY));

            IBuildInfo build = context.getBuildInfos().get(0);
            // Limit build creation to local auth for now
            if (System.getenv("LOCAL_AUTH") != null) {
                build = build.clone();
                try {
                    Build localbuild =
                            AbLocalBuildProvider.createLocalBuild(
                                    context, build.getBuildBranch(), build.getBuildFlavor());
                    build.setBuildId(localbuild.getBuildId());
                    CLog.d("Local build %s was created to store results.", build);
                    CLog.d("Original build info: %s", context.getBuildInfos().get(0));
                    context.addDeviceBuildInfo(AbLocalBuildProvider.LOCAL_BUILD, build);
                } catch (IOException | GeneralSecurityException e) {
                    CLog.e(e);
                }
            }

            setBuildInfo(build);
            initLogSaver(getBuildInfo());
            if (mTestResultIdentity == null) {
                try {
                    mTestResultIdentity = fetchTestResultId(context, mUseStubBuild);
                    // Propagate the test_result_id for log saving
                    if (mTestResultIdentity != null
                            && !context.getAttributes().containsKey(TEST_RESULT_ID)) {
                        context.addInvocationAttribute(
                                TEST_RESULT_ID, Long.toString(mTestResultIdentity.mTestResultId));
                    }
                } catch (RuntimeException e) {
                    CLog.e("Failed to init the test result id:");
                    CLog.e(e);
                    return;
                }
            }

            if (mPeriodicUpload) {
                mUploadThread = new UploadThread(mPeriodicUploadFreq);
                mUploadThread.start();
            }
        }
    }

    /**
     * Initialize the log saver.
     *
     * @param buildInfo
     */
    private void initLogSaver(IBuildInfo buildInfo) {
        if (mLogFileSaver != null) {
            return;
        }
        synchronized (this) {
            if (mShardingLock == 0) {
                mLogFileSaver = new LogFileSaver(buildInfo, getLogRootPath());
                mRemotePath =
                        generateAndroidBuildApiPath(mLogFileSaver.getInvocationLogPathSegments());
                setLogStagingDir(mLogFileSaver.getFileDir());
            }
            mShardingLock++;
        }
    }

    /**
     * Fetch test result id by following priority order:
     *
     * <p>1. If explicitly set use stub build, then create a test result id for the stub build. 2.
     * Try to get the test result id and related build info from invocation context. 3. If there is
     * no build info in invocation context, use the local primary (the first) build. 4. If the test
     * result id and local primary build doesn't match, create a new test result id for local
     * primary build. 5. If failed to create test result id for local build, create test result id
     * for stub build.
     *
     * <p>1, 3, 4, 5 should never happen. They are just backups.
     */
    TestResultIdentity fetchTestResultId(IInvocationContext context, boolean useStubBuild) {
        TestResultIdentity testResultIdentity = null;
        try {
            if (useStubBuild) {
                return fetchTestResultIdFromStubBuild();
            } else if (context != null) {
                testResultIdentity = fetchTestResultIdFromContext(context);
            }
            if (testResultIdentity == null) {
                testResultIdentity = fetchTestResultIdFromLocalBuild();
            }
            if (testResultIdentity == null) {
                testResultIdentity = fetchTestResultIdFromStubBuild();
            }
            return testResultIdentity;
        } catch (GoogleJsonResponseException e) {
            CLog.w(
                    "Error fetching result: %s. This is not critical but log uploading will be"
                            + " disabled.",
                    e.getMessage());
        }
        return null;
    }

    /** Fetch test result and related build info with stub build. */
    TestResultIdentity fetchTestResultIdFromStubBuild() throws GoogleJsonResponseException {
        Long testResultId = createTestResult(mStubBuildId, mStubBuildTarget, mStubBuildAttemptId);
        if (testResultId != null) {
            return new TestResultIdentity(
                    testResultId,
                    mStubBuildId,
                    mStubBuildTarget,
                    mStubBuildAttemptId,
                    RemoteBuildInfo.parseBuildType(mStubBuildId));
        }
        return null;
    }

    /**
     * Fetch test result from context. If there is no test result build info, use the local build
     * and check if the test result belong to the build or not. If not, return null
     *
     * @param context
     * @return test result identify
     */
    TestResultIdentity fetchTestResultIdFromContext(IInvocationContext context) {
        CLog.i("Fetching test result from context");
        String testResultIdStr = context.getAttribute(TEST_RESULT_ID);
        if (Strings.isNullOrEmpty(testResultIdStr)) {
            return null;
        }
        Long testResultId = null;
        try {
            testResultId = Long.parseLong(testResultIdStr);
        } catch (NumberFormatException e) {
            CLog.e("%s is not a valid test result id, will create a new one", testResultIdStr);
            CLog.e(e);
            return null;
        }
        String buildId = context.getAttribute("test_result.build_id");
        String buildTarget = context.getAttribute("test_result.build_target");
        String attemptId = context.getAttribute("test_result.build_attempt_id");
        RemoteBuildInfo.BuildType buildType = RemoteBuildInfo.parseBuildType(buildId);

        if (Strings.isNullOrEmpty(buildId)
                || Strings.isNullOrEmpty(buildTarget)
                || Strings.isNullOrEmpty(attemptId)) {
            CLog.i("There is no test result build info in context, use local build info.");
            buildId = getBuildInfo().getBuildId();
            buildTarget = getBuildTarget();
            attemptId = getBuildAttemptId();
            buildType = getBuildType();
        }
        TestResultIdentity testResultIdentity =
                new TestResultIdentity(testResultId, buildId, buildTarget, attemptId, buildType);
        if (checkTestResultExist(testResultIdentity)) {
            return testResultIdentity;
        }
        return null;
    }

    /**
     * Generate test result from local build.
     *
     * @return test result identify
     */
    TestResultIdentity fetchTestResultIdFromLocalBuild() throws GoogleJsonResponseException {
        // In case of early init, build info is not set yet.
        if (getBuildInfo() == null) {
            return null;
        }
        String buildId = getBuildInfo().getBuildId();
        String buildTarget = getBuildTarget();
        String attemptId = getBuildAttemptId();
        RemoteBuildInfo.BuildType buildType = getBuildType();
        CLog.i(
                "Creating new test result from local build (%s %s %s %s)",
                buildId, buildTarget, attemptId, buildType);
        Long testResultId = createTestResult(buildId, buildTarget, attemptId);
        if (testResultId == null) {
            return null;
        }
        return new TestResultIdentity(testResultId, buildId, buildTarget, attemptId, buildType);
    }

    /**
     * Check a build exists in Android Build Api or not.
     *
     * @param buildId
     * @param buildTarget
     * @return true if the build exist, otherwise false.
     */
    boolean checkBuildExist(String buildId, String buildTarget) {
        if (getClient() == null) {
            CLog.w("There is no Android Build Api client.");
            return false;
        }
        try {
            getClient().build().get(buildId, buildTarget).execute();
            return true;
        } catch (IOException e) {
            CLog.e("Build %s %s doesn't exist.", buildId, buildTarget);
            return false;
        }
    }

    /**
     * Check a test result exists or not.
     *
     * @param testResultIdentity testResultIdentity
     * @return true if the test result exists, otherwise false.
     */
    boolean checkTestResultExist(TestResultIdentity testResultIdentity) {
        if (getClient() == null) {
            CLog.w("There is no Android Build Api client.");
            return false;
        }
        try {
            getClient()
                    .testresult()
                    .get(
                            testResultIdentity.mTestResultBuildId,
                            testResultIdentity.mTestResultBuildTarget,
                            testResultIdentity.mTestResultBuildAttemptId,
                            testResultIdentity.mTestResultId)
                    .execute();
            return true;
        } catch (IOException e) {
            CLog.w("There is no test result id %s", testResultIdentity);
            return false;
        }
    }

    /**
     * Create a test result in Android Build Api.
     *
     * @param buildId
     * @param buildTarget
     * @param buildAttemptId
     * @return test result id
     */
    Long createTestResult(String buildId, String buildTarget, String buildAttemptId)
            throws GoogleJsonResponseException {
        if (getClient() == null) {
            CLog.w("There is no Android Build Api client.");
            return null;
        }

        CLog.i(
                "Creating new test result from build (%s %s %s)",
                buildId, buildTarget, buildAttemptId);
        TestResult testResult = new TestResult();
        if (getBuildInfo() == null) {
            testResult.setTestTag("stub");
        } else {
            testResult.setTestTag(getBuildInfo().getTestTag());
        }
        try {
            testResult =
                    getClient()
                            .testresult()
                            .insert(buildId, buildTarget, buildAttemptId, testResult)
                            .execute();
            return testResult.getId();
        } catch (GoogleJsonResponseException gre) {
            throw gre;
        } catch (IOException e) {
            CLog.e("Failed to create test result, will not upload logs to Android Build Api.");
            CLog.e(e);
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void invocationEnded(long elapsedTime) {
        if (!shouldUpload()) {
            return;
        }
        mLifecycleDone = true;

        try (CloseableTraceScope end = new CloseableTraceScope("ab-log-uploader:invocationEnded")) {
            // Make sure we stop the periodic upload thread first, then proceed to finishing
            // uploading
            // everything.
            waitForUploadThread();

            List<String> stagedPaths = listStagedFiles(getLogStagingDir());
            if (stagedPaths == null) {
                // It is uncommon that we don't even have a host log to save, so log something
                CLog.w("No log files were saved; skipping export to Android Build Api.");
                return;
            }

            CLog.d("Start uploadOnPath");
            if (mParallelUpload) {
                int poolSize = Math.min(stagedPaths.size(), 50);
                if (poolSize != 0) {
                    ParallelDeviceExecutor<Boolean> executor =
                            new ParallelDeviceExecutor<>(poolSize);
                    List<Callable<Boolean>> callableTasks = new ArrayList<>();
                    for (String stagedPath : stagedPaths) {
                        Callable<Boolean> callableTask =
                                () -> {
                                    uploadOnPath(stagedPath);
                                    return true;
                                };
                        callableTasks.add(callableTask);
                        // mark this log file as handled for upload, so that it's not considered for
                        // upload during next rounds of periodic upload.
                        mUploadedLogs.add(stagedPath);
                    }
                    executor.invokeAll(callableTasks, 0L, TimeUnit.MINUTES);
                }
            } else {
                for (String stagedPath : stagedPaths) {
                    // mark this log file as handled for upload, so that it's not considered for
                    // upload during next rounds of periodic upload.
                    mUploadedLogs.add(stagedPath);
                    uploadOnPath(stagedPath);
                }
            }
            CLog.d("Start updateArtifactMetadata");
            updateArtifactMetadata();

            synchronized (this) {
                if (--mShardingLock == 0) {
                    List<String> remainingPaths = listStagedFiles(getLogStagingDir());
                    if (mRemoveStagedFiles
                            && remainingPaths != null
                            && remainingPaths.size() == 0) {
                        FileUtil.recursiveDelete(getLogStagingDir());
                    }
                }
                if (mShardingLock < 0) {
                    CLog.w(
                            "Sharding lock exited more times than entered, possible "
                                    + "unbalanced invocationStarted/Ended calls");
                }
            }
            CLog.d("Done uploading with AndroidBuildApiLogSaver");
        }
    }

    /** Routine to upload a given stagedPath */
    private void uploadOnPath(String stagedPath) {
        try (CloseableTraceScope ignored =
                new CloseableTraceScope("ab-upload:" + new File(stagedPath).getName())) {
            uploadFileToAndroidBuildApiWithMultiAttempts(stagedPath);
            if (mRemoveStagedFiles) {
                FileUtil.deleteFile(new File(stagedPath));
            }
        } catch (IOException e) {
            CLog.e("Failed to upload %s to Android Build Api.", stagedPath);
            String message = e.getMessage();
            if (!Strings.isNullOrEmpty(message)) {
                // Outputting the whole message is too verbose.
                // The first line have a brief error message.
                CLog.e(message.split("\n", 2)[0]);
            }
            // In case of exception, if periodic upload is enabled, instead of re-trying again at
            // the next round, delete right away to match non-periodic behavior.
            // TODO: Consider leaving a trace/snippet somewhere for debugging
            if (mRemoveStagedFiles && mPeriodicUpload) {
                FileUtil.deleteFile(new File(stagedPath));
            }
        }
    }

    /**
     * Check if the log saver should upload the log files. Used for testing.
     *
     * @return should upload
     */
    boolean shouldUpload() {
        if (getTestResultIdentity() == null) {
            CLog.w(
                    "There is no test result id for this invocation, can not upload tests files "
                            + "to Android Build Api. The logs are saved under %s.",
                    getLogStagingDir());
            return false;
        }

        if (getClient() == null) {
            CLog.w(
                    "No Android Build Api client set, can not upload tests files to "
                            + "Android Build Api. The logs are saved under %s.",
                    getLogStagingDir());
            return false;
        }
        return true;
    }

    /**
     * Upload staged file to Android Build Api with multiple attempts.
     *
     * @param filePath the staged file path
     * @throws IOException
     */
    void uploadFileToAndroidBuildApiWithMultiAttempts(String filePath) throws IOException {
        // TODO(b/143365209): Remove this method once we switch to v3 api.
        // ObjectUpdater library already implements retries and error handling.
        IOException exception = null;
        int waitTimeMS = INIT_ATTEMPTS_WAIT_TIME;
        for (int i = 1; i <= mMaxUploadAttempts; ++i) {
            try {
                uploadFileToAndroidBuildApi(filePath);
                return;
            } catch (IOException e) {
                exception = e;
                String message = e.getMessage();
                if (e instanceof GoogleJsonResponseException) {
                    GoogleJsonResponseException respException = (GoogleJsonResponseException) e;
                    // Get the scotty id for debugging purpose.
                    String value =
                            respException
                                    .getHeaders()
                                    .getFirstHeaderStringValue("x-guploader-uploadid");
                    if (!Strings.isNullOrEmpty(value)) {
                        CLog.w("More info at http://go/scottydash/lookup.html?uploadId=%s", value);
                    } else {
                        CLog.w("There is no \"x-guploader-uploadid\" in the response.");
                    }
                    message = respException.getStatusMessage();
                }
                CLog.w(
                        "Failed to upload file %s to the Android Build API due to %s in attempt"
                                + " %d.",
                        filePath, message, i);
                if (i < mMaxUploadAttempts) {
                    CLog.w("Wait %dms for another retry.", waitTimeMS);
                    getRunUtil().sleep(waitTimeMS);
                    waitTimeMS *= 2;
                }
            }
        }
        throw exception;
    }

    @VisibleForTesting
    protected IInvocationContext getInvocationContext() {
        return mInvocationContext;
    }

    private synchronized void fetchInvocationAndWorkUnitId() {
        if (Strings.isNullOrEmpty(mInvocationId)) {
            mInvocationId = getInvocationContext().getAttribute(INVOCATION_ID_KEY);
        }
        if (Strings.isNullOrEmpty(mWorkUnitId)) {
            mWorkUnitId = getInvocationContext().getAttribute(WORK_UNIT_ID_KEY);
        }
    }

    /**
     * Upload staged file to Android Build Api.
     *
     * @param filePath the staged file path
     * @throws IOException
     */
    void uploadFileToAndroidBuildApi(String filePath) throws IOException {
        long startUpload = System.currentTimeMillis();
        try {
            fetchInvocationAndWorkUnitId();

            if (!mUseLegacyMigration) {
                legacyUploadFileToAndroidBuildApi(filePath);
                return;
            }

            if (Strings.isNullOrEmpty(mInvocationId) || Strings.isNullOrEmpty(mWorkUnitId)) {
                CLog.w("Unable to upload logs because invocation id or work unit id are not set");
                return;
            }

            File f = new File(filePath);
            if (f.length() <= 0) {
                // Android Build API doesn't accept empty file
                FileUtil.writeToFile("empty", f);
            }

            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.ARTIFACTS_UPLOAD_SIZE, f.length());

            com.google.api.services.androidbuildinternal.v3.Androidbuildinternal client =
                    getV3Client();
            String contentType = FileUtil.getContentType(filePath);
            String md5 = FileUtil.calculateMd5(f);
            LogDataType type = getFilePathToLogDataTypeMap().get(filePath);
            String resourceId = getRemoteFilePath(f.getName());
            FileContent fileContent = new FileContent(contentType, f);

            runUpdate(client, filePath, resourceId, md5, fileContent, type, contentType);
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.AB_LOG_SAVER_UPLOAD_TIME,
                    System.currentTimeMillis() - startUpload);
        }
    }

    @VisibleForTesting
    protected void runUpdate(
            com.google.api.services.androidbuildinternal.v3.Androidbuildinternal client,
            String filePath,
            String resourceId,
            String md5,
            FileContent fileContent,
            LogDataType type,
            String contentType)
            throws IOException {
        ObjectUpdater updater = ObjectUpdater.create(client);
        updater.runUpdate(
                mInvocationId,
                resourceId,
                getTestResultId(),
                new ObjectUpdater.TestArtifactUpdate() {
                    @Override
                    public com.google.api.services.androidbuildinternal.v3.model
                                    .BuildArtifactMetadata
                            doUpdate(
                                    com.google.api.services.androidbuildinternal.v3.model
                                                    .BuildArtifactMetadata
                                            current) {
                        if (current == null) {
                            // If we are creating artifact for the first time, we will set work unit
                            // to the work unit that got passed to tf. It will be updated when
                            // associations are uploaded.
                            current =
                                    new com.google.api.services.androidbuildinternal.v3.model
                                                    .BuildArtifactMetadata()
                                            .setInvocationId(mInvocationId)
                                            .setWorkUnitId(mWorkUnitId)
                                            .setName(resourceId);
                        }
                        if (md5.equals(current.getMd5())) {
                            // if the file is the same skip upload.
                            return null;
                        }
                        CLog.i(
                                "File %s with different md5 exists in Android Build Api. "
                                        + "Will overwrite contents.\n",
                                filePath);
                        current.setContentType(contentType);
                        if (type != null) {
                            current.setArtifactType(type.name());
                        }
                        return current;
                    }

                    @Override
                    public AbstractInputStreamContent getContent() {
                        return fileContent;
                    }
                });
    }

    private void legacyUploadFileToAndroidBuildApi(String filePath) throws IOException {
        File f = new File(filePath);
        if (f.length() <= 0) {
            // Android Build API doesn't accept empty file
            FileUtil.writeToFile("empty", f);
        }
        String contentType = FileUtil.getContentType(filePath);
        BuildArtifactMetadata metadata = new BuildArtifactMetadata().setContentType(contentType);
        BuildArtifactMetadata artifact = getTestArtifact(getRemoteFilePath(f.getName()));
        if (artifact != null) {
            String md5 = FileUtil.calculateMd5(f);
            if (md5.equals(artifact.getMd5())) {
                CLog.i(
                        "File %s with same md5 exists in Android Build Api." + " Skip uploading.",
                        filePath);
                return;
            } else {
                CLog.i(
                        "File %s with different md5 exists in Android Build Api. "
                                + "Will overwrite contents.\n",
                        filePath);
                metadata.setRevision(artifact.getRevision());
            }
        }
        LogDataType type = getFilePathToLogDataTypeMap().get(filePath);
        if (type != null) {
            metadata.setArtifactType(type.name());
        }
        FileContent fileContent = new FileContent(contentType, f);
        TestResultIdentity testResultIdentity = getTestResultIdentity();

        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.ARTIFACTS_UPLOAD_SIZE, f.length());

        getClient()
                .testartifact()
                .update(
                        testResultIdentity.mTestResultBuildType.toString(),
                        testResultIdentity.mTestResultBuildId,
                        testResultIdentity.mTestResultBuildTarget,
                        testResultIdentity.mTestResultBuildAttemptId,
                        testResultIdentity.mTestResultId,
                        getRemoteFilePath(f.getName()),
                        metadata,
                        fileContent)
                .execute();
        CLog.i("Finished uploading %s", filePath);
    }

    /**
     * Get test artifact for a file from Android Build API.
     *
     * @param resourceId test artifact's resource id
     * @throws IOException
     */
    BuildArtifactMetadata getTestArtifact(String resourceId) throws IOException {
        // TODO(b/143365209): We can remove this method when switching over to v3 api.
        try {
            return getClient()
                    .testartifact()
                    .get(
                            mTestResultIdentity.mTestResultBuildType.toString(),
                            mTestResultIdentity.mTestResultBuildId,
                            mTestResultIdentity.mTestResultBuildTarget,
                            mTestResultIdentity.mTestResultBuildAttemptId,
                            mTestResultIdentity.mTestResultId,
                            resourceId)
                    .execute();
        } catch (HttpResponseException e) {
            if (e.getStatusCode() == 404) {
                CLog.i("File %s doesn't exist in Android Build API. Expected.", resourceId);
                return null;
            } else {
                throw e;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public LogFile saveLogData(String dataName, LogDataType dataType, InputStream dataStream)
            throws IOException {
        if (isSandbox()) {
            dataName = "sandbox-" + dataName;
        }
        File log = null;
        long stagingStart = System.currentTimeMillis();
        // Handle host_log specifically: we are tracking the main host_log location file to be
        // able to write the end_host_log to it.
        // We write directly the end_host_log to it because it's too late for it to be reported
        // and displayed in AnTS. So before host_log is uploaded, we complete the physical
        // log file.
        // We will still upload the end_host_log no matter what.
        try {
            if (TestInvocation.TRADEFED_END_HOST_LOG.equals(dataName) && mHostLogFile != null) {
                if (mHostLogFile.exists()) {
                    try (OutputStream output = new FileOutputStream(mHostLogFile, true)) {
                        StreamUtil.copyStreams(dataStream, output);
                    }
                    // We will use it again so reset the stream.
                    if (dataStream.markSupported()) {
                        dataStream.reset();
                    } else if (dataStream instanceof FileInputStream) {
                        ((FileInputStream) dataStream).getChannel().position(0L);
                    }
                }
            }

            if (!mCompressFiles) {
                log = mLogFileSaver.saveLogData(dataName, dataType, dataStream);
                mFilePathToLogDataTypeMap.put(log.getAbsolutePath(), dataType);
                LogFile logFile =
                        new LogFile(
                                log.getAbsolutePath(), getRemoteFileUrl(log.getName()), dataType);
                return logFile;
            }
            log = mLogFileSaver.saveAndGZipLogData(dataName, dataType, dataStream);
            mFilePathToLogDataTypeMap.put(log.getAbsolutePath(), dataType);
            return new LogFile(
                    log.getAbsolutePath(),
                    getRemoteFileUrl(log.getName()),
                    true /* compressed */,
                    dataType,
                    log.length());
        } finally {
            if (TestInvocation.TRADEFED_LOG_NAME.equals(dataName) && mHostLogFile == null) {
                mHostLogFile = log;
            }
            if (mLifecycleDone) {
                if (log != null) {
                    // Special case where we receive a log after the upload lifecycle
                    // is supposed to be done.
                    mUploadedLogs.add(log.getAbsolutePath());
                    uploadFileToAndroidBuildApi(log.getAbsolutePath());
                    if (mRemoveStagedFiles) {
                        FileUtil.recursiveDelete(getLogStagingDir());
                    }
                }
            }
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.AB_LOG_SAVER_STAGING_TIME,
                    System.currentTimeMillis() - stagingStart);
        }
    }

    @Override
    public LogFile saveLogFile(String dataName, LogDataType dataType, File fileToLog)
            throws IOException {
        if (isSandbox()) {
            dataName = "sandbox-" + dataName;
        }
        File log = null;
        long stagingStart = System.currentTimeMillis();
        try {
            if (!mCompressFiles) {
                log = mLogFileSaver.saveLogFile(dataName, dataType, fileToLog);
                mFilePathToLogDataTypeMap.put(log.getAbsolutePath(), dataType);
                return new LogFile(
                        log.getAbsolutePath(), getRemoteFileUrl(log.getName()), dataType);
            }
            // saveAndGZip already handles dataType that do not need compression.
            log = mLogFileSaver.saveAndGZipLogFile(dataName, dataType, fileToLog);
            mFilePathToLogDataTypeMap.put(log.getAbsolutePath(), dataType);
            return new LogFile(
                    log.getAbsolutePath(),
                    getRemoteFileUrl(log.getName()),
                    true,
                    dataType,
                    log.length());
        } finally {
            if (mLifecycleDone) {
                if (log != null) {
                    // Special case where we receive a log after the upload lifecycle
                    // is supposed to be done.
                    mUploadedLogs.add(log.getAbsolutePath());
                    uploadFileToAndroidBuildApi(log.getAbsolutePath());
                    if (mRemoveStagedFiles) {
                        FileUtil.recursiveDelete(getLogStagingDir());
                    }
                }
            }
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.AB_LOG_SAVER_STAGING_TIME,
                    System.currentTimeMillis() - stagingStart);
        }
    }

    private void updateArtifactMetadata() {
        fetchInvocationAndWorkUnitId();

        // TODO(b/143365209): Remove this method once we switch to v3 api.
        if (mUseLegacyMigration) {
            CLog.i("Not updating metadata because we are using v3 api for upload.");
            return;
        }
        if (mInvocationId == null || mInvocationId.isEmpty()) {
            CLog.i("Invocation id is empty, skipping metadata update.");
            return;
        }
        if (!mUpdateMetadata) {
            CLog.i("Metadata update is skipped in subprocess");
            return;
        }
        try {
            Map<String, BuildArtifactMetadata> correctMetadata = new HashMap<>();
            for (BuildArtifactMetadata artifact : listArtifacts()) {
                correctMetadata.put(artifact.getName(), artifact);
            }
            CLog.i("Got %d v2 artifacts", correctMetadata.size());
            com.google.api.services.androidbuildinternal.v3.Androidbuildinternal client =
                    AndroidBuildAPIHelper.getTestStorageClient(mBuildEnvironment);
            List<com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata>
                    artifacts = listArtifacts(client, mInvocationId);
            CLog.i("ArtifactList returnd %d artifacts", artifacts.size());

            for (com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata
                    artifact : artifacts) {
                BuildArtifactMetadata uploadedArtifact = correctMetadata.get(artifact.getName());
                if (uploadedArtifact == null) {
                    continue;
                }
                CLog.i("Update metadata for %s\nCorrect: %s", artifact, uploadedArtifact);
                try {
                    updateMetadata(client, artifact, uploadedArtifact);
                } catch (IOException e) {
                    CLog.e(e);
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            CLog.e(e);
        }
    }

    private List<BuildArtifactMetadata> listArtifacts() throws IOException {
        // TODO(b/143365209): Remove this method once we switch to v3 api.
        String pageToken = "";
        List<BuildArtifactMetadata> artifacts = new ArrayList<>();
        do {
            Testartifact.List request =
                    getClient()
                            .testartifact()
                            .list(
                                    mTestResultIdentity.mTestResultBuildType.toString(),
                                    mTestResultIdentity.mTestResultBuildId,
                                    mTestResultIdentity.mTestResultBuildTarget,
                                    mTestResultIdentity.mTestResultBuildAttemptId,
                                    mTestResultIdentity.mTestResultId)
                            .setMaxResults(1000L);
            if (!pageToken.isEmpty()) {
                request.setPageToken(pageToken);
            }
            com.google.api.services.androidbuildinternal.model.TestArtifactListResponse response =
                    request.execute();
            pageToken = response.getNextPageToken();
            if (response.getTestArtifacts() != null) {
                artifacts.addAll(response.getTestArtifacts());
            }
        } while (pageToken != null && !pageToken.isEmpty());

        return artifacts;
    }

    private List<com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata>
            listArtifacts(
                    com.google.api.services.androidbuildinternal.v3.Androidbuildinternal client,
                    String invocationId)
                    throws IOException {
        // TODO(b/143365209): Remove this method once we switch to v3 api.
        String pageToken = "";
        List<com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata>
                artifacts = new ArrayList<>();
        do {
            com.google.api.services.androidbuildinternal.v3.Androidbuildinternal.Testartifact.List
                    request =
                            client.testartifact()
                                    .list()
                                    .setInvocationId(invocationId)
                                    .setMaxResults(1000L);
            if (!pageToken.isEmpty()) {
                request.setPageToken(pageToken);
            }
            TestArtifactListResponse response = request.execute();
            pageToken = response.getNextPageToken();
            if (response.getTestArtifacts() != null) {
                artifacts.addAll(response.getTestArtifacts());
            }
        } while (pageToken != null && !pageToken.isEmpty());

        return artifacts;
    }

    private void updateMetadata(
            com.google.api.services.androidbuildinternal.v3.Androidbuildinternal client,
            com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata
                    staleArtifact,
            BuildArtifactMetadata correctMetadata)
            throws IOException {
        // TODO(b/143365209): Remove this method once we switch to v3 api.

        int attempts = 0;
        do {
            com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata update =
                    new com.google.api.services.androidbuildinternal.v3.model
                            .BuildArtifactMetadata();
            String artifactType = correctMetadata.getArtifactType();
            if (artifactType != null && !artifactType.isEmpty()) {
                update.setArtifactType(artifactType);
            }
            update.setContentType(correctMetadata.getContentType());
            update.setRevision(staleArtifact.getRevision());
            try {
                attempts++;
                client.testartifact()
                        .patch(staleArtifact.getName(), update)
                        .setInvocationId(mInvocationId)
                        .execute();
                break;
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() != HttpURLConnection.HTTP_CONFLICT) {
                    throw e;
                }
                // We detected a conflicting write. It might be the artifact was already updated.
                // Get the latest version.
                if (attempts >= DEFAULT_MAX_UPLOAD_ATTEMPTS) {
                    CLog.i("Too many attempts failed to update metadata for %s", staleArtifact);
                    continue;
                }
                staleArtifact =
                        client.testartifact()
                                .get(staleArtifact.getName())
                                .setInvocationId(mInvocationId)
                                .setWorkUnitId(staleArtifact.getWorkUnitId())
                                .setResultId(staleArtifact.getTestResultId())
                                .execute();
            }
        } while (attempts < DEFAULT_MAX_UPLOAD_ATTEMPTS);
    }

    /** {@inheritDoc} */
    @Override
    public LogFile getLogReportDir() {
        return new LogFile(getRemoteFilePath(""), getRemoteFileUrl(""), LogDataType.DIR);
    }

    /**
     * Generate base path in Android Build Api, used as prefix of resource id for testaritifact api.
     *
     * @param invPathSegments
     * @return path in Android Build Api.
     */
    String generateAndroidBuildApiPath(List<String> invPathSegments) {
        return String.join(ANDROID_BUILD_API_PATHSEP, invPathSegments.toArray(new String[] {}));
    }

    /**
     * List the files which are staged for upload to Android Build Api.
     *
     * <p>Exposed for unit testing
     */
    List<String> listStagedFiles(File parentDir) {
        final File[] files = parentDir.listFiles();
        if (files == null) {
            return null;
        }

        List<String> paths = new LinkedList<>();
        for (int i = 0; i < files.length; ++i) {
            String filepath = files[i].getPath();
            if (!mUploadedLogs.contains(filepath)) {
                paths.add(files[i].getPath());
            }
        }

        return paths;
    }

    /**
     * A helper method that returns a URL for a given file name.
     *
     * @param fileName the filename of the log
     * @return A URL that should allow visitors to view the logfile with the specified name
     */
    String getRemoteFileUrl(String fileName) {
        if (mTestResultIdentity == null) {
            return null;
        }
        String baseUrl =
                String.format(
                        URL_TEMPLATE,
                        mTestResultIdentity.mTestResultBuildType,
                        mTestResultIdentity.mTestResultBuildId,
                        mTestResultIdentity.mTestResultBuildTarget,
                        mTestResultIdentity.mTestResultBuildAttemptId,
                        String.valueOf(mTestResultIdentity.mTestResultId));
        fileName = sanitizeUrlFileName(fileName);
        return baseUrl + getRemoteFilePath(fileName);
    }

    /** Escape special url character (like spaces and #) to have a proper link. */
    private static String sanitizeUrlFileName(String filename) {
        return UrlEscapers.urlPathSegmentEscaper().escape(filename);
    }

    /**
     * A helper method that returns the remote path for a given file name.
     *
     * @param fileName the filename of the log
     * @return remote path as an resource id of the log file in the Android Build API.
     */
    String getRemoteFilePath(String fileName) {
        return String.format("%s%s%s", getRemotePath(), ANDROID_BUILD_API_PATHSEP, fileName);
    }

    /**
     * Get the Androidbuildinternal client.
     *
     * @return a Androidbuildinternal
     */
    Androidbuildinternal getClient() {
        // Prevent multiple threads from trying to initialize a client at the same time.
        synchronized (this) {
            if (!mClientInitialized) {
                mClientInitialized = true;
                try {
                    setClient(AndroidBuildAPIHelper.getLegacyClient(mBuildEnvironment));
                } catch (GeneralSecurityException | IOException e) {
                    CLog.e("Failed to create Android Build Api client.");
                    CLog.e(e);
                    CLog.e("Logs will be saved locally.");
                }
            }
        }
        return mClient;
    }

    synchronized com.google.api.services.androidbuildinternal.v3.Androidbuildinternal
            getV3Client() {
        if (mClientV3 != null) {
            return mClientV3;
        }
        try {
            mClientV3 = AndroidBuildAPIHelper.getTestStorageClient(mBuildEnvironment);
        } catch (GeneralSecurityException | IOException e) {
            CLog.e("Failed to create Android Build Api V3 client");
            CLog.e(e);
            CLog.e("Logs will be saved locally");
        }
        return mClientV3;
    }

    /**
     * Get build attempt id for the current build.
     *
     * @return build attempt id
     */
    String getBuildAttemptId() {
        String buildAttemptId = getBuildInfo().getBuildAttributes().get(BUILD_ATTEMPT_ID);
        if (buildAttemptId == null) {
            return DEFAULT_BUILD_ATTEMPT_ID;
        }
        return buildAttemptId;
    }

    /**
     * Get build type.
     *
     * @return build type.
     */
    RemoteBuildInfo.BuildType getBuildType() {
        return RemoteBuildInfo.parseBuildType(getBuildInfo().getBuildId());
    }

    /**
     * Get build target. If there is no build target attribute, return build flavor.
     *
     * @return build target.
     */
    String getBuildTarget() {
        String buildTarget = getBuildInfo().getBuildAttributes().get(BUILD_TARGET);
        if (buildTarget == null) {
            String build_flavor = getBuildInfo().getBuildFlavor();
            CLog.w("No build target, fall back to build flavor %s", build_flavor);
            return build_flavor;
        }
        return buildTarget;
    }

    /**
     * Set the Androidbuildinternal client. Exposed for testing.
     *
     * @param client
     */
    void setClient(Androidbuildinternal client) {
        mClient = client;
    }

    /** Get IBuildInfo. Exposed for testing. */
    IBuildInfo getBuildInfo() {
        return mBuildInfo;
    }

    /**
     * Set IBuildInfo. Exposed for testing.
     *
     * @param buildInfo
     */
    void setBuildInfo(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    /**
     * Get test result identify.
     *
     * @return test result identify.
     */
    @VisibleForTesting
    TestResultIdentity getTestResultIdentity() {
        return mTestResultIdentity;
    }

    /**
     * Get file path to log data type map.
     *
     * @return the file path to log data type map.
     */
    @VisibleForTesting
    Map<String, LogDataType> getFilePathToLogDataTypeMap() {
        return mFilePathToLogDataTypeMap;
    }

    /**
     * Get test result id.
     *
     * @return test result id
     */
    Long getTestResultId() {
        return mTestResultIdentity.mTestResultId;
    }

    /**
     * Get test result build id.
     *
     * @return test result build id
     */
    String getTestResultBuildId() {
        return mTestResultIdentity.mTestResultBuildId;
    }

    /**
     * Get test result build target.
     *
     * @return test result build target
     */
    String getTestResultBuildTarget() {
        return mTestResultIdentity.mTestResultBuildTarget;
    }

    /**
     * Set log root path Exposed for testing.
     *
     * @param logRootPath
     */
    void setLogRootPath(File logRootPath) {
        mLogRootPath = logRootPath;
    }

    /**
     * Get log root path Exposed for testing.
     *
     * @return the log root path
     */
    File getLogRootPath() {
        return mLogRootPath;
    }

    /**
     * Set log staging dir. Exposed for testing.
     *
     * @param logStagingDir
     */
    void setLogStagingDir(File logStagingDir) {
        mLogStagingDir = logStagingDir;
    }

    /**
     * Get log staging dir. Exposed for testing.
     *
     * @return the log staing dir.
     */
    File getLogStagingDir() {
        return mLogStagingDir;
    }

    /**
     * Get remote path. Exposed for testing.
     *
     * @return the remote path
     */
    String getRemotePath() {
        return mRemotePath;
    }

    /**
     * Set compress. Exposed for testing.
     *
     * @param compress
     */
    void setCompressFiles(boolean compress) {
        mCompressFiles = compress;
    }

    /**
     * Set use stub build. Exposed for testing.
     *
     * @param useStubBuild
     */
    void setUseStubBuild(boolean useStubBuild) {
        mUseStubBuild = useStubBuild;
    }

    /** Sets whether or not to use periodic uploads. */
    void setUsePeriodicUpload(boolean usePeriodicUpload) {
        mPeriodicUpload = usePeriodicUpload;
    }

    /** Sets the frequency at which periodic upload should run. */
    void setPeriodicUploadFrequency(long freq) {
        mPeriodicUploadFreq = freq;
    }

    /**
     * Get the log file saver. Exposed for testing.
     *
     * @return log file saver
     */
    LogFileSaver getLogFilerSaver() {
        return mLogFileSaver;
    }

    /**
     * @return the default RunUtil.
     */
    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /**
     * Set the client initialized flag. Exposed for testing.
     *
     * @param clientInitialized
     */
    void setClientInitialized(boolean clientInitialized) {
        mClientInitialized = clientInitialized;
    }

    @VisibleForTesting
    void waitForUploadThread() {
        if (mUploadThread != null) {
            mUploadThread.cancel();
            try {
                CLog.d("Waiting for the periodic upload thread.");
                mUploadThread.join(UPLOAD_THREAD_JOIN_TIMEOUT_MS);
                CLog.d("Done waiting for the periodic upload thread.");
            } catch (InterruptedException e) {
                CLog.e(e);
            }
        }
    }

    boolean isSandbox() {
        return System.getenv(TradefedSandbox.SANDBOX_ENABLED) != null;
    }

    /** Threads that execute an upload then sleeps and so on until cancelled. */
    @VisibleForTesting
    class UploadThread extends Thread {

        private boolean mCancel = false;
        private final long mFrequency;

        public UploadThread(long frequency) {
            setDaemon(true);
            setName("AndroidBuildApiLogSaver-UploadThread");
            mFrequency = frequency;
        }

        @Override
        public void run() {
            RunUtil.getDefault().allowInterrupt(true);
            while (!mCancel) {
                if (!shouldUpload()) {
                    return;
                }
                fetchInvocationAndWorkUnitId();
                if (!Strings.isNullOrEmpty(mInvocationId) && !Strings.isNullOrEmpty(mWorkUnitId)) {
                    List<String> stagedPaths = listStagedFiles(getLogStagingDir());
                    if (stagedPaths == null) {
                        // It is uncommon that we don't even have a host log to save, so log
                        // something
                        CLog.w("No log files were saved; skipping export to Android Build Api.");
                        return;
                    }
                    RunUtil.getDefault().allowInterrupt(false);
                    if (mParallelUpload) {
                        int poolSize = Math.min(stagedPaths.size(), 50);
                        if (poolSize != 0) {
                            ParallelDeviceExecutor<Boolean> executor =
                                    new ParallelDeviceExecutor<>(poolSize);
                            List<Callable<Boolean>> callableTasks = new ArrayList<>();
                            for (String stagedPath : stagedPaths) {
                                // Only upload files that have artifact type in the map already
                                // Files with no artifact type will be uploaded at invocationEnded
                                // or in later rounds when the type is included in the map.
                                if (getFilePathToLogDataTypeMap().get(stagedPath) != null) {
                                    Callable<Boolean> callableTask =
                                            () -> {
                                                uploadOnPath(stagedPath);
                                                return true;
                                            };
                                    callableTasks.add(callableTask);
                                    // mark this log file as handled for upload, so that it's not
                                    // considered for upload during next rounds of periodic upload.
                                    mUploadedLogs.add(stagedPath);
                                }
                            }
                            executor.invokeAll(callableTasks, 0L, TimeUnit.MINUTES);
                        }
                    } else {
                        for (String stagedPath : stagedPaths) {
                            if (mCancel) {
                                return;
                            }
                            if (getFilePathToLogDataTypeMap().get(stagedPath) != null) {
                                // mark this log file as handled for upload, so that it's not
                                // considered
                                // for upload during next rounds of periodic upload.
                                mUploadedLogs.add(stagedPath);
                                uploadOnPath(stagedPath);
                            }
                        }
                    }
                } else {
                    CLog.i(
                            "Delaying log upload due to empty invocation or work unit id. If this"
                                + " message repeats, the id might not have been set correctly.");
                }

                try {
                    RunUtil.getDefault().allowInterrupt(true);
                    if (mCancel) {
                        return;
                    }
                    RunUtil.getDefault().sleep(mFrequency);
                } catch (RunInterruptedException e) {
                    if (!mCancel) {
                        // If it wasn't interrupted due to cancel, then let the exception bubble up
                        throw e;
                    }
                    return;
                }
            }
        }

        public void cancel() {
            mCancel = true;
            CLog.d("Marking UploadThread as canceled for end of invocation.");
            // This should only really interrupt the sleeping
            RunUtil.getDefault().interrupt(this, "Canceling ants upload thread");
        }
    }
}
