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
package com.android.tradefed.util;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.incremental.IIncrementalSetup;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.testtype.InstrumentationTest;
import com.android.tradefed.testtype.IsolatedHostTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Unit tests for {@link AtestUtils}. */
@RunWith(JUnit4.class)
public class AtestUtilsTest {

    private IConfiguration mMockConfig;
    private ITargetPreparer mMockPreparer;

    @Before
    public void setUp() {
        mMockConfig = mock(IConfiguration.class);
        mMockPreparer = mock(ITargetPreparer.class);
    }

    @Test
    public void testDisableTargetPreparers_skipSetUp() {
        List<ITargetPreparer> preparers = new ArrayList<>();
        preparers.add(mMockPreparer);
        when(mMockConfig.getTargetPreparers()).thenReturn(preparers);
        when(mMockConfig.getName()).thenReturn("test");

        AtestUtils.disableTargetPreparers(mMockConfig, true, false);

        verify(mMockPreparer).setDisable(true);
    }

    @Test
    public void testDisableTargetPreparers_skipTearDown() {
        List<ITargetPreparer> preparers = new ArrayList<>();
        preparers.add(mMockPreparer);
        when(mMockConfig.getTargetPreparers()).thenReturn(preparers);
        when(mMockConfig.getName()).thenReturn("test");

        AtestUtils.disableTargetPreparers(mMockConfig, false, true);

        verify(mMockPreparer).setDisableTearDown(true);
    }

    @Test
    public void testSetIncrementalSetupEnabledForTargetPreparers() {
        IDeviceConfiguration deviceConfig = mock(IDeviceConfiguration.class);
        List<IDeviceConfiguration> deviceConfigs = new ArrayList<>();
        deviceConfigs.add(deviceConfig);
        when(mMockConfig.getDeviceConfig()).thenReturn(deviceConfigs);

        ITargetPreparer incrementalPreparer =
                mock(
                        ITargetPreparer.class,
                        withSettings().extraInterfaces(IIncrementalSetup.class));
        List<ITargetPreparer> preparers = new ArrayList<>();
        preparers.add(incrementalPreparer);
        when(deviceConfig.getTargetPreparers()).thenReturn(preparers);

        AtestUtils.setIncrementalSetupEnabledForTargetPreparers(mMockConfig, true);

        verify((IIncrementalSetup) incrementalPreparer).setIncrementalSetupEnabled(true);
    }

    @Test
    public void testAddDebugger_instrumentation() {
        InstrumentationTest mockTest = mock(InstrumentationTest.class);
        List<IRemoteTest> tests = new ArrayList<>();
        tests.add(mockTest);
        when(mMockConfig.getTests()).thenReturn(tests);

        AtestUtils.addDebugger(mMockConfig);

        verify(mockTest).setDebug(true);
    }

    @Test
    public void testAddDebugger_isolatedHost() {
        IsolatedHostTest mockTest = mock(IsolatedHostTest.class);
        List<IRemoteTest> tests = new ArrayList<>();
        tests.add(mockTest);
        when(mMockConfig.getTests()).thenReturn(tests);

        AtestUtils.addDebugger(mMockConfig);

        verify(mockTest).setDebug(true);
    }

    @Test
    public void testApplyIncludeFilters_multipleModulesAndFilters() {
        LinkedHashMap<String, IConfiguration> configMap = new LinkedHashMap<>();

        IConfiguration mockConfig1 = mock(IConfiguration.class);
        when(mockConfig1.getName()).thenReturn("module1");
        ITestFilterReceiver mockTest1 =
                mock(ITestFilterReceiver.class, withSettings().extraInterfaces(IRemoteTest.class));
        when(mockConfig1.getTests()).thenReturn(Arrays.asList((IRemoteTest) mockTest1));
        configMap.put("module1", mockConfig1);

        IConfiguration mockConfig2 = mock(IConfiguration.class);
        when(mockConfig2.getName()).thenReturn("module2");
        ITestFilterReceiver mockTest2 =
                mock(ITestFilterReceiver.class, withSettings().extraInterfaces(IRemoteTest.class));
        when(mockConfig2.getTests()).thenReturn(Arrays.asList((IRemoteTest) mockTest2));
        configMap.put("module2", mockConfig2);

        List<String> filters =
                Arrays.asList("module1:filter1", "module1:filter2", "module2:filter3");

        AtestUtils.applyIncludeFilters(configMap, filters);

        verify(mockTest1).addIncludeFilter("filter1");
        verify(mockTest1).addIncludeFilter("filter2");
        verify(mockTest2).addIncludeFilter("filter3");
    }

