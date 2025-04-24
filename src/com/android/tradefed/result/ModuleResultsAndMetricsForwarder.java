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

package com.android.tradefed.result;

import com.android.tradefed.invoker.logger.CurrentInvocation.IsolationGrade;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.result.proto.TestRecordProto;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An Extension of {@link ResultAndLogForwarder} that adds extra module related metrics to the test
 * results.
 */
public class ModuleResultsAndMetricsForwarder extends ResultAndLogForwarder {
    /** Track if we are within an isolated run or not */
    private IsolationGrade mAttemptIsolation = IsolationGrade.NOT_ISOLATED;

    private List<String> mTestMappingSources = new ArrayList<>();
    private static final String TEST_MAPPING_SOURCE = "test_mapping_source";
    private String mModuleId = null;
    private boolean mSkip = false;
    private int mTestCount;
    private int mTestsRan;
    private String mCurrentRunName;
    private boolean mTestRunFailed = false;

    public ModuleResultsAndMetricsForwarder(ITestInvocationListener... listeners) {
        super(listeners);
    }

    /** Sets whether or not the attempt should be reported as isolated. */
    public void setAttemptIsolation(IsolationGrade isolation) {
        mAttemptIsolation = isolation;
    }

    /** Sets test-mapping sources that will be inserted into metrics. */
    public void setTestMappingSources(List<String> testMappingSources) {
        mTestMappingSources = testMappingSources;
    }

    public void setModuleId(String moduleId) {
        mModuleId = moduleId;
    }

    /** Whether or not to mark all the test cases skipped. */
    public void setMarkTestsSkipped(boolean skip) {
        mSkip = skip;
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber) {
        testRunStarted(mModuleId, testCount, attemptNumber, System.currentTimeMillis());
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber, long startTime) {
        super.testRunStarted(mModuleId, testCount, attemptNumber, startTime);
        mTestRunFailed = false;
        mCurrentRunName = runName;
        mTestCount = testCount;
    }

    @Override
    public void testStarted(TestDescription test) {
        testStarted(test, System.currentTimeMillis());
    }

    @Override
    public void testStarted(TestDescription test, long startTime) {
        super.testStarted(test, startTime);
        mTestsRan++;
        if (mSkip) {
            super.testIgnored(test);
        }
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        if (mSkip) {
            return;
        }
        super.testFailed(test, trace);
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        if (mSkip) {
            return;
        }
        super.testFailed(test, failure);
    }

    @Override
    public void testEnded(
            TestDescription test, HashMap<String, MetricMeasurement.Metric> testMetrics) {
        testEnded(test, System.currentTimeMillis(), testMetrics);
    }

    @Override
    public void testEnded(
            TestDescription test,
            long endTime,
            HashMap<String, MetricMeasurement.Metric> testMetrics) {
        if (!mTestMappingSources.isEmpty()) {
            testMetrics.put(
                    TEST_MAPPING_SOURCE,
                    TfMetricProtoUtil.stringToMetric(mTestMappingSources.toString()));
        }
        super.testEnded(test, endTime, testMetrics);
    }

    @Override
    public void testRunFailed(String errorMessage) {
        super.testRunFailed(errorMessage);
        mTestRunFailed = true;
    }

    @Override
    public void testRunFailed(FailureDescription failure) {
        super.testRunFailed(failure);
        mTestRunFailed = true;
    }

    @Override
    public void testRunEnded(
            long elapsedTime, HashMap<String, MetricMeasurement.Metric> runMetrics) {
        if (!mTestRunFailed && (mTestsRan != mTestCount)) {
            String error =
                    String.format(
                            "TestRun %s only ran %d out of %d expected tests.",
                            mCurrentRunName, mTestsRan, mTestCount);
            FailureDescription mismatch =
                    FailureDescription.create(error)
                            .setFailureStatus(TestRecordProto.FailureStatus.TEST_FAILURE)
                            .setErrorIdentifier(InfraErrorIdentifier.EXPECTED_TESTS_MISMATCH);
            CLog.e(error);
            super.testRunFailed(mismatch);
        }
        if (!IsolationGrade.NOT_ISOLATED.equals(mAttemptIsolation)) {
            runMetrics.put(
                    "run-isolated", TfMetricProtoUtil.stringToMetric(mAttemptIsolation.toString()));
            // In case something was off, reset isolation.
            mAttemptIsolation = IsolationGrade.NOT_ISOLATED;
        }
        super.testRunEnded(elapsedTime, runMetrics);
        mTestRunFailed = false;
        mTestCount = 0;
        mTestsRan = 0;
        mCurrentRunName = null;
    }

    @Override
    public void testRunEnded(long elapsedTimeMillis, Map<String, String> runMetrics) {
        if (!mTestRunFailed && (mTestsRan != mTestCount)) {
            String error =
                    String.format(
                            "TestRun %s only ran %d out of %d expected tests.",
                            mCurrentRunName, mTestsRan, mTestCount);
            FailureDescription mismatch =
                    FailureDescription.create(error)
                            .setFailureStatus(TestRecordProto.FailureStatus.TEST_FAILURE)
                            .setErrorIdentifier(InfraErrorIdentifier.EXPECTED_TESTS_MISMATCH);
            CLog.e(error);
            super.testRunFailed(mismatch);
        }
        super.testRunEnded(elapsedTimeMillis, runMetrics);
        mTestRunFailed = false;
        mTestCount = 0;
        mTestsRan = 0;
        mCurrentRunName = null;
    }
}
