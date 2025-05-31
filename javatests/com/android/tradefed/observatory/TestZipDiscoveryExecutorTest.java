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

package com.android.tradefed.observatory;

import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.command.ICommandOptions;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.sandbox.SandboxOptions;
import com.android.tradefed.sandbox.TradefedSandbox; // For calling its static method directly
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.ITestSuite;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Unit tests for {@link TestZipDiscoveryExecutor}. */
@RunWith(JUnit4.class)
public class TestZipDiscoveryExecutorTest {

    private TestDiscoveryUtil mTestDiscoveryUtil;
    private IConfiguration mMockConfiguration;
    private ICommandOptions mMockCommandOptions;
    private IDeviceConfiguration mMockDeviceConfiguration;
    private IBuildProvider mMockDiscoverableBuildProvider;
    private ITestSuite mMockTestSuite;
    private IRemoteTest mMockRemoteTest;

    private TestZipDiscoveryExecutor mTestZipDiscoveryExecutor;

    @Before
    public void setUp() throws Exception {
        mTestDiscoveryUtil = mock(TestDiscoveryUtil.class);
        mMockConfiguration = mock(IConfiguration.class);
        mMockCommandOptions = mock(com.android.tradefed.command.ICommandOptions.class);
        mMockDeviceConfiguration = mock(IDeviceConfiguration.class);
        mMockDiscoverableBuildProvider =
                mock(
                        IBuildProvider.class,
                        withSettings().extraInterfaces(IDiscoverDependencies.class));
        mMockTestSuite = mock(ITestSuite.class);
        mMockRemoteTest = mock(IRemoteTest.class);

        mTestZipDiscoveryExecutor = new TestZipDiscoveryExecutor(mTestDiscoveryUtil);

        // Default behaviors for instance mocks
        when(mTestDiscoveryUtil.getConfiguration(any(String[].class)))
                .thenReturn(mMockConfiguration);
        when(mMockConfiguration.getCommandOptions()).thenReturn(mMockCommandOptions);
        when(mMockConfiguration.getTests()).thenReturn(Collections.singletonList(mMockRemoteTest));
        when(mMockConfiguration.getName()).thenReturn("mockConfig");

        lenient().when(mMockCommandOptions.shouldUseSandboxing()).thenReturn(false);
        lenient().when(mMockCommandOptions.shouldUseRemoteSandboxMode()).thenReturn(false);
        lenient()
                .when(mMockConfiguration.getConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME))
                .thenReturn(null);
    }

    @After
    public void tearDown() {
        // Reset the static field in the class under test
        new TestZipDiscoveryExecutor(null);
    }

    private void assertJsonOutput(String jsonString, Set<String> expectedZips, boolean noPossibleDiscovery)
            throws JSONException {
        JSONObject json = new JSONObject(jsonString);
        assertTrue(
                "JSON output missing '" + TestDiscoveryInvoker.TEST_ZIP_REGEXES_LIST_KEY + "' key.",
                json.has(TestDiscoveryInvoker.TEST_ZIP_REGEXES_LIST_KEY));
        JSONArray zipsArray = json.getJSONArray(TestDiscoveryInvoker.TEST_ZIP_REGEXES_LIST_KEY);
        assertEquals(
                "Number of zips in JSON output does not match expected.",
                expectedZips.size(),
                zipsArray.length());
        Set<String> actualZips = new LinkedHashSet<>();
        for (int i = 0; i < zipsArray.length(); i++) {
            actualZips.add(zipsArray.getString(i));
        }
        assertEquals("Set of zips in JSON output does not match expected.", expectedZips, actualZips);

        if (noPossibleDiscovery) {
            assertTrue(
                    "JSON output missing '"
                            + TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY
                            + "' key when expected.",
                    json.has(TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY));
            assertEquals(
                    "Value of '"
                            + TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY
                            + "' not 'true' as expected.",
                    "true",
                    json.getString(TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY));
        } else {
            assertFalse(
                    "JSON output has '"
                            + TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY
                            + "' key when not expected.",
                    json.has(TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY));
        }
    }

    // --- Tests for nonTradefedDiscovery ---

    @Test
    public void discoverTestZips_nonTradefed_unusedAndLiblyric_returnsCameraHalZip() throws Exception {
        String[] args = {"unused", "some-liblyric-arg"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(false);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        assertJsonOutput(result, Collections.singleton("camera-hal-tests.zip"), false);
    }

    @Test
    public void discoverTestZips_nonTradefed_haiku_returnsHaikuPresubmitZip() throws Exception {
        String[] args = {"contains_haiku_keyword"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(false);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        assertJsonOutput(result, Collections.singleton("haiku-presubmit"), false);
    }

    @Test
    public void discoverTestZips_nonTradefed_mobly_returnsGeneralTestsZip() throws Exception {
        String[] args = {"some_mobly_stuff"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(false);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        assertJsonOutput(result, Collections.singleton("general-tests.zip"), false);
    }

    @Test
    public void discoverTestZips_nonTradefed_multipleMatches_returnsAllZips() throws Exception {
        String[] args = {"contains_haiku_keyword", "some_mobly_stuff"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(false);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        assertJsonOutput(result, new LinkedHashSet<>(Arrays.asList("haiku-presubmit", "general-tests.zip")), false);
    }

    @Test
    public void discoverTestZips_nonTradefed_noMatches_returnsEmptySet() throws Exception {
        String[] args = {"random_arg"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(false);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        assertJsonOutput(result, Collections.emptySet(), false);
    }

    // --- Tests for Tradefed discovery path ---

    @Test
    public void discoverTestZips_tradefed_noTestsFound_throwsTestDiscoveryException() throws Exception {
        String[] args = {"config.xml"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mMockConfiguration.getTests()).thenReturn(null);
        try {
            mTestZipDiscoveryExecutor.discoverTestZips(args);
            fail("Should have thrown TestDiscoveryException for no tests found.");
        } catch (TestDiscoveryException e) {
            assertTrue(e.getMessage().contains("Tradefed Observatory discovered no tests from the IConfiguration"));
            assertEquals(DiscoveryExitCode.ERROR, e.exitCode());
        }
    }

    @Test
    public void discoverTestZips_tradefed_emptyTestsFound_throwsTestDiscoveryException() throws Exception {
        String[] args = {"config.xml"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mMockConfiguration.getTests()).thenReturn(Collections.emptyList());
        try {
            mTestZipDiscoveryExecutor.discoverTestZips(args);
            fail("Should have thrown TestDiscoveryException for empty test list.");
        } catch (TestDiscoveryException e) {
            assertTrue(e.getMessage().contains("Tradefed Observatory discovered no tests from the IConfiguration"));
            assertEquals(DiscoveryExitCode.ERROR, e.exitCode());
        }
    }

    @Test
    public void discoverTestZips_tradefed_useSandboxing_addsDefaultTradefedZips() throws Exception {
        String[] args = {"config.xml", "--use-sandboxing"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mMockCommandOptions.shouldUseSandboxing()).thenReturn(true);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        Set<String> expectedZips =
                new LinkedHashSet<>(
                        Arrays.asList(
                                "tradefed.zip",
                                "tradefed-all.zip",
                                "google-tradefed.zip",
                                "google-tradefed-all.zip"));
        assertJsonOutput(result, expectedZips, false);
    }

    @Test
    public void discoverTestZips_tradefed_useRemoteSandboxMode_addsDefaultTradefedZips() throws Exception {
        String[] args = {"config.xml", "--use-remote-sandbox-mode"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mMockCommandOptions.shouldUseRemoteSandboxMode()).thenReturn(true);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        Set<String> expectedZips =
                new LinkedHashSet<>(
                        Arrays.asList(
                                "tradefed.zip",
                                "tradefed-all.zip",
                                "google-tradefed.zip",
                                "google-tradefed-all.zip"));
        assertJsonOutput(result, expectedZips, false);
    }

    @Test
    public void discoverTestZips_tradefed_sandboxOptionsWithCustomZips_addsCustomZips() throws Exception {
        String[] args = {"config.xml"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        SandboxOptions realSandboxOptions = new SandboxOptions();
        OptionSetter setter = new OptionSetter(realSandboxOptions);
        setter.setOptionValue("sandbox-tests-zips", "custom-sandbox-1.zip");
        setter.setOptionValue("sandbox-tests-zips", "custom-sandbox-2.zip");
        when(mMockConfiguration.getConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME))
                .thenReturn(realSandboxOptions);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        Set<String> expectedZips = new LinkedHashSet<>(Arrays.asList("custom-sandbox-1.zip", "custom-sandbox-2.zip"));
        assertJsonOutput(result, expectedZips, false);
    }

    @Test
    public void discoverTestZips_tradefed_deviceConfigWithDiscoverDependencies_addsProviderZips() throws Exception {
        String[] args = {"config.xml"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mMockConfiguration.getDeviceConfig()).thenReturn(Collections.singletonList(mMockDeviceConfiguration));
        when(mMockDeviceConfiguration.getBuildProvider()).thenReturn(mMockDiscoverableBuildProvider);
        Set<String> providerZips = Collections.singleton("provider-dep.zip");
        when(((IDiscoverDependencies) mMockDiscoverableBuildProvider).reportTestZipFileFilter())
                .thenReturn(providerZips);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        assertJsonOutput(result, providerZips, false);
    }

    @Test
    public void discoverTestZips_tradefed_deviceConfigWithDiscoverDependenciesReturnsNull_noCrash() throws Exception {
        String[] args = {"config.xml"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mMockConfiguration.getDeviceConfig()).thenReturn(Collections.singletonList(mMockDeviceConfiguration));
        when(mMockDeviceConfiguration.getBuildProvider()).thenReturn(mMockDiscoverableBuildProvider);
        when(((IDiscoverDependencies) mMockDiscoverableBuildProvider).reportTestZipFileFilter())
                .thenReturn(null);
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        assertJsonOutput(result, Collections.emptySet(), false);
    }

    @Test
    public void discoverTestZips_tradefed_testSuiteWithSandboxMatching_addsExtraTargets() throws Exception {
        String[] args = {"config.xml"};
        String configName = "cts-suite"; // Example: ensure this name has known behavior with real static method
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mMockConfiguration.getTests()).thenReturn(Collections.singletonList(mMockTestSuite));
        SandboxOptions realSandboxOptions = new SandboxOptions();
        OptionSetter setter = new OptionSetter(realSandboxOptions);
        setter.setOptionValue("sandbox-default-zips", "true");
        when(mMockConfiguration.getConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME))
                .thenReturn(realSandboxOptions);
        when(mMockConfiguration.getName()).thenReturn(configName);

        // Calling the REAL static method.
        Set<String> expectedZipsFromRealStaticMethod = TradefedSandbox.matchSandboxExtraBuildTargetByConfigName(configName);
        System.out.println("Note: Test 'discoverTestZips_tradefed_testSuiteWithSandboxMatching_addsExtraTargets' " +
                "is calling the real TradefedSandbox.matchSandboxExtraBuildTargetByConfigName for config: '" + configName +
                "'. Expected output based on real method: " + expectedZipsFromRealStaticMethod);

        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        assertJsonOutput(result, expectedZipsFromRealStaticMethod, false);
    }


    @Test
    public void discoverTestZips_tradefed_nullTestZipInSetFromBuildProvider_throwsTestDiscoveryException() throws Exception {
        String[] args = {"config.xml"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mMockConfiguration.getDeviceConfig()).thenReturn(Collections.singletonList(mMockDeviceConfiguration));
        when(mMockDeviceConfiguration.getBuildProvider()).thenReturn(mMockDiscoverableBuildProvider);
        Set<String> zipsWithNull = new LinkedHashSet<>();
        zipsWithNull.add("valid.zip");
        zipsWithNull.add(null);
        when(((IDiscoverDependencies) mMockDiscoverableBuildProvider).reportTestZipFileFilter())
                .thenReturn(zipsWithNull);
        try {
            mTestZipDiscoveryExecutor.discoverTestZips(args);
            fail("Should have thrown TestDiscoveryException for null regex from build provider.");
        } catch (TestDiscoveryException e) {
            assertTrue(e.getMessage().contains("discovered null test zip regex"));
            assertEquals(DiscoveryExitCode.DISCOVERY_RESULTS_CORREPUTED, e.exitCode());
        }
    }

    @Test
    public void discoverTestZips_tradefed_noDiscoveriesMade_reportNoPossibleDiscoveryTrue() throws Exception {
        String[] args = {"config.xml"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mMockCommandOptions.shouldUseSandboxing()).thenReturn(false);
        when(mMockCommandOptions.shouldUseRemoteSandboxMode()).thenReturn(false);
        when(mMockConfiguration.getConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME)).thenReturn(null);
        when(mMockConfiguration.getDeviceConfig()).thenReturn(Collections.emptyList());
        when(mMockConfiguration.getTests()).thenReturn(Collections.singletonList(mMockRemoteTest));
        String result = mTestZipDiscoveryExecutor.discoverTestZips(args);
        assertJsonOutput(result, Collections.emptySet(), true);
    }

    @Test
    public void discoverTestZips_tradefed_hasOutputResultFile_executesWithoutError() throws Exception {
        System.out.println("Note: Static logging calls (DdmPreferences, Log, LogRegistry) " +
                "will use real implementations. This test only checks for runtime errors in this path.");
        String[] args = {"config.xml"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mTestDiscoveryUtil.hasOutputResultFile()).thenReturn(true);

        try {
            mTestZipDiscoveryExecutor.discoverTestZips(args);
        } catch (Exception e) {
            // Log the exception and fail, as this path should ideally not throw an unexpected error
            // even with real static calls, unless the real static calls themselves are problematic
            // in a test environment (e.g. LogRegistry.getLogRegistry().unregisterLogger() failing).
            e.printStackTrace();
            fail("discoverTestZips threw an unexpected exception when hasOutputResultFile=true: " + e.getMessage());
        }
        assertTrue("Test for hasOutputResultFile=true path completed execution.", true);
    }

    @Test
    public void discoverTestZips_tradefed_doesNotHaveOutputResultFile_executesWithoutError() throws Exception {
        String[] args = {"config.xml"};
        when(mTestDiscoveryUtil.isTradefedConfiguration(args)).thenReturn(true);
        when(mTestDiscoveryUtil.hasOutputResultFile()).thenReturn(false); // Key condition

        mTestZipDiscoveryExecutor.discoverTestZips(args); // Should execute without error

        assertTrue("Test for hasOutputResultFile=false path completed execution.", true);
    }
}