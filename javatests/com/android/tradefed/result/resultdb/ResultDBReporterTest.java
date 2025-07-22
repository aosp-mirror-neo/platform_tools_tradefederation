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

import static com.google.common.truth.Truth.assertThat;

import com.android.resultdb.proto.Artifact;
import com.android.resultdb.proto.CreateInvocationRequest;
import com.android.resultdb.proto.FailureReason;
import com.android.resultdb.proto.SkippedReason;
import com.android.resultdb.proto.TestIdentifier;
import com.android.resultdb.proto.TestIdentifierBase;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.Variant;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.proto.TestRecordProto;
import com.android.tradefed.result.skipped.SkipManager;
import com.android.tradefed.testtype.suite.ModuleDefinition;

import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RunWith(JUnit4.class)
public class ResultDBReporterTest {

    private InvocationSimulator mSimulator;
    private ResultDBReporterTester mReporter;
    private ConfigurationTester mConfig;
    private OptionSetter mOption;

    private class ResultDBReporterTester extends ResultDBReporter {

        public StubClient mRecorder = StubClient.create();

        public ResultDBReporterTester() {
            super();
        }

        @Override
        IRecorderClient createRecorderClient(
                String invocationId, String updateToken, Boolean isStaging) {
            return mRecorder;
        }

        @Override
        IRecorderClient createRecorderClient(CreateInvocationRequest request, Boolean isStaging) {
            return mRecorder;
        }

        @Override
        long currentTimestamp() {
            return 1000000000L;
        }

        @Override
        String randomUUIDString() {
            return "result_id";
        }

        @Override
        String randomHexString() {
            return "1234abcd";
        }
    }

    private class ConfigurationTester extends Configuration {

        SkipManager mSkipManager = new SkipManager();

        public ConfigurationTester(String name, String description) {
            super(name, description);
        }

        public void withDemotionFilters() throws ConfigurationException {
            OptionSetter setter = new OptionSetter(mSkipManager);
            setter.setOptionValue(
                    "demotion-filters",
                    "x86 module-name testClassSkipped#test-name-skipped",
                    "test-skip-reason");
            mSkipManager.setup(this, new InvocationContext());
        }

        @Override
        public SkipManager getSkipManager() {
            return mSkipManager;
        }
    }

    private TestResult.Builder newTestResult(String method) {
        return TestResult.newBuilder()
                .setTestIdStructured(
                        TestIdentifier.newBuilder()
                                .setModuleName("example-module")
                                .setModuleScheme("junit")
                                .setModuleVariant(
                                        Variant.newBuilder()
                                                .putDef("name", "test tag")
                                                .putDef("scheduler", "ATP")
                                                .build())
                                .setCoarseName("com.google")
                                .setFineName("ExampleClass")
                                .setCaseName(method)
                                .build())
                .setResultId("1234abcd-00001")
                .setStartTime(Timestamps.fromSeconds(1536333825L))
                .setDuration(Durations.fromMillis(100))
                .setStatusV2(TestResult.Status.PASSED);
    }

    @Before
    public void setUp() throws ConfigurationException {
        mReporter = new ResultDBReporterTester();
        mSimulator = InvocationSimulator.create().withModule("example-module");
        mConfig = new ConfigurationTester("name", "description");
        mReporter.setConfiguration(mConfig);
        mOption = new OptionSetter(mReporter);
    }

