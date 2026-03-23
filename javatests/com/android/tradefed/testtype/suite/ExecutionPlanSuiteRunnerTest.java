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
package com.android.tradefed.testtype.suite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.soong.testconfigs.proto.TestConfigs;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.StubTest;
import com.android.tradefed.util.AbiUtils;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.protobuf.TextFormat;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Unit tests for {@link ExecutionPlanSuiteRunner}. */
@RunWith(JUnit4.class)
public class ExecutionPlanSuiteRunnerTest {

    @Rule public TemporaryFolder mTempFolder = new TemporaryFolder();

    private static final String ABI_1 = "arm64-v8a";
    private static final String ABI_2 = "armeabi-v7a";
    private static final String TEST_DATA_DIR = "testdata";
    private static final String TEST_CONFIG_NAME = "test-config-name-1";
    private static final String EMPTY_CONFIG = "empty";

    private ExecutionPlanSuiteRunner mRunner;
    private OptionSetter mOptionSetter;
    @Mock IDeviceBuildInfo mBuildInfo;
    @Mock ITestDevice mMockDevice;
    private LinkedHashMap<String, IConfiguration> mTestConfigs;

    public class FakeExecutionPlanSuiteRunner extends ExecutionPlanSuiteRunner {
        @Override
        public Set<IAbi> getAbis(ITestDevice device) throws DeviceNotAvailableException {
            Set<IAbi> abis = new LinkedHashSet<>();
            abis.add(new Abi(ABI_1, AbiUtils.getBitness(ABI_1)));
            abis.add(new Abi(ABI_2, AbiUtils.getBitness(ABI_2)));
            return abis;
        }

        @Override
        public LinkedHashMap<String, IConfiguration> loadingStrategy(
                Set<IAbi> abis, List<File> testsDirs, String suitePrefix, String suiteTag) {
            return mTestConfigs;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mRunner = new FakeExecutionPlanSuiteRunner();
        mRunner.setBuild(mBuildInfo);
        mRunner.setDevice(mMockDevice);
        mRunner.setSkipjarLoading(true);

        mOptionSetter = new OptionSetter(mRunner);

        // Default test configs
        mTestConfigs = new LinkedHashMap<>();
        addModuleConfig(TEST_CONFIG_NAME);

        when(mBuildInfo.getFile(Mockito.any(String.class))).thenReturn(null);
        when(mBuildInfo.getFile(Mockito.any(BuildInfoFileKey.class))).thenReturn(null);
        when(mBuildInfo.getTestsDir()).thenReturn(mTempFolder.newFolder());
        when(mMockDevice.getProperty(Mockito.any())).thenReturn(ABI_1);
        when(mMockDevice.getIDevice()).thenReturn(mock(IDevice.class));
        when(mMockDevice.getFoldableStates()).thenReturn(new HashSet<>());
    }

    @Test
    public void testLoadTests_noConfigZip() throws Exception {
        mOptionSetter.setOptionValue("config-zip-paths", "non_existent.zip");

        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertTrue(configMap.isEmpty());
    }

    @Test(expected = HarnessRuntimeException.class)
    public void testLoadTests_withIncludeFilterSet() throws Exception {
        mOptionSetter.setOptionValue("include-filter", "a_module_to_include");
        mRunner.loadTests();
    }

    @Test(expected = HarnessRuntimeException.class)
    public void testLoadTests_nonExistentPlan() throws Exception {
        mOptionSetter.setOptionValue("execution-plans", "non_existent_plan");
        mRunner.loadTests();
    }

    @Test
    public void testLoadTests_multiplePlans() throws Exception {
        String zipPath = createTestConfigZip("acme_test_configs_textproto");
        when(mBuildInfo.getFile("testconfigs.zip")).thenReturn(new File(zipPath));
        mOptionSetter.setOptionValue("config-zip-paths", "testconfigs.zip");
        mOptionSetter.setOptionValue("execution-plans", "plan_alpha");

        mTestConfigs.clear();
        addModuleConfig("module_one");
        addModuleConfig("module_two");

        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(2, configMap.size());
        assertTrue(configMap.containsKey("module_one"));
        assertTrue(configMap.containsKey("module_two"));
        assertFalse(
                configMap
                        .get("module_one")
                        .getConfigurationDescription()
                        .isNotIRemoteTestShardable());
        assertFalse(
                configMap
                        .get("module_two")
                        .getConfigurationDescription()
                        .isNotIRemoteTestShardable());
        assertNull(
                configMap
                        .get("module_one")
                        .getConfigurationDescription()
                        .getMetaData(RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION));
        assertNull(
                configMap
                        .get("module_two")
                        .getConfigurationDescription()
                        .getMetaData(RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION));
    }

