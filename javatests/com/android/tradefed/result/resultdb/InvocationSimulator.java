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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestSummaryListener;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestSummary;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.util.MultiMap;

import com.google.errorprone.annotations.CanIgnoreReturnValue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Invocation simulator for testing. */
public class InvocationSimulator {

    public enum TestStatus {
        PASS("pass"),
        FAIL("fail"),
        IGNORED("ignored"),
        ASSUMPTION_FAILURE("assumptionFailure"),
        TEST_ERROR("testError"),
        TEST_SKIPPED("testSkipped");

        private final String value;

        private TestStatus(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    static class TestConfig {
        TestDescription description;
        TestStatus status;
        HashMap<String, MetricMeasurement.Metric> metrics;
        FailureDescription failure;
        List<LogFile> logs = new ArrayList<>();

        TestConfig(
                TestDescription description,
                TestStatus status,
                HashMap<String, MetricMeasurement.Metric> metrics,
                FailureDescription failure) {
            this.description = description;
            this.status = status;
            this.metrics = metrics;
            this.failure = failure;
        }
    }

    static class TestRunConfig {
        String name = "TestRun1";
        HashMap<String, MetricMeasurement.Metric> metrics = new HashMap<>();
        String error = "";
        FailureDescription failure = null;
        List<LogFile> logs = new ArrayList<>();
        List<TestConfig> tests = new ArrayList<>();

        TestRunConfig(String name) {
            this.name = name;
        }
    }

    static class ModuleConfig {
        String name;
        MultiMap<String, String> metadata = new MultiMap<>();
        Map<String, String> attributes = new HashMap<>();
        List<TestRunConfig> testRuns = new ArrayList<>();
        List<LogFile> logs = new ArrayList<>();

        ModuleConfig(String name) {
            this.name = name;
        }
    }

    private List<ModuleConfig> mModules = new ArrayList<>();
    private ModuleConfig mCurrentModule = null;
    private TestRunConfig mCurrentTestRun = null;

    private String mSummary = "";
    private Map<String, String> mInvocationAttributes = new HashMap<>();
    private List<IBuildInfo> mBuildInfos = new ArrayList<>();
    private List<LogFile> mTestLogs = new ArrayList<>();
    private List<LogFile> mInvocationLogs = new ArrayList<>();
    private boolean mIgnoreExceptions = false;

    public static InvocationSimulator create() {
        return new InvocationSimulator();
    }

    private InvocationSimulator() {
        mBuildInfos = new ArrayList<>();
        mTestLogs = new ArrayList<>();
        mInvocationLogs = new ArrayList<>();
        mInvocationAttributes = new HashMap<>();
    }

    public InvocationSimulator withBuildInfo(IBuildInfo info) {
        mBuildInfos.add(info);
        return this;
    }

    public InvocationSimulator withInvocationAttribute(String name, String value) {
        mInvocationAttributes.put(name, value);
        return this;
    }

    private IInvocationContext getModuleContext(ModuleConfig moduleConfig) {
        ConfigurationDescriptor module = new ConfigurationDescriptor();
        module.setModuleName(moduleConfig.name);
        if (!moduleConfig.metadata.isEmpty()) {
            module.setMetaData(moduleConfig.metadata);
        }
        InvocationContext moduleContext = createInvocation();
        moduleContext.setConfigurationDescriptor(module);
        for (Map.Entry<String, String> attribute : moduleConfig.attributes.entrySet()) {
            moduleContext.addInvocationAttribute(attribute.getKey(), attribute.getValue());
        }
        return moduleContext;
    }

    private long getStartTime() {
        String in = "2018-09-07 15:23:45";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        ZonedDateTime date = formatter.parse(in, LocalDateTime::from).atZone(ZoneId.of("UTC"));
        return date.toInstant().toEpochMilli();
    }

    private void ensureModule() {
        if (mCurrentModule == null) {
            withModule("example-module");
        }
    }

    private void ensureTestRun() {
        ensureModule();
        if (mCurrentTestRun == null) {
            withTestRun("TestRun1");
        }
    }

    public InvocationSimulator withTest(String clazz, String method) {
        return withTest(clazz, method, TestStatus.PASS);
    }

    public InvocationSimulator withTest(String clazz, String method, TestStatus outcome) {
        return withTest(clazz, method, outcome, new HashMap<String, MetricMeasurement.Metric>());
    }

    public InvocationSimulator withTestFailure(
            String clazz, String method, FailureDescription failure) {
        ensureTestRun();
        mCurrentTestRun.tests.add(
                new TestConfig(
                        new TestDescription(clazz, method),
                        TestStatus.FAIL,
                        new HashMap<>(),
                        failure));
        return this;
    }

    public InvocationSimulator withTest(
            String clazz,
            String method,
            TestStatus outcome,
            HashMap<String, MetricMeasurement.Metric> metrics) {
        ensureTestRun();
        mCurrentTestRun.tests.add(
                new TestConfig(new TestDescription(clazz, method), outcome, metrics, null));
        return this;
    }

    @CanIgnoreReturnValue
    public InvocationSimulator withModule(String moduleName) {
        mCurrentModule = new ModuleConfig(moduleName);
        mModules.add(mCurrentModule);
        mCurrentTestRun = null;
        return this;
    }

    @CanIgnoreReturnValue
    public InvocationSimulator withTestRun(String runName) {
        ensureModule();
        mCurrentTestRun = new TestRunConfig(runName);
        mCurrentModule.testRuns.add(mCurrentTestRun);
        return this;
    }

    public InvocationSimulator withModuleMetadata(String name, String value) {
        ensureModule();
        mCurrentModule.metadata.put(name, value);
        return this;
    }

    public InvocationSimulator withModuleAttribute(String name, String value) {
        ensureModule();
        mCurrentModule.attributes.put(name, value);
        return this;
    }

    public InvocationSimulator withoutModules() {
        mModules.clear();
        mCurrentModule = null;
        mCurrentTestRun = null;
        return this;
    }

    public InvocationSimulator withSummary(String summary) {
        mSummary = summary;
        return this;
    }

    public InvocationSimulator withTestRunFailure(String message) {
        ensureTestRun();
        mCurrentTestRun.error = message;
        return this;
    }

    public InvocationSimulator withTestRunFailure(FailureDescription failure) {
        ensureTestRun();
        mCurrentTestRun.failure = failure;
        return this;
    }

    public InvocationSimulator withTestLog(LogFile log) {
        mTestLogs.add(log);
        return this;
    }

    public InvocationSimulator withTestRunLog(LogFile log) {
        ensureTestRun();
        mCurrentTestRun.logs.add(log);
        return this;
    }

    public InvocationSimulator withModuleLog(LogFile log) {
        ensureModule();
        mCurrentModule.logs.add(log);
        return this;
    }

    public InvocationSimulator withInvocationLog(LogFile log) {
        mInvocationLogs.add(log);
        return this;
    }

    public InvocationSimulator ignoreExceptions() {
        mIgnoreExceptions = true;
        return this;
    }

    public <T extends ILogSaverListener & ITestSummaryListener> void simulateInvocation(
            T reporter) {
        if (!mSummary.isEmpty()) {
            reporter.putEarlySummary(Arrays.asList(new TestSummary(mSummary)));
        }
        try {
            reporter.invocationStarted(createInvocation());
        } catch (Exception e) {
            if (!mIgnoreExceptions) {
                throw e;
            }
        }

        for (ModuleConfig module : mModules) {
            try {
                if (!module.name.isEmpty()) {
                    reporter.testModuleStarted(getModuleContext(module));
                }
            } catch (Exception e) {
                if (!mIgnoreExceptions) {
                    throw e;
                }
            }

            for (TestRunConfig run : module.testRuns) {
                try {
                    reporter.testRunStarted(run.name, run.tests.size(), 1);
                } catch (Exception e) {
                    if (!mIgnoreExceptions) {
                        throw e;
                    }
                }

                simulateTests(reporter, run.tests);

                if (!run.error.isEmpty()) {
                    reporter.testRunFailed(run.error);
                }
                if (run.failure != null) {
                    reporter.testRunFailed(run.failure);
                }
                simulateLogs("test-run-", run.logs, reporter);
                reporter.testRunEnded(1000L, run.metrics);
            }

            try {
                if (!module.name.isEmpty()) {
                    simulateLogs("module-log-", module.logs, reporter);
                    reporter.testModuleEnded();
                }
            } catch (Exception e) {
                if (!mIgnoreExceptions) {
                    throw e;
                }
            }
        }

        simulateLogs("invocation-log-", mInvocationLogs, reporter);
        reporter.invocationEnded(100);
    }

    private InvocationContext createInvocation() {
        InvocationContext context = new InvocationContext();
        context.setTestTag("test tag");

        for (int i = 0; i < mBuildInfos.size(); i++) {
            context.addDeviceBuildInfo(String.format("device_%d", i), mBuildInfos.get(i));
        }
        for (Map.Entry<String, String> attr : mInvocationAttributes.entrySet()) {
            context.addInvocationAttribute(attr.getKey(), attr.getValue());
        }
        return context;
    }

    private <T extends ILogSaverListener & ITestSummaryListener> void simulateTests(
            T reporter, List<TestConfig> tests) {
        long startTime = getStartTime();
        for (int i = 0; i < tests.size(); i++) {
            TestConfig test = tests.get(i);
            reporter.testStarted(test.description, startTime);
            startTime += 100;
            if (test.failure != null) {
                reporter.testFailed(test.description, test.failure);
            } else {
                switch (test.status) {
                    case FAIL:
                        reporter.testFailed(test.description, "Fail Trace");
                        break;
                    case ASSUMPTION_FAILURE:
                        reporter.testAssumptionFailure(test.description, "Assumption Fail Trace");
                        break;
                    case IGNORED:
                        reporter.testIgnored(test.description);
                        break;
                    case TEST_SKIPPED:
                        reporter.testSkipped(
                                test.description,
                                new SkipReason("skip reason message", "skip trigger", "bugId"));
                        break;
                    default:
                        break;
                }
            }
            simulateLogs("test-log-", mTestLogs, reporter);
            reporter.testEnded(test.description, startTime, test.metrics);
            startTime += 100;
        }
    }

    private void simulateLogs(String prefix, List<LogFile> logs, ILogSaverListener reporter) {
        for (int i = 0; i < logs.size(); i++) {
            String dataName = String.format("%s%d", prefix, i);
            reporter.logAssociation(dataName, logs.get(i));
        }
    }
}
