/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.SubprocessResultsReporter;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.incremental.IIncrementalSetup;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.InstrumentationTest;
import com.android.tradefed.util.AbiUtils;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Unit tests for {@link AtestExecutionPlanSuiteRunner}. */
@RunWith(JUnit4.class)
public class AtestExecutionPlanSuiteRunnerTest {

    @Rule public TemporaryFolder mTempFolder = new TemporaryFolder();

    private static final String ABI = "arm64-v8a";
    private static final String MODULE_NAME = "module_one";

    private AtestExecutionPlanSuiteRunner mRunner;
    private OptionSetter mOptionSetter;
    @Mock IDeviceBuildInfo mBuildInfo;
    @Mock ITestDevice mMockDevice;
    private LinkedHashMap<String, IConfiguration> mTestConfigs;
    private IConfiguration mMockConfig;

    /** Pseudo runner for testing. */
    public class FakeAtestExecutionPlanSuiteRunner extends AtestExecutionPlanSuiteRunner {
        @Override
        public Set<IAbi> getAbis(ITestDevice device) throws DeviceNotAvailableException {
            Set<IAbi> abis = new LinkedHashSet<>();
            abis.add(new Abi(ABI, AbiUtils.getBitness(ABI)));
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

        mRunner = new FakeAtestExecutionPlanSuiteRunner();
        mRunner.setBuild(mBuildInfo);
        mRunner.setDevice(mMockDevice);
        mRunner.setSkipjarLoading(true);

        mOptionSetter = new OptionSetter(mRunner);

        mTestConfigs = new LinkedHashMap<>();
        mMockConfig = addMockModuleConfig(MODULE_NAME);

        when(mBuildInfo.getTestsDir()).thenReturn(mTempFolder.newFolder());
        when(mMockDevice.getProperty(Mockito.any())).thenReturn(ABI);
    }

    @Test
    public void testLoadTests_integration() throws Exception {
        AtestExecutionPlanSuiteRunner runner = Mockito.spy(mRunner);
        Mockito.doReturn(mTestConfigs).when(runner).callSuperLoadTests();

        runner.loadTests();

        Mockito.verify(runner).setPrimaryAbiRun(true);
        Mockito.verify(runner).callSuperLoadTests();
        Mockito.verify(runner).loadTestsInternal(mTestConfigs);
    }

    @Test
    public void testLoadTestsInternal_skipSetUp() throws Exception {
        mOptionSetter.setOptionValue("disable-target-preparers", "true");
        ITargetPreparer mockPreparer = mock(ITargetPreparer.class);
        when(mMockConfig.getTargetPreparers()).thenReturn(Arrays.asList(mockPreparer));

        mRunner.loadTestsInternal(mTestConfigs);

        Mockito.verify(mockPreparer).setDisable(true);
    }

    @Test
    public void testLoadTestsInternal_skipTearDown() throws Exception {
        mOptionSetter.setOptionValue("disable-teardown", "true");
        ITargetPreparer mockPreparer = mock(ITargetPreparer.class);
        when(mMockConfig.getTargetPreparers()).thenReturn(Arrays.asList(mockPreparer));

        mRunner.loadTestsInternal(mTestConfigs);

        Mockito.verify(mockPreparer).setDisableTearDown(true);
    }

    @Test
    public void testLoadTestsInternal_debug() throws Exception {
        mOptionSetter.setOptionValue("wait-for-debugger", "true");
        InstrumentationTest mockTest = mock(InstrumentationTest.class);
        when(mMockConfig.getTests()).thenReturn(Arrays.asList(mockTest));

        mRunner.loadTestsInternal(mTestConfigs);

        Mockito.verify(mockTest).setDebug(true);
    }

    @Test
    public void testLoadTestsInternal_incrementalSetup_yes() throws Exception {
        mOptionSetter.setOptionValue("incremental-setup", "YES");

        PseudoTargetPreparer preparer = mock(PseudoTargetPreparer.class);
        IDeviceConfiguration deviceConfig = mock(IDeviceConfiguration.class);
        when(deviceConfig.getTargetPreparers()).thenReturn(Arrays.asList(preparer));
        when(mMockConfig.getDeviceConfig()).thenReturn(Arrays.asList(deviceConfig));

        mRunner.loadTestsInternal(mTestConfigs);

        Mockito.verify((IIncrementalSetup) preparer).setIncrementalSetupEnabled(true);
    }

    @Test
    public void testLoadTestsInternal_incrementalSetup_no() throws Exception {
        mOptionSetter.setOptionValue("incremental-setup", "NO");

        PseudoTargetPreparer preparer = mock(PseudoTargetPreparer.class);
        IDeviceConfiguration deviceConfig = mock(IDeviceConfiguration.class);
        when(deviceConfig.getTargetPreparers()).thenReturn(Arrays.asList(preparer));
        when(mMockConfig.getDeviceConfig()).thenReturn(Arrays.asList(deviceConfig));

        mRunner.loadTestsInternal(mTestConfigs);

        Mockito.verify((IIncrementalSetup) preparer).setIncrementalSetupEnabled(false);
    }

    @Test
    public void testCreateModuleListeners_reportPort() throws Exception {
        mOptionSetter.setOptionValue("subprocess-report-port", "1234");

        List<ITestInvocationListener> listeners = mRunner.createModuleListeners();

        boolean foundSubprocess = false;
        for (ITestInvocationListener listener : listeners) {
            if (listener instanceof SubprocessResultsReporter) {
                foundSubprocess = true;
                break;
            }
        }
        assertTrue(
                "SubprocessResultsReporter should be added when report port is set",
                foundSubprocess);
    }

    private IConfiguration addMockModuleConfig(String moduleName) {
        IConfiguration config = mock(IConfiguration.class);
        ConfigurationDescriptor desc = new ConfigurationDescriptor();
        desc.setModuleName(moduleName);
        when(config.getConfigurationDescription()).thenReturn(desc);
        when(config.getName()).thenReturn(moduleName);
        when(config.clone()).thenReturn(config);
        when(config.getTargetPreparers()).thenReturn(new ArrayList<>());
        when(config.getTests()).thenReturn(new ArrayList<>());
        when(config.getDeviceConfig()).thenReturn(new ArrayList<>());
        mTestConfigs.put(moduleName, config);
        return config;
    }

    /** A pseudo target preparer which is optimizable with incremental setup. */
    private abstract static class PseudoTargetPreparer
            implements ITargetPreparer, IIncrementalSetup {}
}