    @Test
    public void testLoadTests_multiplePlansWithRemoteTestTimeOut() throws Exception {
        String zipPath = createTestConfigZip("acme_test_configs_textproto");
        when(mBuildInfo.getFile("testconfigs.zip")).thenReturn(new File(zipPath));
        mOptionSetter.setOptionValue("config-zip-paths", "testconfigs.zip");
        mOptionSetter.setOptionValue("execution-plans", "plan_alpha");
        mOptionSetter.setOptionValue("execution-plans", "plan_gamma");
        mOptionSetter.setOptionValue(RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION, "15m");

        mTestConfigs.clear();
        addModuleConfig("module_one");
        addModuleConfig("module_two");
        addModuleConfig("module_five");
        // Show unrelated configs returned from super.loadTests are filtered out
        // in final result of ExecutionPlanSuiteRunner
        addModuleConfig("module_unrelated");

        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(3, configMap.size());
        assertTrue(configMap.containsKey("module_one"));
        assertTrue(configMap.containsKey("module_two"));
        assertTrue(configMap.containsKey("module_five"));
        assertEquals(
                1,
                configMap
                        .get("module_one")
                        .getConfigurationDescription()
                        .getMetaData(RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION)
                        .size());
        assertEquals(
                1,
                configMap
                        .get("module_two")
                        .getConfigurationDescription()
                        .getMetaData(RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION)
                        .size());
        assertEquals(
                1,
                configMap
                        .get("module_five")
                        .getConfigurationDescription()
                        .getMetaData(RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION)
                        .size());
        assertTrue(
                configMap
                        .get("module_one")
                        .getConfigurationDescription()
                        .getMetaData(RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION)
                        .contains("PT15M"));
        assertTrue(
                configMap
                        .get("module_two")
                        .getConfigurationDescription()
                        .getMetaData(RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION)
                        .contains("PT15M"));
        assertTrue(
                configMap
                        .get("module_five")
                        .getConfigurationDescription()
                        .getMetaData(RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION)
                        .contains("PT15M"));
    }

    @Test
    public void testLoadTests_multipleAbis() throws Exception {
        String zipPath = createTestConfigZip("acme_test_configs_textproto");
        when(mBuildInfo.getFile("testconfigs.zip")).thenReturn(new File(zipPath));
        mOptionSetter.setOptionValue("config-zip-paths", "testconfigs.zip");
        mOptionSetter.setOptionValue("execution-plans", "plan_alpha");

        mTestConfigs.clear();
        addModuleConfig("module_one", ABI_1);
        addModuleConfig("module_one", ABI_2);
        addModuleConfig("module_two", ABI_1);
        addModuleConfig("module_two", ABI_2);

        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(4, configMap.size());
        assertTrue(configMap.containsKey(ABI_1 + " module_one"));
        assertTrue(configMap.containsKey(ABI_2 + " module_one"));
        assertTrue(configMap.containsKey(ABI_1 + " module_two"));
        assertTrue(configMap.containsKey(ABI_2 + " module_two"));
    }

    @Test
    public void testLoadTest_repeatedModules() throws Exception {
        String zipPath = createTestConfigZip("acme_test_configs_textproto");
        when(mBuildInfo.getFile("testconfigs.zip")).thenReturn(new File(zipPath));
        mOptionSetter.setOptionValue("config-zip-paths", "testconfigs.zip");
        // "module_four" is repeated in "plan_beta"
        mOptionSetter.setOptionValue("execution-plans", "plan_beta");

        mTestConfigs.clear();
        addModuleConfig("module_three");
        addModuleConfig("module_four");

        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(2, configMap.size());
        assertTrue(configMap.containsKey("module_three"));
        assertTrue(configMap.containsKey("module_four"));

        // When a module runs in multiple execution-plans, it set not shardable.
        assertFalse(
                configMap
                        .get("module_three")
                        .getConfigurationDescription()
                        .isNotIRemoteTestShardable());
        assertTrue(
                configMap
                        .get("module_four")
                        .getConfigurationDescription()
                        .isNotIRemoteTestShardable());

        // One IRemoteTest instance per abi faked in getAbis function.
        assertEquals(2, configMap.get("module_three").getTests().size());
        assertEquals(4, configMap.get("module_four").getTests().size());
    }

