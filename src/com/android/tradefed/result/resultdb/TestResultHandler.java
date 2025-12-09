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
import com.android.resultdb.proto.FailureReason;
import com.android.resultdb.proto.ModuleIdentifier;
import com.android.resultdb.proto.SkippedReason;
import com.android.resultdb.proto.StringPair;
import com.android.resultdb.proto.TestResult;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.testtype.suite.SuiteTestFilter;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

/** Handles individual Test level states and test result reporting for ResultDB. */
class TestResultHandler {

    private static final int MAX_SUMMARY_HTML_BYTES = 4096;
    private static final int MAX_ERROR_MESSAGE_BYTES = 1024;
    private static final int MAX_ERROR_TRACE_BYTES = 4096;
    private static final int MAX_SKIP_REASON_MESSAGE_BYTES = 1024;
    // Tag name for the test mapping source
    private static final String TEST_MAPPING_TAG = "test_mapping_source";

    private TestResult mCurrentTestResult;
    // Counter for generate test result ID.
    private AtomicInteger mResultCounter = new AtomicInteger(0);
    // Base string for generate test result ID.
    private String mResultIdBase = randomHexString();

    // Generate a random hexadecimal string of length 8.
    @VisibleForTesting
    String randomHexString() {
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            byte[] bytes = new byte[4];
            random.nextBytes(bytes);
            return ResultDBUtil.bytesToHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // This should not happen as SHA1PRNG is a standard algorithm.
            throw new AssertionError(e);
        }
    }

    public void testStarted(
            TestDescription test, long startTime, ModuleIdentifier moduleIdentifier) {
        mCurrentTestResult =
                TestResult.newBuilder()
                        .setTestIdStructured(
                                ResultDBUtil.toTestIdentifier(
                                        moduleIdentifier.getModuleName(),
                                        moduleIdentifier.getModuleVariant(),
                                        test))
                        .setResultId(
                                String.format(
                                        "%s-%05d", mResultIdBase, mResultCounter.incrementAndGet()))
                        .setStartTime(Timestamps.fromMillis(startTime))
                        .setStatusV2(TestResult.Status.PASSED)
                        .build();
    }

    public void testEnded(
            TestDescription test,
            long endTime,
            HashMap<String, MetricMeasurement.Metric> testMetrics,
            String workUnitId,
            IRecorderClientLegacy recorderLegacy,
            IRecorderClient recorder) {
        if (mCurrentTestResult == null) {
            CLog.e("Received #testEnded(%s) without a valid testStart before.", test);
            return;
        }
        ;

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
            for (String source : testMappingSources) {
                testResultBuilder.addTags(
                        StringPair.newBuilder()
                                .setKey(ResultDBUtil.makeValidKey(TEST_MAPPING_TAG))
                                .setValue(source));
            }
        }

        mCurrentTestResult = testResultBuilder.build();
        if (recorderLegacy != null) {
            recorderLegacy.enqueueTestResult(mCurrentTestResult);
        }
        if (recorder != null) {
            if (Strings.isNullOrEmpty(workUnitId)) {
                CLog.e("logic error: test ended %s without a valid work unit id.", test.toString());
            } else {
                // TODO(b/458246392): determine if it is possible for a test to start outside a
                // module and a test run, in such case the work unit id is the top work unit passed
                // to this tradefed invocation. ResultDB will reject the test result because of
                // missing module_id in the parent work unit. If this is possible, find a suitable
                // workaround.
                recorder.enqueueTestResult(workUnitId, mCurrentTestResult);
            }
        }
        mCurrentTestResult = null;
    }

    public void testFailed(
            TestDescription test,
            FailureDescription failure,
            String workUnitId,
            IRecorderClientLegacy recorderLegacy,
            IRecorderClient recorder) {
        if (mCurrentTestResult == null) {
            CLog.e("Received #testFailed(%s) without a valid testStart before.", test);
            return;
        }

        String trace = failure.toString();
        String truncatedFailureReason = extractAndTruncateFirstLine(trace, MAX_ERROR_MESSAGE_BYTES);
        ResultDBUtil.TruncationResult truncationResult =
                ResultDBUtil.truncateString(trace, MAX_ERROR_TRACE_BYTES);

        FailureStatus status = failure.getFailureStatus();
        FailureReason.Kind kind = FailureReason.Kind.ORDINARY;
        if (status != null) {
            switch (status) {
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

        FailureReason reason =
                FailureReason.newBuilder()
                        .setKind(kind)
                        .addErrors(
                                FailureReason.Error.newBuilder()
                                        .setMessage(truncatedFailureReason)
                                        .setTrace(truncationResult.getTruncatedString())
                                        .build())
                        .build();

        TestResult.Builder testResultBuilder =
                mCurrentTestResult.toBuilder()
                        .setFailureReason(reason)
                        .setStatusV2(TestResult.Status.FAILED);

        if (status != null && status != FailureStatus.UNSET) {
            testResultBuilder.setSummaryHtml("TF error type: " + status);
        }
        mCurrentTestResult = testResultBuilder.build();

        if (truncationResult.wasTruncated()) {
            // Upload the full trace as test artifact.
            Artifact atf = artifactFromString("testFailed_trace", trace);
            if (recorderLegacy != null) {
                recorderLegacy.enqueueArtifact(atf);
            }
            if (recorder != null) {
                recorder.enqueueArtifact(workUnitId, atf);
            }
        }
        // TODO: extract local instruction from test description and set in ResultDB test result.

    }

    public void testSkipped(
            TestDescription test,
            SkipReason reason,
            String workUnitId,
            IRecorderClientLegacy recorderLegacy,
            IRecorderClient recorder) {
        if (mCurrentTestResult == null) {
            CLog.e("Received #testSkipped(%s) without a valid testStart before.", test);
            return;
        }

        String summaryHtml = ResultDBUtil.testSkipReasonToSummaryHtml(reason, true);
        ResultDBUtil.TruncationResult truncated =
                ResultDBUtil.truncateString(summaryHtml, MAX_SUMMARY_HTML_BYTES);

        mCurrentTestResult =
                mCurrentTestResult.toBuilder()
                        // Execution errored is used for tests that did not run to completion,
                        // because an infrastructure error precluded it from doing so.
                        .setStatusV2(TestResult.Status.EXECUTION_ERRORED)
                        // Put the skip reason in the summary HTML field, because the SkippedReason
                        // field and FailureReason failure can only be set when the status is
                        // SKIPPED or FAILED, respectively.
                        .setSummaryHtml(truncated.getTruncatedString())
                        .build();
        if (truncated.wasTruncated()) {
            // Upload the full skip reason as test artifact.
            Artifact atf = artifactFromString("testSkipped_reason", summaryHtml);
            if (recorderLegacy != null) {
                recorderLegacy.enqueueArtifact(atf);
            }
            if (recorder != null) {
                recorder.enqueueArtifact(workUnitId, atf);
            }
        }
    }

    public void testAssumptionFailure(
            TestDescription test,
            FailureDescription failure,
            String workUnitId,
            IRecorderClientLegacy recorderLegacy,
            IRecorderClient recorder) {
        if (mCurrentTestResult == null) {
            CLog.e("Received #testAssumptionFailure(%s) without a valid testStart before.", test);
            return;
        }

        // Put first line of error message in the skip reason field because Assumption Failure maps
        // to ResultDB SKIPPED status.
        SkippedReason skippedReason =
                SkippedReason.newBuilder()
                        .setKind(SkippedReason.Kind.SKIPPED_BY_TEST_BODY)
                        .setReasonMessage(
                                extractAndTruncateFirstLine(
                                        failure.getErrorMessage(), MAX_SKIP_REASON_MESSAGE_BYTES))
                        .build();

        // Put the full error message in the summary HTML field.
        ResultDBUtil.TruncationResult truncated =
                ResultDBUtil.truncateString(failure.getErrorMessage(), MAX_SUMMARY_HTML_BYTES);

        mCurrentTestResult =
                mCurrentTestResult.toBuilder()
                        .setStatusV2(TestResult.Status.SKIPPED)
                        .setSkippedReason(skippedReason)
                        .setSummaryHtml(truncated.getTruncatedString())
                        .build();

        if (truncated.wasTruncated()) {
            // Upload the full skip reason as test artifact if it was truncated.
            Artifact atf =
                    artifactFromString("testAssumptionFailure_reason", failure.getErrorMessage());
            if (recorderLegacy != null) {
                recorderLegacy.enqueueArtifact(atf);
            }
            if (recorder != null) {
                recorder.enqueueArtifact(workUnitId, atf);
            }
        }
    }

    public void testIgnored(TestDescription test) {
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

    public TestResult getCurrentTestResult() {
        return mCurrentTestResult;
    }

    /**
     * Extract the first line of the stack trace as the error message, and truncate the string to
     * the given max bytes.
     *
     * <p>In most cases, this ends up being the exception + error message.
     */
    private String extractAndTruncateFirstLine(String trace, int maxBytes) {
        if (Strings.isNullOrEmpty(trace)) {
            return trace;
        }
        String firstLine = trace.split("[\\r\\n]+", 2)[0];
        if (!firstLine.trim().isEmpty()) {
            return ResultDBUtil.truncateString(firstLine, maxBytes).getTruncatedString();
        }
        return "";
    }

    /**
     * Create an artifact from the given string.
     *
     * <p>The artifact will be associated with the current test result if it is not null.
     */
    private Artifact artifactFromString(String artifactId, String contents) {
        ByteString contentsBytes = ByteString.copyFromUtf8(contents);
        Artifact.Builder ab =
                Artifact.newBuilder()
                        .setArtifactId(artifactId)
                        .setContentType("text/plain")
                        .setContents(contentsBytes)
                        .setSizeBytes(contentsBytes.size());
        if (mCurrentTestResult != null) {
            ab.setTestIdStructured(mCurrentTestResult.getTestIdStructured())
                    .setResultId(mCurrentTestResult.getResultId());
        }
        return ab.build();
    }

    public void reportDemotedTests(
            IConfiguration configuration,
            IInvocationContext moduleContext,
            long currentTimestamp,
            ModuleIdentifier moduleIdentifier,
            String workUnitId,
            IRecorderClientLegacy recorderLegacy,
            IRecorderClient recorder) {
        String moduleAbi = null;
        String moduleName = null;
        if (moduleContext != null) {
            moduleAbi = moduleContext.getAttribute(ModuleDefinition.MODULE_ABI);
            moduleName = moduleContext.getAttribute(ModuleDefinition.MODULE_NAME);
        }
        for (Entry<String, SkipReason> entry :
                configuration.getSkipManager().getDemotedTests().entrySet()) {
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
            // Skip reason of demoted test is not likely to exceed the size limit.
            // But we truncate it just in case.
            ResultDBUtil.TruncationResult truncatedSkipReason =
                    ResultDBUtil.truncateString(
                            entry.getValue().getReason(), MAX_SKIP_REASON_MESSAGE_BYTES);
            SkippedReason skippedReason =
                    SkippedReason.newBuilder()
                            .setKind(SkippedReason.Kind.DEMOTED)
                            .setReasonMessage(truncatedSkipReason.getTruncatedString())
                            .build();
            // Some skip reason field doesn't have a dedicated field in ResultDB, put them in the
            // summary HTML field for now. This is not likely to exceed the size limit.
            // But we truncate it just in case.
            String summaryHtml =
                    ResultDBUtil.truncateString(
                                    ResultDBUtil.testSkipReasonToSummaryHtml(
                                            entry.getValue(), false),
                                    MAX_SUMMARY_HTML_BYTES)
                            .getTruncatedString();
            TestResult currentTest =
                    TestResult.newBuilder()
                            .setTestIdStructured(
                                    ResultDBUtil.toTestIdentifier(
                                            moduleIdentifier.getModuleName(),
                                            moduleIdentifier.getModuleVariant(),
                                            test))
                            .setResultId(
                                    String.format(
                                            "%s-%05d",
                                            mResultIdBase, mResultCounter.incrementAndGet()))
                            .setStartTime(Timestamps.fromMillis(currentTimestamp))
                            .setStatusV2(TestResult.Status.SKIPPED)
                            .setSkippedReason(skippedReason)
                            .setSummaryHtml(summaryHtml)
                            .build();
            if (recorderLegacy != null) {
                recorderLegacy.enqueueTestResult(currentTest);
            }
            if (recorder != null) {
                if (Strings.isNullOrEmpty(workUnitId)) {
                    CLog.e(
                            "logic error: reportDemotedTests %s without a valid work unit id.",
                            test.toString());
                    return;
                }
                recorder.enqueueTestResult(workUnitId, currentTest);
            }
        }
    }
}
