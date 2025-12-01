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
package com.android.tradefed.result.resultdb;

import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.ProducerResource;
import com.android.resultdb.proto.RootInvocation;
import com.android.resultdb.proto.Sources;
import com.android.resultdb.proto.SubmittedAndroidBuild;
import com.android.resultdb.proto.WorkUnit;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.Log.LogLevel;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestSummaryListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestSummary;
import com.android.tradefed.result.retry.ISupportGranularResults;
import com.android.tradefed.result.skipped.SkipReason;

import com.google.android.tradefed.build.AbLocalBuildProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.FieldMask;

import java.util.HashMap;
import java.util.UUID;

/** Result reporter that uploads test results to ResultDB. */
@OptionClass(alias = "resultdb-reporter")
public class ResultDBReporter
        implements ITestSummaryListener,
                ILogSaverListener,
                ISupportGranularResults,
                IConfigurationReceiver {

    private static final String BUILD_ENV_INVOCATION_CONTEXT_KEY = "android-build-environment";

    @Option(name = "disable", description = "Set to true if reporter is disabled")
    private boolean mDisable = false;

    // Option used to test Tradefed ResultDB integration without invocation created by ATE.
    @Option(
            name = "create-local-invocation",
            description = "Create a local invocation if invocation is not provided in the context")
    private boolean mCreateLocalInvocation = false;

    private IConfiguration mConfiguration;
    private String mInvocationId;
    private String mTopWorkUnitId;
    private String mRootInvocationId;
    // Set to true if the reporter is responsible for updating and finalizing the invocation.
    private boolean mManageInvocation = false;

    // A legacy recorder client that uploading test results to legacy invocation.
    // Only set when report to legacy invocation is enabled, and mInvocationId is set.
    private IRecorderClientLegacy mRecorderLegacy;
    // A recorder client that uploading test results to work unit.
    // Only set when report to root invocation and work unit is enabled, and mRootInvocationId,
    // mTopWorkUnitId are set.
    private IRecorderClient mRecorder;
    private boolean mIsStaging = false;

    private final TestResultHandler mTestResultHandler = createTestResultHandler();
    private final WorkUnitHandler mWorkUnitHandler = new WorkUnitHandler();
    private final TestArtifactHandler mTestArtifactHandler = new TestArtifactHandler();

    @VisibleForTesting
    TestResultHandler createTestResultHandler() {
        return new TestResultHandler();
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    @Override
    public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
        // TODO: implement this method.
    }

    @Override
    public void logAssociation(String dataName, LogFile logFile) {
        if (mDisable) {
            return;
        }
        mTestArtifactHandler.logAssociation(
                dataName,
                logFile,
                mWorkUnitHandler.getCurrentWorkUnitId(),
                mTestResultHandler.getCurrentTestResult());
    }

    /**
     * Uploads the storage path of an AnTS test artifact to ResultDB.
     *
     * <p>This is a temporary solution for the migration from AnTS to ResultDB (see
     * go/convergence-tr). This method should be called by the AnTS reporter when an artifact's
     * storage path becomes available.
     *
     * <p>The AnTS reporter guarantees it will call this method for every artifact it uploads. This
     * reporter, in turn, will upload all such artifacts to ResultDB with the correct test
     * association. For correct log association,it is required that {@link #logAssociation(String,
     * LogFile)} has been called for the same resource ID beforehand. This ensures that the artifact
     * is already tracked. Otherwise, the artifact will be associated with the top-level work unit
     * of the tradefed invocation.
     *
     * <p>This method must not be called before {@link #invocationStarted(IInvocationContext)}. It
     * can be called after {@link #invocationEnded(long)} for invocations managed by an upstream
     * system, as they are not finalized by this reporter. This does not apply to locally created
     * invocations, which are finalized in {@link #invocationEnded(long)}.
     *
     * <p>TODO(b/464941055): Remove this method once we can properly migrate AnTS artifacts to
     * ResultDB.
     *
     * @param resourceId The resource ID of the artifact.
     * @param storagePath The storage path of the artifact (e.g., a /bigstore/ path).
     * @param logFile The {@link LogFile} object associated with the artifact.
     */
    public void uploadStoragePath(String resourceId, String storagePath, LogFile logFile) {
        if (mDisable) {
            return;
        }
        mTestArtifactHandler.uploadStoragePath(
                resourceId, storagePath, logFile, mTopWorkUnitId, mRecorderLegacy, mRecorder);
    }

    @Override
    public void setLogSaver(ILogSaver logSaver) {
        // TODO: implement this method.
    }

    @Override
    public TestSummary getSummary() {
        // TODO: implement this method.
        return null;
    }

    @VisibleForTesting
    IRecorderClientLegacy createRecorderClientLegacy(
            String invocationId, String updateToken, Boolean isStaging) {
        return RecorderClientLegacy.createLegacy(invocationId, updateToken, isStaging);
    }

    @VisibleForTesting
    IRecorderClientLegacy createRecorderClientLegacy(
            String invocationId, Invocation invocation, Boolean isStaging) {
        return RecorderClientLegacy.createLegacyWithNewInvocation(
                invocationId, invocation, isStaging);
    }

    @VisibleForTesting
    IRecorderClient createRecorderClient(
            String rootInvocationId, String updateToken, Boolean isStaging) {
        return RecorderClient.create(rootInvocationId, updateToken, isStaging);
    }

    @VisibleForTesting
    IRecorderClient createRecorderClient(
            String rootInvocationId,
            RootInvocation rootInvocation,
            WorkUnit rootWorkUnit,
            Boolean isStaging) {
        return RecorderClient.create(rootInvocationId, rootInvocation, rootWorkUnit, isStaging);
    }

    @Override
    public void invocationStarted(IInvocationContext context) {
        if (mDisable) {
            CLog.i("ResultDBReporter is disabled");
            return;
        }
        mIsStaging = isStaging(context);

        boolean successLegacy = initRecorderLegacy(context);
        boolean success = initRecorder(context);
        if (!success && !successLegacy) {
            mDisable = true;
            CLog.i("ResultDBReporter is disabled");
            return;
        }
        CLog.logAndDisplay(LogLevel.INFO, "Result links: %s", String.join(", ", getResultLinks()));
    }

    private ImmutableList<String> getResultLinks() {
        String host = "";
        if (mIsStaging) {
            host = "https://luci-milo-dev.appspot.com";
        } else {
            host = "https://ci.chromium.org";
        }
        ImmutableList.Builder<String> links = ImmutableList.builder();
        if (mInvocationId != null) {
            links.add(String.format("%s/ui/inv/%s", host, mInvocationId));
        }
        if (!Strings.isNullOrEmpty(mRootInvocationId)) {
            links.add(
                    String.format(
                            "%s/ui/test-investigate/invocations/%s", host, mRootInvocationId));
        }
        return links.build();
    }

    private IBuildInfo getPrimaryBuild(IInvocationContext context) {
        if (context.getBuildInfos().isEmpty()) {
            return null;
        }
        if (context.getBuildInfo(AbLocalBuildProvider.LOCAL_BUILD) != null) {
            return context.getBuildInfo(AbLocalBuildProvider.LOCAL_BUILD);
        }
        return context.getBuildInfos().get(0);
    }

    /**
     * Initializes the ResultDB client with root invocation, work unit information from the context.
     *
     * @return true if the client is initialized successfully, false otherwise. If it succeeds,
     *     mRecorder, mRootInvocationId and mTopWorkUnitId are set, otherwise none of them is set.
     */
    private boolean initRecorder(IInvocationContext context) {
        try {
            // Read root invocation and work unit context.
            String rootInvocationId = context.getAttribute("resultdb_root_invocation_id");
            String workUnitId = context.getAttribute("resultdb_work_unit_id");
            String workUnitUpdateToken = context.getAttribute("resultdb_work_unit_update_token");

            if (!rootInvocationId.isEmpty()) {
                if (workUnitId.isEmpty() || workUnitUpdateToken.isEmpty()) {
                    CLog.w(
                            "both resultdb_work_unit_id and resultdb_work_unit_update_token are"
                                    + " required when resultdb_root_invocation_id is provided %s",
                            rootInvocationId);
                    return false;
                }
                CLog.i(
                        "ResultDBReporter received root invocation ID: %s, work unit ID: %s and"
                                + " update token",
                        rootInvocationId, workUnitId);
                mTopWorkUnitId = workUnitId;
                mRootInvocationId = rootInvocationId;
                mRecorder =
                        createRecorderClient(
                                rootInvocationId, workUnitUpdateToken, isStaging(context));
                mWorkUnitHandler.setTopWorkUnitInfo(
                        mTopWorkUnitId, WorkUnitHandler.TF_INVOCATION_WORK_UNIT_TYPE);
            } else if (mCreateLocalInvocation) {
                IBuildInfo primaryBuild = getPrimaryBuild(context);
                if (primaryBuild == null) {
                    CLog.w("No primary build found, skipping ResultDBReporter initialization.");
                    return false;
                }
                String branch =
                        primaryBuild.getBuildBranch() != null
                                ? primaryBuild.getBuildBranch()
                                : "unknown";
                // TODO(b/463830449): Update the sources field with the correct information. Set
                // temporary data
                // to unblock upload.
                Sources sources =
                        Sources.newBuilder()
                                .setSubmittedAndroidBuild(
                                        SubmittedAndroidBuild.newBuilder()
                                                .setDataRealm("prod")
                                                .setBranch(branch)
                                                .setBuildId(
                                                        Long.parseLong(primaryBuild.getBuildId()))
                                                .build())
                                .build();
                ProducerResource producerResource =
                        ProducerResource.newBuilder()
                                .setSystem("tradefed")
                                .setDataRealm("prod")
                                .setName("runs/" + randomUUIDString())
                                .build();
                RootInvocation rootInvocation =
                        RootInvocation.newBuilder()
                                .setRealm("android:ants-experiment")
                                .setSources(sources)
                                .setStreamingExportState(
                                        RootInvocation.StreamingExportState.WAIT_FOR_METADATA)
                                .setProducerResource(producerResource)
                                .build();
                mRootInvocationId = "u-" + randomUUIDString().toString();

                WorkUnit rootWorkUnit =
                        WorkUnit.newBuilder()
                                .setState(WorkUnit.State.RUNNING)
                                .setKind(WorkUnitHandler.TF_INVOCATION_WORK_UNIT_TYPE)
                                .build();
                mRecorder =
                        createRecorderClient(
                                mRootInvocationId,
                                rootInvocation,
                                rootWorkUnit,
                                isStaging(context));
                mTopWorkUnitId = "root"; // Top work unit is the root work unit.
                mManageInvocation = true;
                mWorkUnitHandler.setTopWorkUnitInfo(
                        mTopWorkUnitId, WorkUnitHandler.TF_INVOCATION_WORK_UNIT_TYPE);
            } else {
                CLog.i(
                        "ResultDBReporter is disabled as root invocation ID, work unit ID and"
                                + " update token are not provided.");
                return false;
            }
        } catch (RuntimeException e) {
            CLog.e("Failed to create ResultDB client. Error: %s", e.getMessage());
            if (mRecorder != null) {
                // Make sure we cancel the client, otherwise it will leak a thread since
                // invocationEnded will skip the finalization.
                mRecorder.finalizeUpload();
            }
            mRecorder = null;
            mRootInvocationId = null;
            mTopWorkUnitId = null;
            return false;
        }
        return true;
    }

    /**
     * Initializes the ResultDB client with legacy invocation information from the context.
     *
     * @return true if the client is initialized successfully, false otherwise. If it succeeds,
     *     mInvocationId and mRecorderLegacy are set, otherwise none of them is set.
     */
    private boolean initRecorderLegacy(IInvocationContext context) {
        try {
            // Obtain invocation ID from context.
            String invocationId = context.getAttribute("resultdb_invocation_id");
            String updateToken = context.getAttribute("resultdb_invocation_update_token");
            if (!invocationId.isEmpty() && !updateToken.isEmpty()) {
                mInvocationId = invocationId;
                mRecorderLegacy =
                        createRecorderClientLegacy(invocationId, updateToken, isStaging(context));
            } else if (mCreateLocalInvocation) {
                Invocation invocation =
                        Invocation.newBuilder()
                                .setRealm("android:ants-experiment")
                                .setIsExportRoot(true)
                                .build();
                mInvocationId = "u-" + randomUUIDString().toString();
                mRecorderLegacy =
                        createRecorderClientLegacy(mInvocationId, invocation, isStaging(context));
                mManageInvocation = true;
            } else {
                CLog.i(
                        "ResultDBReporter is disabled as invocation ID or update token is not"
                                + " provided.");
                return false;
            }
        } catch (RuntimeException e) {
            CLog.e("Failed to create ResultDB client legacy. Error: %s", e.getMessage());
            if (mRecorderLegacy != null) {
                // Make sure we cancel the client, otherwise it will leak a thread since
                // invocationEnded will skip the finalization.
                mRecorderLegacy.finalizeUpload();
                mTestArtifactHandler.setUploadQueueFinalized();
            }
            mRecorderLegacy = null;
            mInvocationId = null;
            return false;
        }
        return true;
    }

    private Boolean isStaging(IInvocationContext context) {
        String buildEnvironment = context.getAttribute(BUILD_ENV_INVOCATION_CONTEXT_KEY);
        return !buildEnvironment.equals("prod");
    }

    @Override
    public void invocationFailed(Throwable cause) {
        // TODO: implement this method.
    }

    @Override
    public void invocationFailed(FailureDescription failure) {
        // TODO: implement this method.
    }

    @Override
    public void invocationSkipped(SkipReason reason) {
        // TODO: implement this method.
    }

    @Override
    public void invocationEnded(long elapsedTime) {
        if (mDisable) {
            return;
        }
        if (mRecorderLegacy != null) {
            mRecorderLegacy.finalizeUpload();
            mTestArtifactHandler.setUploadQueueFinalized();
            if (mManageInvocation) {
                mRecorderLegacy.finalizeInvocation();
            }
        }
        if (mRecorder != null) {
            mRecorder.finalizeUpload();
            if (mManageInvocation) {
                mRecorder.updateWorkUnit(
                        WorkUnit.newBuilder()
                                .setName(
                                        String.format(
                                                "rootInvocations/%s/workUnits/root",
                                                mRootInvocationId))
                                .setState(WorkUnit.State.SUCCEEDED)
                                .build(),
                        FieldMask.newBuilder().addPaths("state").build());
            }
        }
        CLog.logAndDisplay(LogLevel.INFO, "Result links: %s", String.join(", ", getResultLinks()));
        // TODO(b/463830449): Update ResultDB invocation with information from TF invocation.
    }

    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        if (mDisable) {
            return;
        }
        mWorkUnitHandler.testModuleStarted(moduleContext, mTopWorkUnitId, mRecorder);
    }

    @Override
    public void testModuleEnded() {
        if (mDisable) {
            return;
        }
        mWorkUnitHandler.testModuleEnded(mRootInvocationId, mRecorder);
    }

    @Override
    public void testRunEnded(
            long elapsedTimeMillis, HashMap<String, MetricMeasurement.Metric> runMetrics) {
        if (mDisable) {
            return;
        }
        try {
            mTestResultHandler.reportDemotedTests(
                    mConfiguration,
                    mWorkUnitHandler.getModuleContext(),
                    currentTimestamp(),
                    mWorkUnitHandler.getCurrentModuleIdentifier(),
                    mWorkUnitHandler.getCurrentWorkUnitId(),
                    mRecorderLegacy,
                    mRecorder);
        } catch (RuntimeException e) {
            CLog.e("error during skip reporting:");
            CLog.e(e);
        }
        mWorkUnitHandler.testRunEnded(mRootInvocationId, mTopWorkUnitId, mRecorder);
    }

    @Override
    public void testRunFailed(String errorMessage) {
        // TODO: implement this method.
    }

    @Override
    public void testRunFailed(FailureDescription failure) {
        // TODO: implement this method.
    }

    @Override
    public void testRunStarted(String runName, int testCount) {
        testRunStarted(runName, testCount, 0);
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber) {
        testRunStarted(runName, testCount, attemptNumber, currentTimestamp());
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber, long startTime) {
        if (mDisable) {
            return;
        }
        mWorkUnitHandler.testRunStarted(
                runName, testCount, attemptNumber, startTime, mTopWorkUnitId, mRecorder);
    }

    @VisibleForTesting
    long currentTimestamp() {
        return System.currentTimeMillis();
    }

    @VisibleForTesting
    String randomUUIDString() {
        return UUID.randomUUID().toString();
    }

    @Override
    public void testRunStopped(long elapsedTime) {
        // TODO: implement this method.
    }

    @Override
    public void testStarted(TestDescription test) {
        testStarted(test, currentTimestamp());
    }

    @Override
    public void testStarted(TestDescription test, long startTime) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testStarted(
                test, startTime, mWorkUnitHandler.getCurrentModuleIdentifier());
    }

    @Override
    public void testAssumptionFailure(TestDescription test, String trace) {
        testAssumptionFailure(test, FailureDescription.create(trace));
    }

    @Override
    public void testAssumptionFailure(TestDescription test, FailureDescription failure) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testAssumptionFailure(
                test, failure, mWorkUnitHandler.getCurrentWorkUnitId(), mRecorderLegacy, mRecorder);
    }

    @Override
    public void testSkipped(TestDescription test, SkipReason reason) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testSkipped(
                test, reason, mWorkUnitHandler.getCurrentWorkUnitId(), mRecorderLegacy, mRecorder);
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testFailed(
                test,
                FailureDescription.create(trace),
                mWorkUnitHandler.getCurrentWorkUnitId(),
                mRecorderLegacy,
                mRecorder);
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testFailed(
                test, failure, mWorkUnitHandler.getCurrentWorkUnitId(), mRecorderLegacy, mRecorder);
    }

    @Override
    public void testIgnored(TestDescription test) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testIgnored(test);
    }

    @Override
    public void testEnded(
            TestDescription test, HashMap<String, MetricMeasurement.Metric> testMetrics) {
        testEnded(test, currentTimestamp(), testMetrics);
    }

    @Override
    public void testEnded(
            TestDescription test,
            long endTime,
            HashMap<String, MetricMeasurement.Metric> testMetrics) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testEnded(
                test,
                endTime,
                testMetrics,
                mWorkUnitHandler.getCurrentWorkUnitId(),
                mRecorderLegacy,
                mRecorder);
    }

    @Override
    public boolean supportGranularResults() {
        return true;
    }

    public boolean isDisabled() {
        return mDisable;
    }
}
