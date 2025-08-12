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

import com.android.resultdb.proto.Artifact;
import com.android.resultdb.proto.CreateInvocationRequest;
import com.android.resultdb.proto.FailureReason;
import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.SkippedReason;
import com.android.resultdb.proto.StringPair;
import com.android.resultdb.proto.TestIdentifier;
import com.android.resultdb.proto.TestIdentifierBase;
import com.android.resultdb.proto.TestResult;
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
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.testtype.suite.SuiteTestFilter;
import com.android.tradefed.util.MultiMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Result reporter that uploads test results to ResultDB. */
@OptionClass(alias = "resultdb-reporter")
public class ResultDBReporter
        implements ITestSummaryListener,
                ILogSaverListener,
                ISupportGranularResults,
                IConfigurationReceiver {

    private static final String BUILD_ENV_INVOCATION_CONTEXT_KEY = "android-build-environment";

    private static final int MAX_SUMMARY_HTML_BYTES = 4096;

    private static final int MAX_ERROR_MESSAGE_BYTES = 1024;
    private static final int MAX_SKIP_REASON_MESSAGE_BYTES = 1024;

    // Link to redirect to the result page.
    private static final String RESULT_LINK = "https://ci.chromium.org/ui/inv/invocations/%s";

    // Set containing the allowed variant module parameter keys
    private static final Set<String> ALLOWED_MODULE_PARAMETERS =
            ImmutableSet.of(ModuleDefinition.MODULE_ABI, ModuleDefinition.MODULE_PARAMETERIZATION);
    // Tag name for the test mapping source
    private static final String TEST_MAPPING_TAG = "test_mapping_source";

    // Pattern to extract artifact id from logsaver URL.
    private static final Pattern LOGSAVER_PATTERN =
            Pattern.compile(
                    "https:\\/\\/android-build.googleplex.com\\/builds\\/.+\\/.+\\/.+\\/.+\\/tests\\/\\d+\\/(.+)");

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
    // Module level variant for test in the same test module.
    private Variant mModuleVariant;
    private String mCurrentModule;
    private IInvocationContext mModuleContext;
    private TestResult mCurrentTestResult;
    // Counter for generate test result ID.
    private AtomicInteger mResultCounter = new AtomicInteger(0);
    // Base for generate test result ID.
    private String mResultIdBase;
    // No more log associations can be added to the upload queue after this set to true.
    private boolean mUploadQueueFinalized = false;
    // A map from resource ID to TestArtifact.
    private Map<String, Artifact> mTestArtifacts = new ConcurrentHashMap<>();

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

        String resourceId = getAntsResourceId(logFile);
        if (resourceId.isEmpty()) {
            // Ignore logs with empty resource id.
            return;
        }
        Artifact.Builder artifactBuilder = Artifact.newBuilder().setArtifactId(resourceId);

        CLog.v(
                "Received association %s %s %s %s",
                dataName, resourceId, logFile.getPath(), logFile.getUrl());

        if (mCurrentTestResult != null) {
            TestIdentifier testId = mCurrentTestResult.getTestIdStructured();

            artifactBuilder
                    .setTestIdStructured(
                            TestIdentifierBase.newBuilder()
                                    .setModuleName(testId.getModuleName())
                                    .setModuleScheme(testId.getModuleScheme())
                                    .setCoarseName(testId.getCoarseName())
                                    .setFineName(testId.getFineName())
                                    .setCaseName(testId.getCaseName()))
                    .setResultId(mCurrentTestResult.getResultId());
        }
        mTestArtifacts.put(resourceId, artifactBuilder.build());
        CLog.v("Received association %s %s %s", dataName, logFile.getPath(), logFile.getUrl());
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
        CLog.v("Uploading storage path %s %s", resourceId, storagePath);
        Artifact artifact = mTestArtifacts.remove(resourceId);
        if (artifact == null) {
            // If the artifact is not found, it means the log association is not called before.
            // Upload the artifact to the top work unit.
            CLog.d(
                    "Artifact for resource ID %s not found, upload to the top work unit.",
                    resourceId);
            artifact = Artifact.newBuilder().setArtifactId(resourceId).build();
        }
        if (!storagePath.matches("^/bigstore/([^/]+)/.+$")) {
            return;
        }
        // The storage path is a valid bigstore path, set the artifact to with GCS URI.
        String contentType = logFile.getType().getContentType();
        // Convert storage path to GCS URI.
        String gcsUri = storagePath.replaceFirst("^/bigstore/", "gs://");
        artifact =
                artifact.toBuilder()
                        .setGcsUri(gcsUri)
                        .setContentType(contentType)
                        .setSizeBytes(logFile.getSize())
                        .build();

        if (mUploadQueueFinalized) {
            // If the upload queue is finalized, make block call to upload the artifact directly.
            CLog.d("Upload queue is finalized, has been called, upload the artifact directly.");
            mRecorder.uploadArtifact(artifact);
            return;
        }
        // Queue the artifact for upload.
        mRecorder.enqueueArtifact(artifact);
    }

    // Returns the AnTS artifact resource ID.
    private String getAntsResourceId(LogFile logFile) {
        // We need to decode the resourceId because the backend expects that it is unencoded,
        // but TF gives as the url that is already encoded.
        String resourceId = "";
        if (logFile.getUrl() == null) {
            resourceId = "";
        } else {
            Matcher m = LOGSAVER_PATTERN.matcher(logFile.getUrl());
            if (!m.find()) {
                resourceId = "";
            } else {
                try {
                    resourceId = new URI(m.group(1)).getPath();
                } catch (URISyntaxException e) {
                    // ignore
                }
            }
        }
        return resourceId;
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

    // Generate a random hexadecimal string of length 8.
    @VisibleForTesting
    String randomHexString() throws NoSuchAlgorithmException {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return ResultDBUtil.bytesToHex(bytes);
    }

    @Override
    public void invocationStarted(IInvocationContext context) {
        if (mDisable) {
            CLog.i("ResultDBReporter is disabled");
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
                mUploadQueueFinalized = true;
            }
            throw new RuntimeException(e);
        }
        try {
            mResultIdBase = this.randomHexString();
        } catch (NoSuchAlgorithmException e) {
            mDisable = true;
            CLog.e("Failed to generate random result ID base.");
            return;
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
        mUploadQueueFinalized = true;
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
        mModuleContext = moduleContext;
        // Extract module informations.
        mCurrentModule = moduleContext.getConfigurationDescriptor().getModuleName();
        mModuleVariant = getModuleVariant(moduleContext.getAttributes());
    }

    /*
     * Only module-abi and module-param are used in the variant, so filter other values.
     */
    private Variant getModuleVariant(MultiMap<String, String> properties) {
        Variant.Builder variantBuilder = Variant.newBuilder();
        for (Map.Entry<String, String> property : properties.entries()) {
            if (ALLOWED_MODULE_PARAMETERS.contains(property.getKey())) {
                variantBuilder.putDef(
                        ResultDBUtil.makeValidKey(property.getKey()), property.getValue());
            }
        }
        return variantBuilder.build();
    }

    @Override
    public void testModuleEnded() {
        // Clear module context.
        mModuleContext = null;
        mModuleVariant = null;
    }

    @Override
    public void testRunEnded(
            long elapsedTimeMillis, HashMap<String, MetricMeasurement.Metric> runMetrics) {
        if (mDisable) {
            return;
        }
        try {
            reportDemotedTests();
        } catch (RuntimeException e) {
            CLog.e("error during skip reporting:");
            CLog.e(e);
        }
    }

    private void reportDemotedTests() {
        String moduleAbi = null;
        String moduleName = null;
        if (mModuleContext != null) {
            moduleAbi = mModuleContext.getAttribute(ModuleDefinition.MODULE_ABI);
            moduleName = mModuleContext.getAttribute(ModuleDefinition.MODULE_NAME);
        }
        Variant.Builder variantBuilder = Variant.newBuilder();
        if (mModuleVariant != null) {
            variantBuilder = variantBuilder.mergeFrom(mModuleVariant);
        }
        if (mBaseVariant != null) {
            variantBuilder = variantBuilder.mergeFrom(mBaseVariant);
        }
        for (Entry<String, SkipReason> entry :
                mConfiguration.getSkipManager().getDemotedTests().entrySet()) {
            String key = entry.getKey();
            SuiteTestFilter filter = SuiteTestFilter.createFrom(key);
            // If module filter has an ABI, we expect the filter to match it to be applicable
            // not having it is a bit ambiguous so we default to report for all abi similar
            // to how filters are applied.
            if (filter.getAbi() != null && !filter.getAbi().equals(moduleAbi)) {
                continue;
            }
            if (!filter.getName().equals(moduleName)) {
                continue;
            }
            if (filter.getTest() == null) {
                continue;
            }
            TestDescription test = TestDescription.fromString(filter.getTest());
            // String skipReasonString = ResultDBUtil.testSkipReasonToString(entry.getValue(),
            // "\n");
            SkippedReason skippedReason =
                    SkippedReason.newBuilder()
                            .setKind(SkippedReason.Kind.DEMOTED)
                            .setReasonMessage(
                                    ResultDBUtil.truncateString(
                                            entry.getValue().getReason(),
                                            MAX_SKIP_REASON_MESSAGE_BYTES))
                            .build();
            // Some skip reason field doesn't have a dedicated field in ResultDB, put them in the
            // summary HTML field for now.
            String summaryHtml =
                    ResultDBUtil.truncateString(
                            ResultDBUtil.testSkipReasonToSummaryHtml(entry.getValue(), false),
                            MAX_SUMMARY_HTML_BYTES);
            TestResult currentTest =
                    TestResult.newBuilder()
                            .setTestIdStructured(
                                    ResultDBUtil.toTestIdentifier(
                                            mCurrentModule, variantBuilder.build(), test))
                            .setResultId(
                                    String.format(
                                            "%s-%05d",
                                            mResultIdBase, mResultCounter.incrementAndGet()))
                            .setStartTime(Timestamps.fromMillis(currentTimestamp()))
                            .setStatusV2(TestResult.Status.SKIPPED)
                            .setSkippedReason(skippedReason)
                            .setSummaryHtml(summaryHtml)
                            .build();
            mRecorder.enqueueTestResult(currentTest);
            CLog.d("Backfilled %s SKIPPED", currentTest);
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
        Variant.Builder variantBuilder = Variant.newBuilder();
        if (mModuleVariant != null) {
            variantBuilder = variantBuilder.mergeFrom(mModuleVariant);
        }
        if (mBaseVariant != null) {
            variantBuilder = variantBuilder.mergeFrom(mBaseVariant);
        }

        mCurrentTestResult =
                TestResult.newBuilder()
                        .setTestIdStructured(
                                ResultDBUtil.toTestIdentifier(
                                        mCurrentModule, variantBuilder.build(), test))
                        .setResultId(
                                String.format(
                                        "%s-%05d", mResultIdBase, mResultCounter.incrementAndGet()))
                        .setStartTime(Timestamps.fromMillis(startTime))
                        .setStatusV2(TestResult.Status.PASSED)
                        .build();
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
        if (mCurrentTestResult == null) {
            CLog.e("Received #testAssumptionFailure(%s) without a valid testStart before.", test);
            return;
        }

        SkippedReason skippedReason =
                SkippedReason.newBuilder()
                        .setKind(SkippedReason.Kind.SKIPPED_BY_TEST_BODY)
                        .setReasonMessage(
                                extractAndTruncateFirstLine(
                                        failure.getErrorMessage(), MAX_SKIP_REASON_MESSAGE_BYTES))
                        .build();
        mCurrentTestResult =
                mCurrentTestResult.toBuilder()
                        .setStatusV2(TestResult.Status.SKIPPED)
                        .setSkippedReason(skippedReason)
                        .build();
        // TODO: Full error message is too long to fit in any test result field.
        // Upload it as test artifact.
    }

    @Override
    public void testSkipped(TestDescription test, SkipReason reason) {
        if (mDisable) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testSkipped(%s) without a valid testStart before.", test);
            return;
        }

        String summaryHtml = ResultDBUtil.testSkipReasonToSummaryHtml(reason, true);

        // TODO: Skip reason can be too long to fit in any test result field.
        // Upload it as test artifact.

        mCurrentTestResult =
                mCurrentTestResult.toBuilder()
                        // Execution errored is used for tests that did not run to completion,
                        // because an infrastructure error precluded it from doing so.
                        .setStatusV2(TestResult.Status.EXECUTION_ERRORED)
                        // Put the skip reason in the summary HTML field, because the SkippedReason
                        // field and FailureReason failure can only be set when the status is
                        // SKIPPED or FAILED, respectively.
                        .setSummaryHtml(
                                ResultDBUtil.truncateString(summaryHtml, MAX_SUMMARY_HTML_BYTES))
                        .build();
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        if (mDisable) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testFailed(%s) without a valid testStart before.", test);
            return;
        }
        FailureReason.Error error =
                FailureReason.Error.newBuilder()
                        .setMessage(extractAndTruncateFirstLine(trace, MAX_ERROR_MESSAGE_BYTES))
                        .build();
        FailureReason failureReason =
                FailureReason.newBuilder()
                        .addErrors(error)
                        .setKind(FailureReason.Kind.ORDINARY)
                        .build();
        mCurrentTestResult =
                mCurrentTestResult.toBuilder()
                        .setFailureReason(failureReason)
                        .setStatusV2(TestResult.Status.FAILED)
                        .build();
        // TODO: extract local instruction from test description and set in ResultDB test result.
        // TODO: trace is too long to fit in any test result field. Upload it as test artifact.
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        if (mDisable) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testFailed(%s) without a valid testStart before.", test);
            return;
        }

        FailureReason.Kind kind = FailureReason.Kind.ORDINARY;
        if (failure.getFailureStatus() != null) {
            switch (failure.getFailureStatus()) {
                case TIMED_OUT:
                    kind = FailureReason.Kind.TIMEOUT;
                    break;
                case CANCELLED:
                case INFRA_FAILURE:
                case SYSTEM_UNDER_TEST_CRASHED:
                    kind = FailureReason.Kind.CRASH;
                    break;
                default:
                    kind = FailureReason.Kind.ORDINARY;
                    break;
            }
        }
        String truncatedFailureReason =
                extractAndTruncateFirstLine(failure.getErrorMessage(), MAX_ERROR_MESSAGE_BYTES);

        FailureReason failureReason =
                FailureReason.newBuilder()
                        .addErrors(
                                FailureReason.Error.newBuilder()
                                        .setMessage(truncatedFailureReason)
                                        .build())
                        .setKind(kind)
                        .build();

        TestResult.Builder testResultBuilder =
                mCurrentTestResult.toBuilder()
                        .setFailureReason(failureReason)
                        .setStatusV2(TestResult.Status.FAILED);

        // Set the TF error type in the summary HTML.
        if (failure.getFailureStatus() != null) {
            testResultBuilder.setSummaryHtml("TF error type: " + failure.getFailureStatus());
        }
        mCurrentTestResult = testResultBuilder.build();
        // TODO: extract local instruction from test description and set in ResultDB test result.
        // TODO: trace is too long to fit in any test result field. Upload it as test artifact.
    }

    @Override
    public void testIgnored(TestDescription test) {
        if (mDisable) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testIgnored(%s) without a valid testStart before.", test);
            return;
        }
        mCurrentTestResult =
                mCurrentTestResult.toBuilder()
                        .setStatusV2(TestResult.Status.SKIPPED)
                        .setSkippedReason(
                                SkippedReason.newBuilder()
                                        .setKind(SkippedReason.Kind.DISABLED_AT_DECLARATION))
                        .build();
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
        long startTimeMillis = Timestamps.toMillis(mCurrentTestResult.getStartTime());
        TestResult.Builder testResultBuilder =
                mCurrentTestResult.toBuilder()
                        .setDuration(Durations.fromMillis(endTime - startTimeMillis));

        // Add test mapping sources to test result as tags.
        if (testMetrics.get(TEST_MAPPING_TAG) != null) {
            // Get Test Mapping sources from string formatting with list such as "[path1, path2]".
            // Note: Some test mapping sources may not be recorded. This is because a test module
            // can be defined across multiple TEST_MAPPING files, and TF doesn't run it again if
            // it's passed in the previous run.
            String testMappingMeasurement =
                    testMetrics
                            .get(TEST_MAPPING_TAG)
                            .getMeasurements()
                            .getSingleString()
                            .replaceAll("^\\[| |\\]$", "");
            List<String> testMappingSources = Arrays.asList(testMappingMeasurement.split(","));

            for (String testMappingSource : testMappingSources) {
                testResultBuilder.addTags(
                        StringPair.newBuilder()
                                .setKey(ResultDBUtil.makeValidKey(TEST_MAPPING_TAG))
                                .setValue(testMappingSource));
            }
        }
        mCurrentTestResult = testResultBuilder.build();
        mRecorder.enqueueTestResult(mCurrentTestResult);
        mCurrentTestResult = null;
    }

    @Override
    public boolean supportGranularResults() {
        return true;
    }

    /**
     * Extract the first line of the stack trace as the error message, and truncate the string to
     * the given max bytes.
     *
     * <p>In most cases, this ends up being the exception + error message.
     */
    String extractAndTruncateFirstLine(String trace, int maxBytes) {
        String firstLine = trace.split("[\\r\\n]+", 2)[0];
        if (!firstLine.trim().isEmpty()) {
            return ResultDBUtil.truncateString(firstLine, maxBytes);
        }
        return "";
    }
}
