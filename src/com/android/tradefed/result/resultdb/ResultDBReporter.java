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

import com.android.resultdb.proto.CreateInvocationRequest;
import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.Variant;
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

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

    // Link to redirect to the result page.
    private static final String RESULT_LINK = "https://ci.chromium.org/ui/inv/invocations/%s";

    @Option(name = "disable", description = "Set to true if reporter is disabled")
    private boolean mDisable = false;

    // Option used to test Tradefed ResultDB integration without invocation created by ATE.
    @Option(
            name = "create-local-invocation",
            description = "Create a local invocation if invocation is not provided in the context")
    private boolean mCreateLocalInvocation = false;

    private IConfiguration mConfiguration;
    private String mInvocationId;
    // Set to true if the reporter is responsible for updating and finalizing the invocation.
    private boolean mManageInvocation = false;
    private IRecorderClient mRecorder;

    // Common variant values for all test in this TF invocation.
    private Variant mBaseVariant;

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
                dataName, logFile, mTestResultHandler.getCurrentTestResult());
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
     * <p>TODO(beining): Remove this method once we can properly migrate AnTS artifacts to ResultDB.
     *
     * @param resourceId The resource ID of the artifact.
     * @param storagePath The storage path of the artifact (e.g., a /bigstore/ path).
     * @param logFile The {@link LogFile} object associated with the artifact.
     */
    public void uploadStoragePath(String resourceId, String storagePath, LogFile logFile) {
        if (mDisable) {
            return;
        }
        if (mRecorder == null) {
            CLog.w(
                    "Recorder is not initialized, skipping upload storage path %s %s",
                    resourceId, storagePath);
            return;
        }
        mTestArtifactHandler.uploadStoragePath(resourceId, storagePath, logFile, mRecorder);
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
    IRecorderClient createRecorderClient(
            String invocationId, String updateToken, Boolean isStaging) {
        return RecorderClient.create(invocationId, updateToken, isStaging);
    }

    @VisibleForTesting
    IRecorderClient createRecorderClient(CreateInvocationRequest request, Boolean isStaging) {
        return RecorderClient.createWithNewInvocation(request, isStaging);
    }

    @Override
    public void invocationStarted(IInvocationContext context) {
        if (mDisable) {
            CLog.i("ResultDBReporter is disabled");
            return;
        }
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
                return;
            }
            CLog.i(
                    "ResultDBReporter received root invocation ID: %s, work unit ID: %s and"
                            + " update token",
                    rootInvocationId, workUnitId);
            CLog.i(
                    "Disabling ResultDBReporter, because ResultDB work unit support is not"
                            + " yet implemented in this plugin. Test results will be uploaded"
                            + " automatically once support is ready.");
            // TODO(beining): Implement work unit upload support.
            mDisable = true;
            return;
        }
        try {
            // Obtain invocation ID from context.
            String invocationId = context.getAttribute("resultdb_invocation_id");
            String updateToken = context.getAttribute("resultdb_invocation_update_token");
            if (!invocationId.isEmpty() && !updateToken.isEmpty()) {
                mInvocationId = invocationId;
                mRecorder = createRecorderClient(invocationId, updateToken, isStaging(context));
            } else if (mCreateLocalInvocation) {
                Invocation invocation =
                        Invocation.newBuilder()
                                .setRealm("android:ants-experiment")
                                .setIsExportRoot(true)
                                .build();
                mInvocationId = "u-" + randomUUIDString().toString();
                mRecorder =
                        createRecorderClient(
                                CreateInvocationRequest.newBuilder()
                                        .setInvocation(invocation)
                                        .setInvocationId(mInvocationId)
                                        .build(),
                                isStaging(context));
                mManageInvocation = true;

            } else {
                mDisable = true;
                CLog.i(
                        "ResultDBReporter is disabled as invocation ID or update token is not"
                                + " provided.");
                return;
            }
        } catch (RuntimeException e) {
            mDisable = true;
            CLog.e("Failed to create ResultDB client.");
            if (mRecorder != null) {
                // Make sure we cancel the client, otherwise it will leak a thread since
                // invocationEnded will be skipped.
                mRecorder.finalizeUpload();
                mTestArtifactHandler.setUploadQueueFinalized();
            }
            throw new RuntimeException(e);
        }
        // Variant contains properties in go/consistent-test-identifiers, excluding
        // properties in ResultDB test identifier.
        // TODO: Add Test definition properties eg. cluster_id.
        Variant.Builder mBaseVariantBuilder =
                Variant.newBuilder()
                        .putDef("scheduler", "ATP") // ATP is the only scheduler supported for now.
                        .putDef("name", Strings.nullToEmpty(context.getTestTag()));

        if (!context.getBuildInfos().isEmpty()) {
            IBuildInfo primaryBuild = context.getBuildInfos().get(0);
            mBaseVariantBuilder =
                    mBaseVariantBuilder
                            .putDef("build_provider", "androidbuild")
                            .putDef("branch", Strings.nullToEmpty(primaryBuild.getBuildBranch()))
                            .putDef("target", Strings.nullToEmpty(primaryBuild.getBuildFlavor()));
        }
        mBaseVariant = mBaseVariantBuilder.build();

        CLog.logAndDisplay(
                LogLevel.INFO, "Result link: %s", String.format(RESULT_LINK, mInvocationId));
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
        mRecorder.finalizeUpload();
        mTestArtifactHandler.setUploadQueueFinalized();
        if (mManageInvocation) {
            mRecorder.finalizeInvocation();
        }
        CLog.logAndDisplay(
                LogLevel.INFO, "Result link: %s", String.format(RESULT_LINK, mInvocationId));
        // TODO: Update ResultDB invocation with information from TF invocation.
    }

    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        if (mDisable) {
            return;
        }
        mWorkUnitHandler.testModuleStarted(moduleContext);
    }

    @Override
    public void testModuleEnded() {
        if (mDisable) {
            return;
        }
        mWorkUnitHandler.testModuleEnded();
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
                    mBaseVariant,
                    mWorkUnitHandler.getModuleVariant(),
                    mWorkUnitHandler.getCurrentModule(),
                    mRecorder);
        } catch (RuntimeException e) {
            CLog.e("error during skip reporting:");
            CLog.e(e);
        }
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
        // TODO: implement this method.
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber) {
        // TODO: implement this method.
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber, long startTime) {
        // TODO: implement this method.
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
                test,
                startTime,
                mWorkUnitHandler.getCurrentModule(),
                mBaseVariant,
                mWorkUnitHandler.getModuleVariant());
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
        mTestResultHandler.testAssumptionFailure(test, failure, mRecorder);
    }

    @Override
    public void testSkipped(TestDescription test, SkipReason reason) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testSkipped(test, reason, mRecorder);
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testFailed(test, FailureDescription.create(trace), mRecorder);
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        if (mDisable) {
            return;
        }
        mTestResultHandler.testFailed(test, failure, mRecorder);
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
        mTestResultHandler.testEnded(test, endTime, testMetrics, mRecorder);
    }

    @Override
    public boolean supportGranularResults() {
        return true;
    }

    public boolean isDisabled() {
        return mDisable;
    }
}