    @Test
    public void noInvocationIdInInvocationContext_reporterDisabled() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults()).isEmpty();
    }

    @Test
    public void createLocalInvocation_invocationCreated() throws Exception {
        mOption.setOptionValue("create-local-invocation", "true");
        mSimulator
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(newTestResult("testExampleMethod").build());
    }

    @Test
    public void uploadFunctionalResults() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(newTestResult("testExampleMethod").build());
    }

    @Test
    public void uploadResultWithVariant() {
        BuildInfo info = new BuildInfo("1", "target_1");
        info.setBuildBranch("test-branch");
        info.setBuildFlavor("test-flavor");
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .withModuleAttribute("should-ignore", "ignore")
                .withModuleAttribute(ModuleDefinition.MODULE_ABI, "test-abi")
                .withModuleAttribute(
                        ModuleDefinition.MODULE_PARAMETERIZATION, "test-parameterization")
                .withBuildInfo(info)
                .simulateInvocation(mReporter);

        Variant variant =
                Variant.newBuilder()
                        .putDef("branch", "test-branch")
                        .putDef("build_provider", "androidbuild")
                        .putDef("module_abi", "test-abi")
                        .putDef("module_param", "test-parameterization")
                        .putDef("name", "test tag")
                        .putDef("scheduler", "ATP")
                        .putDef("target", "test-flavor")
                        .build();
        TestResult.Builder expected = newTestResult("testExampleMethod");
        expected.setTestIdStructured(
                expected.getTestIdStructured().toBuilder().setModuleVariant(variant).build());
        assertThat(mReporter.mRecorder.getTestResults()).containsExactly(expected.build());
    }

    @Test
    public void uploadVariantResultStatus() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest(
                        "com.google.ExampleClass",
                        "testMethodPassed",
                        InvocationSimulator.TestStatus.PASS)
                .withTest(
                        "com.google.ExampleClass",
                        "testMethodFailed",
                        InvocationSimulator.TestStatus.FAIL)
                .withTest(
                        "com.google.ExampleClass",
                        "testMethodAssumption",
                        InvocationSimulator.TestStatus.ASSUMPTION_FAILURE)
                .withTest(
                        "com.google.ExampleClass",
                        "testMethodIgnored",
                        InvocationSimulator.TestStatus.IGNORED)
                .withTest(
                        "com.google.ExampleClass",
                        "testMethodSkip",
                        InvocationSimulator.TestStatus.TEST_SKIPPED)
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(
                        newTestResult("testMethodPassed")
                                .setResultId("1234abcd-00001")
                                .setStatusV2(TestResult.Status.PASSED)
                                .build(),
                        newTestResult("testMethodFailed")
                                .setResultId("1234abcd-00002")
                                .setStatusV2(TestResult.Status.FAILED)
                                .setStartTime(Timestamps.fromMillis(1536333825200L))
                                .setFailureReason(
                                        FailureReason.newBuilder()
                                                .setKind(FailureReason.Kind.ORDINARY)
                                                .addErrors(
                                                        FailureReason.Error.newBuilder()
                                                                .setMessage("Fail Trace")))
                                .build(),
                        newTestResult("testMethodAssumption")
                                .setResultId("1234abcd-00003")
                                .setStatusV2(TestResult.Status.SKIPPED)
                                .setStartTime(Timestamps.fromMillis(1536333825400L))
                                .setSkippedReason(
                                        SkippedReason.newBuilder()
                                                .setKind(SkippedReason.Kind.SKIPPED_BY_TEST_BODY)
                                                .setReasonMessage("Assumption Fail Trace"))
                                .build(),
                        newTestResult("testMethodIgnored")
                                .setResultId("1234abcd-00004")
                                .setStatusV2(TestResult.Status.SKIPPED)
                                .setSkippedReason(
                                        SkippedReason.newBuilder()
                                                .setKind(
                                                        SkippedReason.Kind.DISABLED_AT_DECLARATION))
                                .setStartTime(Timestamps.fromMillis(1536333825600L))
                                .build(),
                        newTestResult("testMethodSkip")
                                .setResultId("1234abcd-00005")
                                .setStatusV2(TestResult.Status.EXECUTION_ERRORED)
                                .setStartTime(Timestamps.fromMillis(1536333825800L))
                                .setSummaryHtml(
                                        "bug_id: bugId<br>trigger: skip trigger<br>reason: skip"
                                                + " reason message<br>")
                                .build());
    }

    @Test
    public void testWithFailureDescription_failureStatus_testFailure() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTestFailure(
                        "com.google.ExampleClass",
                        "testMethodFailed",
                        FailureDescription.create(
                                "Failure Message", TestRecordProto.FailureStatus.TEST_FAILURE))
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(
                        newTestResult("testMethodFailed")
                                .setStatusV2(TestResult.Status.FAILED)
                                .setResultId("1234abcd-00001")
                                .setFailureReason(
                                        FailureReason.newBuilder()
                                                .setKind(FailureReason.Kind.ORDINARY)
                                                .addErrors(
                                                        FailureReason.Error.newBuilder()
                                                                .setMessage("Failure Message")))
                                .setSummaryHtml("TF error type: TEST_FAILURE")
                                .build());
    }


    @Test
    public void testWithFailureDescription_failureStatus_crashed() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTestFailure(
                        "com.google.ExampleClass",
                        "testMethodCrashed",
                        FailureDescription.create(
                                "Failure Message\n java.lang.RuntimeException: Failure Message",
                                TestRecordProto.FailureStatus.SYSTEM_UNDER_TEST_CRASHED))
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(
                        newTestResult("testMethodCrashed")
                                .setStatusV2(TestResult.Status.FAILED)
                                .setFailureReason(
                                        FailureReason.newBuilder()
                                                .setKind(FailureReason.Kind.CRASH)
                                                .addErrors(
                                                        FailureReason.Error.newBuilder()
                                                                .setMessage("Failure Message")))
                                .setSummaryHtml("TF error type: SYSTEM_UNDER_TEST_CRASHED")
                                .build());
    }


    @Test
    public void testWithFailureDescription_failureStatus_timeout() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTestFailure(
                        "com.google.ExampleClass",
                        "testMethodTimedOut",
                        FailureDescription.create(
                                "Failure Message", TestRecordProto.FailureStatus.TIMED_OUT))
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(
                        newTestResult("testMethodTimedOut")
                                .setStatusV2(TestResult.Status.FAILED)
                                .setFailureReason(
                                        FailureReason.newBuilder()
                                                .setKind(FailureReason.Kind.TIMEOUT)
                                                .addErrors(
                                                        FailureReason.Error.newBuilder()
                                                                .setMessage("Failure Message")))
                                .setSummaryHtml("TF error type: TIMED_OUT")
                                .build());
    }

    @Test
    public void shouldUploadDemotedTests() throws ConfigurationException {
        mConfig.withDemotionFilters();
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withModuleAttribute(ModuleDefinition.MODULE_ABI, "x86")
                .withModuleAttribute(ModuleDefinition.MODULE_NAME, "module-name")
                .simulateInvocation(mReporter);
        Variant variant =
                Variant.newBuilder()
                        .putDef("module_abi", "x86")
                        .putDef("name", "test tag")
                        .putDef("scheduler", "ATP")
                        .build();

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(
                        TestResult.newBuilder()
                                .setTestIdStructured(
                                        TestIdentifier.newBuilder()
                                                .setModuleName("example-module")
                                                .setModuleScheme("junit")
                                                .setModuleVariant(variant)
                                                .setCoarseName("empty-package")
                                                .setFineName("testClassSkipped")
                                                .setCaseName("test-name-skipped")
                                                .build())
                                .setResultId("1234abcd-00001")
                                .setStartTime(Timestamps.fromSeconds(1000000L))
                                .setStatusV2(TestResult.Status.SKIPPED)
                                .setSkippedReason(
                                        SkippedReason.newBuilder()
                                                .setKind(SkippedReason.Kind.DEMOTED)
                                                .setReasonMessage("test-skip-reason"))
                                .setSummaryHtml("trigger: UNKNOWN_TRIGGER<br>")
                                .build());
    }

    @Test
    public void uploadTestArtifacts() {
        LogFile testLog = new LogFile("/tmp/test.log", getLogUrl("test.log"), LogDataType.TEXT);
        LogFile testRunLog =
                new LogFile("/tmp/test-run.log", getLogUrl("test-run.log"), LogDataType.TEXT);
        LogFile invocationLog =
                new LogFile("/tmp/invocation.log", getLogUrl("invocation.log"), LogDataType.PNG);
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .withTestLog(testLog)
                .withTestRunLog(testRunLog)
                .withInvocationLog(invocationLog)
                .simulateInvocation(mReporter);
        // simulate the updateStoragePath called from AnTS Result reporter.
        mReporter.uploadStoragePath("18274/stub/inv_9000/test.log_9000.dat", "/bigstore/test/test.log");
        mReporter.uploadStoragePath(
                "18274/stub/inv_9000/test-run.log_9000.dat", "/bigstore/test/test-run.log");
        mReporter.uploadStoragePath(
                "18274/stub/inv_9000/invocation.log_9000.dat", "/bigstore/test/invocation.log");

        assertThat(mReporter.mRecorder.getArtifacts())
                .containsExactly(
                        Artifact.newBuilder()
                                .setArtifactId("18274/stub/inv_9000/test.log_9000.dat")
                                .setContentType("text/plain")
                                .setTestIdStructured(
                                        TestIdentifierBase.newBuilder()
                                                .setModuleName("example-module")
                                                .setModuleScheme("junit")
                                                .setCoarseName("com.google")
                                                .setFineName("ExampleClass")
                                                .setCaseName("testExampleMethod")
                                                .build())
                                .setResultId("1234abcd-00001")
                                .setGcsUri("gs://test/test.log")
                                .build(),
                        Artifact.newBuilder()
                                .setArtifactId("18274/stub/inv_9000/test-run.log_9000.dat")
                                .setContentType("text/plain")
                                .setGcsUri("gs://test/test-run.log")
                                .build(),
                        Artifact.newBuilder()
                                .setArtifactId("18274/stub/inv_9000/invocation.log_9000.dat")
                                .setContentType("image/png")
                                .setGcsUri("gs://test/invocation.log")
                                .build());
    }

    private String getLogUrl(String log) {
        try {
            return String.format(
                    "https://android-build.googleplex.com/builds/submitted/18274/docs/0/tests/1488327372312/18274/stub/inv_9000/%s_9000.dat",
                    URLEncoder.encode(log, StandardCharsets.UTF_8.name()));
        } catch (UnsupportedEncodingException e) {
            // ignore
        }
        return "";
    }
}