    @Test
    public void testApplyIncludeFilters_canonicalizeConfigName() {
        LinkedHashMap<String, IConfiguration> configMap = new LinkedHashMap<>();
        IConfiguration mockConfig = mock(IConfiguration.class);
        // Canonical name should be "module1"
        when(mockConfig.getName()).thenReturn("/foo/bar/module1.config");
        ITestFilterReceiver mockTest =
                mock(ITestFilterReceiver.class, withSettings().extraInterfaces(IRemoteTest.class));
        when(mockConfig.getTests()).thenReturn(Arrays.asList((IRemoteTest) mockTest));
        configMap.put("module1", mockConfig);

        AtestUtils.applyIncludeFilters(configMap, Arrays.asList("module1:filter1"));

        verify(mockTest).addIncludeFilter("filter1");
    }

    @Test
    public void testApplyIncludeFilters_testDoesNotImplementIFilterReceiver() {
        LinkedHashMap<String, IConfiguration> configMap = new LinkedHashMap<>();
        IConfiguration mockConfig = mock(IConfiguration.class);
        when(mockConfig.getName()).thenReturn("module1");

        ITestFilterReceiver mockTestSupporting =
                mock(ITestFilterReceiver.class, withSettings().extraInterfaces(IRemoteTest.class));
        IRemoteTest mockTestNotSupporting = mock(IRemoteTest.class);
        when(mockConfig.getTests())
                .thenReturn(Arrays.asList((IRemoteTest) mockTestSupporting, mockTestNotSupporting));
        configMap.put("module1", mockConfig);

        AtestUtils.applyIncludeFilters(configMap, Arrays.asList("module1:filter1"));

        verify(mockTestSupporting).addIncludeFilter("filter1");
        verifyNoMoreInteractions(mockTestNotSupporting);
    }

    @Test
    public void testApplyIncludeFilters_nonExistentModule() {
        LinkedHashMap<String, IConfiguration> configMap = new LinkedHashMap<>();
        IConfiguration mockConfig = mock(IConfiguration.class);
        when(mockConfig.getName()).thenReturn("module1");
        ITestFilterReceiver mockTest =
                mock(ITestFilterReceiver.class, withSettings().extraInterfaces(IRemoteTest.class));
        when(mockConfig.getTests()).thenReturn(Arrays.asList((IRemoteTest) mockTest));
        configMap.put("module1", mockConfig);

        // The filter does not apply to this module.
        AtestUtils.applyIncludeFilters(configMap, Arrays.asList("module2:filter1"));

        verify(mockTest, Mockito.never()).addIncludeFilter(Mockito.anyString());
    }

    @Test
    public void testApplyIncludeFilters_emptyFilters() {
        LinkedHashMap<String, IConfiguration> configMap = new LinkedHashMap<>();
        configMap.put("module1", mMockConfig);

        AtestUtils.applyIncludeFilters(configMap, new ArrayList<>());

        verify(mMockConfig, Mockito.never()).getTests();
    }

    @Test(expected = RuntimeException.class)
    public void testApplyIncludeFilters_invalidFormat() {
        LinkedHashMap<String, IConfiguration> configMap = new LinkedHashMap<>();
        configMap.put("module1", mMockConfig);

        // Missing ':'
        AtestUtils.applyIncludeFilters(configMap, Arrays.asList("module1filter1"));
    }
}