    @Test
    public void testLoadTests_regexZipPaths() throws Exception {
        String zipPath = createTestConfigZip("acme_test_configs_textproto");
        when(mBuildInfo.getVersionedFileKeys())
                .thenReturn(new HashSet<>(Arrays.asList("testconfigs.zip", "other.zip")));
        when(mBuildInfo.getFile("testconfigs.zip")).thenReturn(new File(zipPath));
        // Use a generic regex that would match the given exact file.
        mOptionSetter.setOptionValue("config-zip-paths", ".*testconfigs\\.zip");
        mOptionSetter.setOptionValue("execution-plans", "plan_alpha");

        mTestConfigs.clear();
        addModuleConfig("module_one");
        addModuleConfig("module_two");

        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(2, configMap.size());
        assertTrue(configMap.containsKey("module_one"));
        assertTrue(configMap.containsKey("module_two"));
    }

    @Test
    public void testLoadTests_invalidRegexZipPaths() throws Exception {
        String zipPath = createTestConfigZip("acme_test_configs_textproto");
        when(mBuildInfo.getVersionedFileKeys())
                .thenReturn(new HashSet<>(Arrays.asList("testconfigs.zip", "other.zip")));
        // Mock getFile to return the zipPath when queried with the literal invalid regex string
        when(mBuildInfo.getFile(".*[unclosed_testconfigs.zip")).thenReturn(new File(zipPath));
        // Use an invalid regex (unmatched bracket)
        mOptionSetter.setOptionValue("config-zip-paths", ".*[unclosed_testconfigs.zip");
        mOptionSetter.setOptionValue("execution-plans", "plan_alpha");

        mTestConfigs.clear();
        addModuleConfig("module_one");
        addModuleConfig("module_two");

        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(2, configMap.size());
        assertTrue(configMap.containsKey("module_one"));
        assertTrue(configMap.containsKey("module_two"));
    }

    private void addModuleConfig(String moduleName) {
        addModuleConfig(moduleName, null);
    }

    private void addModuleConfig(String moduleName, String abiName) {
        try {
            IConfiguration config =
                    ConfigurationFactory.getInstance()
                            .createConfigurationFromArgs(new String[] {EMPTY_CONFIG});
            config.setTest(new StubTest());
            config.getConfigurationDescription().setModuleName(moduleName);
            if (abiName != null && !abiName.isEmpty()) {
                config.getConfigurationDescription()
                        .setAbi(new Abi(abiName, AbiUtils.getBitness(abiName)));
                mTestConfigs.put(String.format("%s %s", abiName, moduleName), config);
            } else {
                mTestConfigs.put(moduleName, config);
            }
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    private String createTestConfigZip(String sourceTextProtoName) throws IOException {
        // Step 1: read the textproto
        String srcFile = String.format("/%s/%s", TEST_DATA_DIR, sourceTextProtoName);
        InputStream resourceStream =
                ExecutionPlanSuiteRunnerTest.class.getResourceAsStream(srcFile);
        File textProtoFile = mTempFolder.newFile(sourceTextProtoName);
        FileUtil.writeToFile(resourceStream, textProtoFile);

        String textProto = FileUtil.readStringFromFile(textProtoFile);
        TestConfigs.Builder testConfigsBuilder = TestConfigs.newBuilder();
        TextFormat.merge(textProto, testConfigsBuilder);
        TestConfigs testConfigs = testConfigsBuilder.build();

        // Step 2: write it to binaryproto
        File binaryProtoFile = mTempFolder.newFile("test_configs.pb");
        try (OutputStream output = new FileOutputStream(binaryProtoFile)) {
            testConfigs.writeTo(output);
        }

        // Step 3: zip the binaryproto
        File zipFile = new File(mTempFolder.getRoot(), "testconfigs.zip");
        ZipUtil.createZip(Arrays.asList(binaryProtoFile), zipFile);

        // Step 4: return the zip path
        return zipFile.getAbsolutePath();
    }
}
