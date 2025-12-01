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
import com.android.resultdb.proto.FailureReason;
import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.ModuleIdentifier;
import com.android.resultdb.proto.RootInvocation;
import com.android.resultdb.proto.SkippedReason;
import com.android.resultdb.proto.TestIdentifier;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.Variant;
import com.android.resultdb.proto.WorkUnit;
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

import com.google.protobuf.ByteString;
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

    private static class ResultDBReporterTester extends ResultDBReporter {

        public StubClient mRecorderLegacy = StubClient.create();
        public StubClient mRecorder = StubClient.create();
        public String mInvocationId;
        public String mUpdateToken;
        public Invocation mInvocation;
        public String mRootInvocationId;
        public RootInvocation mRootInvocation;
        public WorkUnit mRootWorkUnit;

        public ResultDBReporterTester() {
            super();
        }

        @Override
        TestResultHandler createTestResultHandler() {
            return new TestResultHandlerTester();
        }

        @Override
        IRecorderClientLegacy createRecorderClientLegacy(
                String invocationId, String updateToken, Boolean isStaging) {
            mInvocationId = invocationId;
            mUpdateToken = updateToken;
            return mRecorderLegacy;
        }

        @Override
        IRecorderClientLegacy createRecorderClientLegacy(
                String invocationId, Invocation invocation, Boolean isStaging) {
            mInvocationId = invocationId;
            mInvocation = invocation;
            return mRecorderLegacy;
        }

        @Override
        IRecorderClient createRecorderClient(
                String rootInvocationId, String updateToken, Boolean isStaging) {
            mRootInvocationId = rootInvocationId;
            mUpdateToken = updateToken;
            return mRecorder;
        }

        @Override
        IRecorderClient createRecorderClient(
                String rootInvocationId,
                RootInvocation rootInvocation,
                WorkUnit rootWorkUnit,
                Boolean isStaging) {
            mRootInvocationId = rootInvocationId;
            mRootInvocation = rootInvocation;
            mRootWorkUnit = rootWorkUnit;
            return mRecorder;
        }

        @Override
        long currentTimestamp() {
            return 1000000000L;
        }

        @Override
        String randomUUIDString() {
            return "random_uuid_string";
        }
    }

    private static class TestResultHandlerTester extends TestResultHandler {
        public TestResultHandlerTester() {
            super();
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

    private static WorkUnit newWorkUnitUpdate(
            String rootInvId, String workUnitId, WorkUnit.State state) {
        return WorkUnit.newBuilder()
                .setName("rootInvocations/" + rootInvId + "/workUnits/" + workUnitId)
                .setState(state)
                .build();
    }

    @Before
    public void setUp() throws ConfigurationException {
        mReporter = new ResultDBReporterTester();
        mSimulator = InvocationSimulator.create();
        mConfig = new ConfigurationTester("name", "description");
        mReporter.setConfiguration(mConfig);
        mOption = new OptionSetter(mReporter);
    }

    @Test
    public void noInvocationIdOrRootInvocationIdInContext_reporterDisabled() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withInvocationAttribute("resultdb_work_unit_id", "work_unit_001")
                .withInvocationAttribute("resultdb_work_unit_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        assertThat(mReporter.isDisabled()).isTrue();
        assertThat(mReporter.mRecorder.getTestResults()).isEmpty();
        assertThat(mReporter.mRecorderLegacy.getTestResults()).isEmpty();
    }

    @Test
    public void onlyRootInvocationParamsInContext_reporterNotDisabled() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withInvocationAttribute("resultdb_root_invocation_id", "root_invocation_001")
                .withInvocationAttribute("resultdb_work_unit_id", "work_unit_001")
                .withInvocationAttribute("resultdb_work_unit_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        assertThat(mReporter.isDisabled()).isFalse();
        assertThat(mReporter.mRecorder.getTestResults()).isEmpty();
        // No test results uploaded to mRecorderLegacy, because no invocation id in context.
        assertThat(mReporter.mRecorderLegacy.getTestResults()).isEmpty();
    }

    @Test
    public void onlyLegacyInvocationParamsInContext_reporterNotDisabled() throws Exception {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withInvocationAttribute("resultdb_work_unit_id", "work_unit_001")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        assertThat(mReporter.isDisabled()).isFalse();
        assertThat(mReporter.mRecorder.getTestResults()).isEmpty();
        assertThat(mReporter.mRecorderLegacy.getTestResults())
                .containsExactly(newTestResult("testExampleMethod").build());
    }

    @Test
    public void createLocalInvocation_rootInvocationAndLegacyInvocationCreated() throws Exception {
        mOption.setOptionValue("create-local-invocation", "true");

        BuildInfo info = new BuildInfo("1", "target_1");
        info.setBuildBranch("test-branch");
        info.setBuildFlavor("test-flavor");
        mSimulator
                .withBuildInfo(info)
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        assertThat(mReporter.isDisabled()).isFalse();
        // Assert root invocation creation.
        assertThat(mReporter.mRootInvocation).isNotNull();
        assertThat(mReporter.mRootInvocationId).isEqualTo("u-random_uuid_string");
        // Assert legacy invocation created.
        assertThat(mReporter.mInvocationId).isEqualTo("u-random_uuid_string");
        assertThat(mReporter.mInvocation).isNotNull();
        // Assert root work unit creation.
        assertThat(mReporter.mRecorder.getWorkUnitItems()).hasSize(2);
        ModuleIdentifier moduleId =
                ModuleIdentifier.newBuilder()
                        .setModuleName("example-module")
                        .setModuleScheme("junit")
                        .setModuleVariant(Variant.getDefaultInstance())
                        .build();
        String moduleWorkUnitId = mReporter.mRecorder.getWorkUnitItems().get(0).getWorkUnitId();
        String testRunWorkUnitId = mReporter.mRecorder.getWorkUnitItems().get(1).getWorkUnitId();
        assertThat(moduleWorkUnitId).isNotEqualTo(testRunWorkUnitId);
        assertThat(moduleWorkUnitId).isNotEmpty();
        assertThat(testRunWorkUnitId).isNotEmpty();
        assertThat(mReporter.mRecorder.getWorkUnitItems())
                .containsExactly(
                        // Module work unit.
                        new RecorderClient.WorkUnitItem(
                                "root",
                                moduleWorkUnitId,
                                WorkUnit.newBuilder()
                                        .setState(WorkUnit.State.RUNNING)
                                        .setKind("TF_MODULE")
                                        .setRealm("android:ants-experiment")
                                        .setModuleId(moduleId)
                                        .build()),
                        // Test run work unit.
                        new RecorderClient.WorkUnitItem(
                                moduleWorkUnitId,
                                testRunWorkUnitId,
                                WorkUnit.newBuilder()
                                        .setState(WorkUnit.State.RUNNING)
                                        .setKind("TF_TEST_RUN")
                                        .setRealm("android:ants-experiment")
                                        .build()));
        // Assert work unit updates.
        assertThat(mReporter.mRecorder.getWorkUnitUpdates())
                .containsExactly(
                        // Test run work unit update
                        newWorkUnitUpdate(
                                "u-random_uuid_string",
                                testRunWorkUnitId,
                                WorkUnit.State.SUCCEEDED),
                        // Module work unit update
                        newWorkUnitUpdate(
                                "u-random_uuid_string", moduleWorkUnitId, WorkUnit.State.SUCCEEDED),
                        // Root work unit update
                        newWorkUnitUpdate(
                                "u-random_uuid_string", "root", WorkUnit.State.SUCCEEDED));
        // Assert test results uploaded to mRecorderLegacy.
        Variant variant =
                Variant.newBuilder()
                        .putDef("branch", "test-branch")
                        .putDef("build_provider", "androidbuild")
                        .putDef("name", "test tag")
                        .putDef("scheduler", "ATP")
                        .putDef("target", "test-flavor")
                        .build();
        TestResult.Builder expected = newTestResult("testExampleMethod");
        expected.setTestIdStructured(
                expected.getTestIdStructured().toBuilder().setModuleVariant(variant).build());
        assertThat(mReporter.mRecorderLegacy.getTestResults()).containsExactly(expected.build());
        // No test results uploaded to mRecorder yet, because it is not implemented yet.
        assertThat(mReporter.mRecorder.getTestResults()).isEmpty();
    }

    @Test
    public void uploadToUpstreamRootInvocation_multipleModulesAndTestRuns() {
        BuildInfo info = new BuildInfo("1", "target_1");
        info.setBuildBranch("test-branch");
        info.setBuildFlavor("test-flavor");
        mSimulator
                .withBuildInfo(info)
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withInvocationAttribute("resultdb_root_invocation_id", "root_invocation_001")
                .withInvocationAttribute("resultdb_work_unit_id", "top_work_unit_001")
                .withInvocationAttribute("resultdb_work_unit_update_token", "update_token")
                .withModule("module-1")
                .withModuleAttribute(ModuleDefinition.MODULE_ABI, "module-1-abi")
                .withModuleAttribute("should-ignore", "ignore")
                .withModuleAttribute(
                        ModuleDefinition.MODULE_PARAMETERIZATION, "test-parameterization")
                .withTestRun("test-run-11")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .withTestRun("test-run-12")
                .withModule("module-2")
                .withModuleAttribute(ModuleDefinition.MODULE_ABI, "module-2-abi")
                .withTestRun("test-run-21")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        // Assert work units created.
        assertThat(mReporter.mRecorder.getWorkUnitItems()).hasSize(5);
        String moduleWorkUnitId1 = mReporter.mRecorder.getWorkUnitItems().get(0).getWorkUnitId();
        String testRunWorkUnitId11 = mReporter.mRecorder.getWorkUnitItems().get(1).getWorkUnitId();
        String testRunWorkUnitId12 = mReporter.mRecorder.getWorkUnitItems().get(2).getWorkUnitId();
        String moduleWorkUnitId2 = mReporter.mRecorder.getWorkUnitItems().get(3).getWorkUnitId();
        String testRunWorkUnitId21 = mReporter.mRecorder.getWorkUnitItems().get(4).getWorkUnitId();
        Variant moduleVariant1 =
                Variant.newBuilder()
                        .putDef("module_abi", "module-1-abi")
                        .putDef("module_param", "test-parameterization")
                        .build();
        Variant moduleVariant2 = Variant.newBuilder().putDef("module_abi", "module-2-abi").build();
        assertThat(mReporter.mRecorder.getWorkUnitItems())
                .containsExactly(
                        // Module work unit 1.
                        new RecorderClient.WorkUnitItem(
                                "top_work_unit_001",
                                moduleWorkUnitId1,
                                WorkUnit.newBuilder()
                                        .setState(WorkUnit.State.RUNNING)
                                        .setKind("TF_MODULE")
                                        .setRealm("android:ants-experiment")
                                        .setModuleId(
                                                ModuleIdentifier.newBuilder()
                                                        .setModuleName("module-1")
                                                        .setModuleScheme("junit")
                                                        .setModuleVariant(moduleVariant1)
                                                        .build())
                                        .build()),
                        // Test run work unit 11.
                        new RecorderClient.WorkUnitItem(
                                moduleWorkUnitId1,
                                testRunWorkUnitId11,
                                WorkUnit.newBuilder()
                                        .setState(WorkUnit.State.RUNNING)
                                        .setKind("TF_TEST_RUN")
                                        .setRealm("android:ants-experiment")
                                        .build()),
                        // Test run work unit 12.
                        new RecorderClient.WorkUnitItem(
                                moduleWorkUnitId1,
                                testRunWorkUnitId12,
                                WorkUnit.newBuilder()
                                        .setState(WorkUnit.State.RUNNING)
                                        .setKind("TF_TEST_RUN")
                                        .setRealm("android:ants-experiment")
                                        .build()),
                        // Module work unit 2.
                        new RecorderClient.WorkUnitItem(
                                "top_work_unit_001",
                                moduleWorkUnitId2,
                                WorkUnit.newBuilder()
                                        .setState(WorkUnit.State.RUNNING)
                                        .setKind("TF_MODULE")
                                        .setRealm("android:ants-experiment")
                                        .setModuleId(
                                                ModuleIdentifier.newBuilder()
                                                        .setModuleName("module-2")
                                                        .setModuleScheme("junit")
                                                        .setModuleVariant(moduleVariant2)
                                                        .build())
                                        .build()),
                        // Test run work unit 21.
                        new RecorderClient.WorkUnitItem(
                                moduleWorkUnitId2,
                                testRunWorkUnitId21,
                                WorkUnit.newBuilder()
                                        .setState(WorkUnit.State.RUNNING)
                                        .setKind("TF_TEST_RUN")
                                        .setRealm("android:ants-experiment")
                                        .build()));
        // Assert work unit updates.
        assertThat(mReporter.mRecorder.getWorkUnitUpdates())
                .containsExactly(
                        newWorkUnitUpdate(
                                "root_invocation_001",
                                testRunWorkUnitId11,
                                WorkUnit.State.SUCCEEDED),
                        newWorkUnitUpdate(
                                "root_invocation_001",
                                testRunWorkUnitId12,
                                WorkUnit.State.SUCCEEDED),
                        newWorkUnitUpdate(
                                "root_invocation_001", moduleWorkUnitId1, WorkUnit.State.SUCCEEDED),
                        newWorkUnitUpdate(
                                "root_invocation_001",
                                testRunWorkUnitId21,
                                WorkUnit.State.SUCCEEDED),
                        newWorkUnitUpdate(
                                "root_invocation_001",
                                moduleWorkUnitId2,
                                WorkUnit.State.SUCCEEDED));

        // Assert test results uploaded to mRecorderLegacy.
        Variant variant =
                Variant.newBuilder()
                        .putDef("branch", "test-branch")
                        .putDef("build_provider", "androidbuild")
                        .putDef("name", "test tag")
                        .putDef("scheduler", "ATP")
                        .putDef("target", "test-flavor")
                        .build();
        TestResult.Builder expectedTestResult1 = newTestResult("testExampleMethod");
        expectedTestResult1
                .setResultId("1234abcd-00001")
                .setTestIdStructured(
                        expectedTestResult1.getTestIdStructured().toBuilder()
                                .setModuleName("module-1")
                                .setModuleVariant(
                                        variant.toBuilder()
                                                .putDef("module_abi", "module-1-abi")
                                                .putDef("module_param", "test-parameterization")
                                                .build())
                                .build());
        TestResult.Builder expectedTestResult2 = newTestResult("testExampleMethod");
        expectedTestResult2
                .setResultId("1234abcd-00002")
                .setTestIdStructured(
                        expectedTestResult2.getTestIdStructured().toBuilder()
                                .setModuleName("module-2")
                                .setModuleVariant(
                                        variant.toBuilder()
                                                .putDef("module_abi", "module-2-abi")
                                                .build())
                                .build());
        assertThat(mReporter.mRecorderLegacy.getTestResults())
                .containsExactly(expectedTestResult1.build(), expectedTestResult2.build());
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

        assertThat(mReporter.mRecorderLegacy.getTestResults())
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
                                                                .setMessage("Fail Trace")
                                                                .setTrace("Fail Trace")))
                                .build(),
                        newTestResult("testMethodAssumption")
                                .setResultId("1234abcd-00003")
                                .setStatusV2(TestResult.Status.SKIPPED)
                                .setStartTime(Timestamps.fromMillis(1536333825400L))
                                .setSkippedReason(
                                        SkippedReason.newBuilder()
                                                .setKind(SkippedReason.Kind.SKIPPED_BY_TEST_BODY)
                                                .setReasonMessage("Assumption Fail Trace"))
                                .setSummaryHtml("Assumption Fail Trace")
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
    public void testWithFailureDescription_failureStatus_testFailure_large_trace() {
        // Put failure message longer than the RBD trace field limit (4096 bytes).
        String failureMessage = "";
        for (int i = 0; i < 4096 + 100; i++) {
            failureMessage += "a";
        }
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTestFailure(
                        "com.google.ExampleClass",
                        "testMethodFailed",
                        FailureDescription.create(
                                failureMessage, TestRecordProto.FailureStatus.TEST_FAILURE))
                .simulateInvocation(mReporter);

        FailureReason.Error expectedRDBError =
                FailureReason.Error.newBuilder()
                        // Message field has a limit of 1024 bytes.
                        .setMessage(failureMessage.substring(0, 1024))
                        // Trace field has a limit of 4096 bytes.
                        .setTrace(failureMessage.substring(0, 4096))
                        .build();
        TestResult expectedTestResult =
                newTestResult("testMethodFailed")
                        .setStatusV2(TestResult.Status.FAILED)
                        .setResultId("1234abcd-00001")
                        .setFailureReason(
                                FailureReason.newBuilder()
                                        .setKind(FailureReason.Kind.ORDINARY)
                                        .addErrors(expectedRDBError))
                        .setSummaryHtml("TF error type: TEST_FAILURE")
                        .build();
        assertThat(mReporter.mRecorderLegacy.getTestResults()).containsExactly(expectedTestResult);

        // Verify the full skip reason artifact is uploaded to ResultDB.
        assertThat(mReporter.mRecorderLegacy.getArtifacts())
                .containsExactly(
                        Artifact.newBuilder()
                                .setArtifactId("testFailed_trace")
                                .setContentType("text/plain")
                                .setSizeBytes(4196)
                                .setTestIdStructured(expectedTestResult.getTestIdStructured())
                                .setResultId(expectedTestResult.getResultId())
                                .setContents(ByteString.copyFromUtf8(failureMessage))
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

        assertThat(mReporter.mRecorderLegacy.getTestResults())
                .containsExactly(
                        newTestResult("testMethodCrashed")
                                .setStatusV2(TestResult.Status.FAILED)
                                .setFailureReason(
                                        FailureReason.newBuilder()
                                                .setKind(FailureReason.Kind.CRASH)
                                                .addErrors(
                                                        FailureReason.Error.newBuilder()
                                                                .setMessage("Failure Message")
                                                                .setTrace(
                                                                        "Failure Message\n"
                                                                            + " java.lang.RuntimeException:"
                                                                            + " Failure Message")))
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

        assertThat(mReporter.mRecorderLegacy.getTestResults())
                .containsExactly(
                        newTestResult("testMethodTimedOut")
                                .setStatusV2(TestResult.Status.FAILED)
                                .setFailureReason(
                                        FailureReason.newBuilder()
                                                .setKind(FailureReason.Kind.TIMEOUT)
                                                .addErrors(
                                                        FailureReason.Error.newBuilder()
                                                                .setMessage("Failure Message")
                                                                .setTrace("Failure Message")))
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
                .withTestRun("testRun1")
                .simulateInvocation(mReporter);
        Variant variant =
                Variant.newBuilder()
                        .putDef("module_abi", "x86")
                        .putDef("name", "test tag")
                        .putDef("scheduler", "ATP")
                        .build();

        assertThat(mReporter.mRecorderLegacy.getTestResults())
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
        LogFile newLog =
                new LogFile("/tmp/new.log", getLogUrl("new.log"), false, LogDataType.TEXT, 101);
        LogFile invalidLog = new LogFile("", getLogUrl("invalid.log"), LogDataType.TEXT);
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .withTestLog(testLog)
                .withTestRunLog(testRunLog)
                .withInvocationLog(invocationLog)
                .simulateInvocation(mReporter);
        // simulate the updateStoragePath called from AnTS Result reporter.
        mReporter.uploadStoragePath(
                "18274/stub/inv_9000/test.log_9000.dat", "/bigstore/test/test.log", testLog);
        mReporter.uploadStoragePath(
                "18274/stub/inv_9000/test-run.log_9000.dat",
                "/bigstore/test/test-run.log",
                testRunLog);
        mReporter.uploadStoragePath(
                "18274/stub/inv_9000/invocation.log_9000.dat",
                "/bigstore/test/invocation.log",
                invocationLog);
        // uploadStoragePath called with unseen resource ID.
        mReporter.uploadStoragePath(
                "18274/stub/inv_9000/new.log_9000.dat", "/bigstore/test/new.log", newLog);
        // uploadStoragePath called with invalid storage path.
        mReporter.uploadStoragePath(
                "18274/stub/inv_9000/invalid.log_9000.dat", "/bigstore/", invalidLog);
        // Verify the artifacts are uploaded to ResultDB.
        assertThat(mReporter.mRecorderLegacy.getArtifacts())
                .containsExactly(
                        Artifact.newBuilder()
                                .setArtifactId("18274/stub/inv_9000/test.log_9000.dat")
                                .setContentType("text/plain")
                                .setTestIdStructured(
                                        TestIdentifier.newBuilder()
                                                .setModuleName("example-module")
                                                .setModuleScheme("junit")
                                                .setCoarseName("com.google")
                                                .setFineName("ExampleClass")
                                                .setCaseName("testExampleMethod")
                                                .build())
                                .setResultId("1234abcd-00001")
                                .setGcsUri("gs://test/test.log")
                                .setArtifactType("TEXT")
                                .build(),
                        Artifact.newBuilder()
                                .setArtifactId("18274/stub/inv_9000/test-run.log_9000.dat")
                                .setContentType("text/plain")
                                .setGcsUri("gs://test/test-run.log")
                                .setArtifactType("TEXT")
                                .build(),
                        Artifact.newBuilder()
                                .setArtifactId("18274/stub/inv_9000/invocation.log_9000.dat")
                                .setContentType("image/png")
                                .setGcsUri("gs://test/invocation.log")
                                .setArtifactType("PNG")
                                .build(),
                        Artifact.newBuilder()
                                .setArtifactId("18274/stub/inv_9000/new.log_9000.dat")
                                .setContentType("text/plain")
                                .setGcsUri("gs://test/new.log")
                                .setArtifactType("TEXT")
                                .setSizeBytes(101)
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
