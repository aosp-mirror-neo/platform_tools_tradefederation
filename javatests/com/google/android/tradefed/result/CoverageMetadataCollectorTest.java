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

package com.google.android.tradefed.result;

import static com.google.android.tradefed.result.CoverageMetadataCollector.CLANG_BINARY_EXCLUDE_FILTER;
import static com.google.android.tradefed.result.CoverageMetadataCollector.CLANG_BINARY_INCLUDE_FILTER;
import static com.google.android.tradefed.result.CoverageMetadataCollector.CLANG_EXCLUDE_FILTER;
import static com.google.android.tradefed.result.CoverageMetadataCollector.CLANG_INCLUDE_FILTER;
import static com.google.android.tradefed.result.CoverageMetadataCollector.GCOV_EXCLUDE_FILTER;
import static com.google.android.tradefed.result.CoverageMetadataCollector.GCOV_INCLUDE_FILTER;
import static com.google.android.tradefed.result.CoverageMetadataCollector.JACOCO_EXCLUDE_FILTER;
import static com.google.android.tradefed.result.CoverageMetadataCollector.JACOCO_INCLUDE_FILTER;
import static com.google.android.tradefed.result.CoverageMetadataCollector.METADATA_COMPONENT;
import static com.google.android.tradefed.result.CoverageMetadataCollector.METADATA_COVERAGE_FILTER;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.util.MultiMap;

import com.google.android.tradefed.build.RemoteBuildInfo;
import com.google.common.collect.ImmutableList;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.Component;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.CoverageMeasurement;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.CoverageMetadata;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.TestArtifact;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Unit tests for {@link CoverageMetadataCollector}. */
@RunWith(JUnit4.class)
public final class CoverageMetadataCollectorTest {

    // Fake BuildInfo
    private static final String BUILD_TYPE = "submitted";
    private static final String BUILD_ID = "1234";
    private static final String BUILD_FLAVOR = "bullhead-userdebug_coverage";
    private static final String BUILD_TARGET = BUILD_FLAVOR;
    private static final String BUILD_ATTEMPT = "1";
    private static final long TEST_RESULT_ID = 9999;
    private static final BuildInfo BUILD_INFO = buildInfo(BUILD_ID, BUILD_FLAVOR, BUILD_ATTEMPT);

    // Framework configuration.
    private static final String FRAMEWORK_COMPONENT_NAME = "framework";
    private static final ImmutableList<String> FRAMEWORK_JACK_FILTERS =
            ImmutableList.of("glob:**/framework_intermediates/coverage.em");

    // Wifi configuration.
    private static final String WIFI_COMPONENT_NAME = "wifi";
    private static final ImmutableList<String> WIFI_JACK_FILTERS =
            ImmutableList.of(
                    "glob:**/wifi-service_intermediates/coverage.em",
                    "glob:**/framework_intermediates/coverage.em");
    private static final ImmutableList<String> WIFI_BINARY_INCLUDES =
            ImmutableList.of("glob:**/libwifi.so");
    private static final ImmutableList<String> WIFI_BINARY_EXCLUDES =
            ImmutableList.of("glob:**/libwifi-dependency.so");
    private static final ImmutableList<String> WIFI_INCLUDES =
            ImmutableList.of(
                    "glob:**/android/net/wifi/**.class",
                    "glob:**/com/android/server/wifi/**.class");
    private static final ImmutableList<String> WIFI_EXCLUDES =
            ImmutableList.of("glob:**/com/android/server/wifi/nano/WifiMetricsProto*.class");
    private static final String METADATA_ZIP_FILE = "all-classes.jar";

    // Fake invocation info.
    private static final String RUN_NAME = "TestRun";
    private static final int TEST_COUNT = 5;
    private static final long RUN_TIME = 10000;
    private static final HashMap<String, Metric> RUN_METRICS = new HashMap<>();
    private static final long INVOCATION_TIME = 15000;

    // Fake test logs.
    private static final String MEASUREMENT1_PATH =
            "git_oc-release/1234/apct/wifi/coverage/inv_1111/TestRun_runtime_coverage_1234.ec";
    private static final LogFile MEASUREMENT1_LOGFILE =
            logFile(MEASUREMENT1_PATH, LogDataType.COVERAGE);
    private static final String MEASUREMENT2_PATH =
            "git_oc-release/1234/apct/wifi/coverage/inv_1111/TestRun_runtime_coverage_5678.ec";
    private static final LogFile MEASUREMENT2_LOGFILE =
            logFile(MEASUREMENT2_PATH, LogDataType.COVERAGE);
    private static final String GCOV_MEASUREMENT1_PATH =
            "git_oc-release/1234/bionic/libc/inv_1111/TestRun_runtime_coverage_1234.zip";
    private static final LogFile GCOV_MEASUREMENT1_LOGFILE =
            logFile(GCOV_MEASUREMENT1_PATH, LogDataType.NATIVE_COVERAGE);
    private static final String GCOV_MEASUREMENT2_PATH =
            "git_oc-release/1234/bionic/libc/inv_1111/TestRun_runtime_coverage_5678.zip";
    private static final LogFile GCOV_MEASUREMENT2_LOGFILE =
            logFile(GCOV_MEASUREMENT2_PATH, LogDataType.NATIVE_COVERAGE);
    private static final String GCOV_KERNEL_MEASUREMENT1_PATH =
            "git_master/1234/cts-kernel-presubmit/inv_1111/"
                    + "CtsBionicTestCases_1678892862334_kernel_coverage_2222.tar.gz";
    private static final LogFile GCOV_KERNEL_MEASUREMENT1_LOGFILE =
            logFile(GCOV_KERNEL_MEASUREMENT1_PATH, LogDataType.GCOV_KERNEL_COVERAGE);

    // Fake CoverageMeasurement proto messages.
    private static final CoverageMeasurement MEASUREMENT1 = coverageMeasurement(MEASUREMENT1_PATH);
    private static final CoverageMeasurement MEASUREMENT2 = coverageMeasurement(MEASUREMENT2_PATH);

    private static final String LOG_ROOT_PATH = "/tmp/logs";

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    CoverageMetadataCollector mCollector;

    @Mock AndroidBuildApiLogSaver mLogSaver;

    @Before
    public void setup() {
        // Configure mocks.
        when(mLogSaver.getTestResultId()).thenReturn(TEST_RESULT_ID);
        when(mLogSaver.getTestResultBuildId()).thenReturn(BUILD_ID);
        when(mLogSaver.getTestResultBuildTarget()).thenReturn(BUILD_TARGET);
        when(mLogSaver.getLogRootPath()).thenReturn(new File(LOG_ROOT_PATH));
        mCollector = new CoverageMetadataCollector();
        mCollector.setCoverage(true);
        mCollector.setLogSaver(mLogSaver);
        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);
    }

    @Test
    public void testGetBuildType_noPrefix() {
        // Set up test data.
        IBuildInfo buildInfo = new BuildInfo("1234", "bullhead-userdebug");

        // Verify that a non-prefixed build is "submitted".
        assertThat(CoverageMetadataCollector.getBuildType(buildInfo.getBuildId()))
                .isEqualTo(RemoteBuildInfo.BuildType.SUBMITTED.toString());
    }

    @Test
    public void testGetBuildType_externalPrefix() {
        // Set up test data.
        IBuildInfo buildInfo = new BuildInfo("E5678", "bullhead-userdebug");

        // Verify that a build prefixed with "E" is "external".
        assertThat(CoverageMetadataCollector.getBuildType(buildInfo.getBuildId()))
                .isEqualTo(RemoteBuildInfo.BuildType.EXTERNAL.toString());
    }

    @Test
    public void testGetBuildType_pendingPrefix() {
        // Set up test data.
        IBuildInfo buildInfo = new BuildInfo("P9876", "bullhead-userdebug");

        // Verify that a build prefixed with "P" is "pending".
        assertThat(CoverageMetadataCollector.getBuildType(buildInfo.getBuildId()))
                .isEqualTo(RemoteBuildInfo.BuildType.PENDING.toString());
    }

    @Test
    public void testGetBuildType_trainPrefix() {
        // Set up test data.
        IBuildInfo buildInfo = new BuildInfo("T9876", "bullhead-userdebug");

        // Verify that a build prefixed with "T" is "train".
        assertThat(CoverageMetadataCollector.getBuildType(buildInfo.getBuildId()))
                .isEqualTo(RemoteBuildInfo.BuildType.TRAIN.toString());
    }

    @Test
    public void testGetBuildType_unknownPrefix() {
        // Set up test data.
        IBuildInfo buildInfo = new BuildInfo("X5432", "bullhead-userdebug");

        // Verify that unknown prefixes default to "submitted".
        assertThat(CoverageMetadataCollector.getBuildType(buildInfo.getBuildId()))
                .isEqualTo(RemoteBuildInfo.BuildType.SUBMITTED.toString());
    }

    @Test
    public void testMeasurements_skipIfNone() throws IOException {
        // Set up configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run without any measurements.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Verify that no metadata proto was uploaded.
        verify(mLogSaver, never())
                .saveLogDataRaw(
                        eq("coverage_metadata_JACK"), eq(LogDataType.PB), any(InputStream.class));
    }

    @Test
    public void testMeasurements_multiple() throws IOException {
        // Set up configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT2_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_JACK"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains both measurements.
        assertThat(metadataProto.getMeasurementsList())
                .containsExactlyElementsIn(Arrays.asList(MEASUREMENT1, MEASUREMENT2));
    }

    @Test
    public void testMeasurements_defaultBuildAttempt() throws IOException {
        // Set up configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up the context.
        BuildInfo unknownAttemptId = buildInfo(BUILD_ID, BUILD_FLAVOR);
        InvocationContext mainContext = getInvocationContext(unknownAttemptId, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_JACK"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that the measurement is correct.
        CoverageMeasurement.Builder expectedMeasurement = MEASUREMENT1.toBuilder();
        expectedMeasurement
                .getTestArtifactBuilder()
                .setAttemptId(AndroidBuildResultReporter.DEFAULT_BUILD_ATTEMPT_ID);
        assertThat(metadataProto.getMeasurementsList())
                .containsExactly(expectedMeasurement.build());
    }

    @Test
    public void testMeasurements_logAssociation() throws IOException {
        // Set up configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);
        mCollector.setEnableLogAssociation(true);

        // Set up the context.
        BuildInfo unknownAttemptId = buildInfo(BUILD_ID, BUILD_FLAVOR);
        InvocationContext mainContext = getInvocationContext(unknownAttemptId, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.logAssociation("unused", MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_JACK"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that the measurement is correct.
        CoverageMeasurement.Builder expectedMeasurement = MEASUREMENT1.toBuilder();
        expectedMeasurement
                .getTestArtifactBuilder()
                .setAttemptId(AndroidBuildResultReporter.DEFAULT_BUILD_ATTEMPT_ID);
        assertThat(metadataProto.getMeasurementsList())
                .containsExactly(expectedMeasurement.build());
    }

    @Test
    public void testComponents_errorIfNone() {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run, but expect a failure during testRunStarted(..).
        mCollector.invocationStarted(mainContext);
        try {
            mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
            fail("Exception not thrown");
        } catch (RuntimeException e) {
            // Expected
        }
    }

    @Test
    public void testComponents_multiple() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(wifiKey, s));

        metadata.put(METADATA_COMPONENT, FRAMEWORK_COMPONENT_NAME);
        String frameworkKey = String.format(METADATA_COVERAGE_FILTER, FRAMEWORK_COMPONENT_NAME);
        FRAMEWORK_JACK_FILTERS.stream().forEach(s -> metadata.put(frameworkKey, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_JACK"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains both components.
        assertThat(metadataProto.getComponentsList())
                .containsExactly(
                        getJackComponent(WIFI_COMPONENT_NAME, WIFI_JACK_FILTERS),
                        getJackComponent(FRAMEWORK_COMPONENT_NAME, FRAMEWORK_JACK_FILTERS));
    }

    @Test
    public void testMetadataZip_setMetadataZip() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiIncludes = String.format(JACOCO_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));

        // Set the metadata zip
        mCollector.setMetadataZip(METADATA_ZIP_FILE);

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACOCO);

        // Set up the context
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_JACOCO"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        assertThat(metadataProto.getMetadataZip()).isEqualTo(METADATA_ZIP_FILE);
    }

    @Test
    public void testComponents_emptyComponentSkipped() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiIncludes = String.format(JACOCO_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));
        metadata.put(METADATA_COMPONENT, "empty_component");

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACOCO);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_JACOCO"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent = getJacocoComponent("wifi", WIFI_INCLUDES, ImmutableList.of());
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testComponents_missingJacocoExcludes() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiIncludes = String.format(JACOCO_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACOCO);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_JACOCO"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent = getJacocoComponent("wifi", WIFI_INCLUDES, ImmutableList.of());
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testComponents_missingGcovExcludes() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiIncludes = String.format(GCOV_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.GCOV);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.NATIVE_COVERAGE, null, GCOV_MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_GCOV"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent = getGcovComponent("wifi", WIFI_INCLUDES, ImmutableList.of());
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testConfigurationMetadata_onlyJackSet() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_JACK"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        assertThat(metadataProto.getComponentsList())
                .containsExactly(getJackComponent(WIFI_COMPONENT_NAME, WIFI_JACK_FILTERS));
    }

    @Test
    public void testConfigurationMetadata_onlyJacocoSet() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiIncludes = String.format(JACOCO_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));

        String wifiExcludes = String.format(JACOCO_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(wifiExcludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACOCO);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_JACOCO"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent = getJacocoComponent("wifi", WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testConfigurationMetadata_onlyGcovSet() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiIncludes = String.format(GCOV_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));

        String wifiExcludes = String.format(GCOV_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(wifiExcludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.GCOV);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.NATIVE_COVERAGE, null, GCOV_MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_GCOV"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent = getGcovComponent("wifi", WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testConfigurationMetadata_onlyClangSet() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiBinaryIncludes = String.format(CLANG_BINARY_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_BINARY_INCLUDES.stream().forEach(s -> metadata.put(wifiBinaryIncludes, s));

        String wifiBinaryExcludes = String.format(CLANG_BINARY_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_BINARY_EXCLUDES.stream().forEach(s -> metadata.put(wifiBinaryExcludes, s));

        String wifiIncludes = String.format(CLANG_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));

        String wifiExcludes = String.format(CLANG_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(wifiExcludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.CLANG);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.CLANG_COVERAGE, null, GCOV_MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_CLANG"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent =
                getClangComponent(
                        "wifi",
                        WIFI_BINARY_INCLUDES,
                        WIFI_BINARY_EXCLUDES,
                        WIFI_INCLUDES,
                        WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testConfigurationMetadata_allSetWithJackToolchain() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        String jacocoWifiIncludes = String.format(JACOCO_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(jacocoWifiIncludes, s));

        String jacocoWifiExcludes = String.format(JACOCO_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(jacocoWifiExcludes, s));

        String gcovWifiIncludes = String.format(GCOV_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(gcovWifiIncludes, s));

        String gcovWifiExcludes = String.format(GCOV_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(gcovWifiExcludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_JACK"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        assertThat(metadataProto.getComponentsList())
                .containsExactly(getJackComponent(WIFI_COMPONENT_NAME, WIFI_JACK_FILTERS));
    }

    @Test
    public void testConfigurationMetadata_allSetWithJacocoToolchain() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        String jacocoWifiIncludes = String.format(JACOCO_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(jacocoWifiIncludes, s));

        String jacocoWifiExcludes = String.format(JACOCO_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(jacocoWifiExcludes, s));

        String gcovWifiIncludes = String.format(GCOV_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(gcovWifiIncludes, s));

        String gcovWifiExcludes = String.format(GCOV_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(gcovWifiExcludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACOCO);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_JACOCO"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent =
                getJacocoComponent(WIFI_COMPONENT_NAME, WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testConfigurationMetadata_allSetWithGcovToolchain() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        String jacocoWifiIncludes = String.format(JACOCO_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(jacocoWifiIncludes, s));

        String jacocoWifiExcludes = String.format(JACOCO_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(jacocoWifiExcludes, s));

        String gcovWifiIncludes = String.format(GCOV_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(gcovWifiIncludes, s));

        String gcovWifiExcludes = String.format(GCOV_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(gcovWifiExcludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.GCOV);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.NATIVE_COVERAGE, null, GCOV_MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_GCOV"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent =
                getGcovComponent(WIFI_COMPONENT_NAME, WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testConfigurationMetadata_allSetWithGcovAndJacocoToolchains() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        metadata.put(METADATA_COMPONENT, "wifi_native");
        String jackFilterKey = String.format(METADATA_COVERAGE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_JACK_FILTERS.stream().forEach(s -> metadata.put(jackFilterKey, s));

        String jacocoWifiIncludes = String.format(JACOCO_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(jacocoWifiIncludes, s));

        String jacocoWifiExcludes = String.format(JACOCO_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(jacocoWifiExcludes, s));

        String gcovWifiIncludes = String.format(GCOV_INCLUDE_FILTER, "wifi_native");
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(gcovWifiIncludes, s));

        String gcovWifiExcludes = String.format(GCOV_EXCLUDE_FILTER, "wifi_native");
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(gcovWifiExcludes, s));

        mCollector.setToolchains(
                ImmutableList.of(
                        CoverageMetadataCollector.Toolchain.GCOV,
                        CoverageMetadataCollector.Toolchain.JACOCO));

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.NATIVE_COVERAGE, null, GCOV_MEASUREMENT1_LOGFILE);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the gcov metadata proto.
        ArgumentCaptor<InputStream> gcovCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_GCOV"), eq(LogDataType.PB), gcovCaptor.capture());
        CoverageMetadata gcovMetadataProto = CoverageMetadata.parseFrom(gcovCaptor.getValue());

        // Capture the JaCoCo metadata proto.
        ArgumentCaptor<InputStream> jacocoCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_JACOCO"), eq(LogDataType.PB), jacocoCaptor.capture());
        CoverageMetadata jacocoMetadataProto = CoverageMetadata.parseFrom(jacocoCaptor.getValue());

        // Verify that each metadata contains the right component.
        Component expectedGcovComponent =
                getGcovComponent("wifi_native", WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(gcovMetadataProto.getComponentsList()).containsExactly(expectedGcovComponent);

        Component expectedJacocoComponent =
                getJacocoComponent(WIFI_COMPONENT_NAME, WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(jacocoMetadataProto.getComponentsList())
                .containsExactly(expectedJacocoComponent);
    }

    @Test
    public void testJacocoConfigurationMetadata_moduleContext() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiIncludes = String.format(JACOCO_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));

        String wifiExcludes = String.format(JACOCO_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(wifiExcludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACOCO);

        // Set up the module context.
        InvocationContext mainContext = new InvocationContext();
        InvocationContext moduleContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testModuleStarted(moduleContext);
        mainContext.setModuleInvocationContext(moduleContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.testModuleEnded();
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_JACOCO"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent = getJacocoComponent("wifi", WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testGcovConfigurationMetadata_moduleContext() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);
        String wifiIncludes = String.format(GCOV_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));

        String wifiExcludes = String.format(GCOV_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(wifiExcludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.GCOV);

        // Set up the module context.
        InvocationContext mainContext = new InvocationContext();
        InvocationContext moduleContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testModuleStarted(moduleContext);
        mainContext.setModuleInvocationContext(moduleContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.NATIVE_COVERAGE, null, GCOV_MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.testModuleEnded();
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_GCOV"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent = getGcovComponent("wifi", WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testClangConfigurationMetadata_moduleContext() throws IOException {
        // Set configuration metadata.
        MultiMap<String, String> metadata = new MultiMap<>();
        metadata.put(METADATA_COMPONENT, WIFI_COMPONENT_NAME);

        String wifiBinaryIncludes = String.format(CLANG_BINARY_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_BINARY_INCLUDES.stream().forEach(s -> metadata.put(wifiBinaryIncludes, s));

        String wifiBinaryExcludes = String.format(CLANG_BINARY_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_BINARY_EXCLUDES.stream().forEach(s -> metadata.put(wifiBinaryExcludes, s));

        String wifiIncludes = String.format(CLANG_INCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_INCLUDES.stream().forEach(s -> metadata.put(wifiIncludes, s));

        String wifiExcludes = String.format(CLANG_EXCLUDE_FILTER, WIFI_COMPONENT_NAME);
        WIFI_EXCLUDES.stream().forEach(s -> metadata.put(wifiExcludes, s));

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.CLANG);

        // Set up the module context.
        InvocationContext mainContext = new InvocationContext();
        InvocationContext moduleContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testModuleStarted(moduleContext);
        mainContext.setModuleInvocationContext(moduleContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.CLANG_COVERAGE, null, GCOV_MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.testModuleEnded();
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_CLANG"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent =
                getClangComponent(
                        "wifi",
                        WIFI_BINARY_INCLUDES,
                        WIFI_BINARY_EXCLUDES,
                        WIFI_INCLUDES,
                        WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testCommandLineOptions_onlyJackSet() throws IOException {
        // Set command line options.
        MultiMap<String, String> jackFilters = new MultiMap<>();
        WIFI_JACK_FILTERS.stream().forEach(s -> jackFilters.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJackFilters(jackFilters);
        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, new MultiMap<>());

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_JACK"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        assertThat(metadataProto.getComponentsList())
                .containsExactly(getJackComponent(WIFI_COMPONENT_NAME, WIFI_JACK_FILTERS));
    }

    @Test
    public void testCommandLineOptions_onlyGcovKernelSet() throws IOException {
        // Set command line options.
        mCollector.setGcovKernelComponentName(ImmutableList.of(WIFI_COMPONENT_NAME));
        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.GCOV_KERNEL);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, new MultiMap<>());

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.GCOV_KERNEL_COVERAGE, null, GCOV_KERNEL_MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_GCOV_KERNEL"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        assertThat(metadataProto.getComponentsList())
                .containsExactly(getGcovKernelComponent(WIFI_COMPONENT_NAME));
    }

    @Test
    public void testCommandLineOptions_errorIfComponentNameNotSetWithGcovKernel() {
        // Set command line options. (Exclude calling setGcovKernelComponentName())
        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.GCOV_KERNEL);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, new MultiMap<>());

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        try {
            mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
            fail("Exception not thrown");
        } catch (RuntimeException e) {
            // Expected
        }
    }

    /** Test when there are multiple test runs that each proto generated is properly populated. */
    @Test
    public void testCommandLineOptions_jacocoMultipleRuns() throws IOException {
        // Set command line options.
        MultiMap<String, String> includes = new MultiMap<>();
        WIFI_INCLUDES.stream().forEach(s -> includes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJacocoIncludes(includes);

        MultiMap<String, String> excludes = new MultiMap<>();
        WIFI_EXCLUDES.stream().forEach(s -> excludes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJacocoExcludes(excludes);

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACOCO);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, new MultiMap<>());

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.testRunStarted(RUN_NAME + "2", TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT2_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver, times(2))
                .saveLogDataRaw(
                        eq("coverage_metadata_JACOCO"), eq(LogDataType.PB), captor.capture());
        // Check that both protos (for each run) are populated properly
        for (InputStream capture : captor.getAllValues()) {
            CoverageMetadata metadataProto = CoverageMetadata.parseFrom(capture);
            // Verify that it contains the right component.
            Component expectedComponent = getJacocoComponent("wifi", WIFI_INCLUDES, WIFI_EXCLUDES);
            assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
        }
    }

    /** Test when there are multiple test runs that each proto generated is properly populated. */
    @Test
    public void testCommandLineOptions_gcovMultipleRuns() throws IOException {
        // Set command line options.
        MultiMap<String, String> includes = new MultiMap<>();
        WIFI_INCLUDES.stream().forEach(s -> includes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setGcovIncludes(includes);

        MultiMap<String, String> excludes = new MultiMap<>();
        WIFI_EXCLUDES.stream().forEach(s -> excludes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setGcovExcludes(excludes);

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.GCOV);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, new MultiMap<>());

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.NATIVE_COVERAGE, null, GCOV_MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.testRunStarted(RUN_NAME + "2", TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.NATIVE_COVERAGE, null, GCOV_MEASUREMENT2_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver, times(2))
                .saveLogDataRaw(eq("coverage_metadata_GCOV"), eq(LogDataType.PB), captor.capture());
        // Check that both protos (for each run) are populated properly
        for (InputStream capture : captor.getAllValues()) {
            CoverageMetadata metadataProto = CoverageMetadata.parseFrom(capture);
            // Verify that it contains the right component.
            Component expectedComponent = getGcovComponent("wifi", WIFI_INCLUDES, WIFI_EXCLUDES);
            assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
        }
    }

    @Test
    public void testCommandLineOptions_allSetWithJackToolchain() throws IOException {
        // Set command line options.
        MultiMap<String, String> jackFilters = new MultiMap<>();
        WIFI_JACK_FILTERS.stream().forEach(s -> jackFilters.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJackFilters(jackFilters);

        MultiMap<String, String> includes = new MultiMap<>();
        WIFI_INCLUDES.stream().forEach(s -> includes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJacocoIncludes(includes);
        mCollector.setGcovIncludes(includes);

        MultiMap<String, String> excludes = new MultiMap<>();
        WIFI_EXCLUDES.stream().forEach(s -> excludes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJacocoExcludes(excludes);
        mCollector.setGcovExcludes(excludes);

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, new MultiMap<>());

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_JACK"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        assertThat(metadataProto.getComponentsList())
                .containsExactly(getJackComponent(WIFI_COMPONENT_NAME, WIFI_JACK_FILTERS));
    }

    @Test
    public void testCommandLineOptions_allSetWithJacocoToolchain() throws IOException {
        // Set command line options.
        MultiMap<String, String> jackFilters = new MultiMap<>();
        WIFI_JACK_FILTERS.stream().forEach(s -> jackFilters.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJackFilters(jackFilters);

        MultiMap<String, String> includes = new MultiMap<>();
        WIFI_INCLUDES.stream().forEach(s -> includes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJacocoIncludes(includes);
        mCollector.setGcovIncludes(includes);

        MultiMap<String, String> excludes = new MultiMap<>();
        WIFI_EXCLUDES.stream().forEach(s -> excludes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJacocoExcludes(excludes);
        mCollector.setGcovExcludes(excludes);

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACOCO);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, new MultiMap<>());

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved("unused", LogDataType.COVERAGE, null, MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(
                        eq("coverage_metadata_JACOCO"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent =
                getJacocoComponent(WIFI_COMPONENT_NAME, WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testCommandLineOptions_allSetWithGcovToolchain() throws IOException {
        // Set command line options.
        MultiMap<String, String> jackFilters = new MultiMap<>();
        WIFI_JACK_FILTERS.stream().forEach(s -> jackFilters.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJackFilters(jackFilters);

        MultiMap<String, String> includes = new MultiMap<>();
        WIFI_INCLUDES.stream().forEach(s -> includes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJacocoIncludes(includes);
        mCollector.setGcovIncludes(includes);

        MultiMap<String, String> excludes = new MultiMap<>();
        WIFI_EXCLUDES.stream().forEach(s -> excludes.put(WIFI_COMPONENT_NAME, s));
        mCollector.setJacocoExcludes(excludes);
        mCollector.setGcovExcludes(excludes);

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.GCOV);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, new MultiMap<>());

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
        mCollector.testLogSaved(
                "unused", LogDataType.NATIVE_COVERAGE, null, GCOV_MEASUREMENT1_LOGFILE);
        mCollector.testRunEnded(RUN_TIME, RUN_METRICS);
        mCollector.invocationEnded(INVOCATION_TIME);

        // Capture the metadata proto.
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mLogSaver)
                .saveLogDataRaw(eq("coverage_metadata_GCOV"), eq(LogDataType.PB), captor.capture());
        CoverageMetadata metadataProto = CoverageMetadata.parseFrom(captor.getValue());

        // Verify that it contains the right component.
        Component expectedComponent =
                getGcovComponent(WIFI_COMPONENT_NAME, WIFI_INCLUDES, WIFI_EXCLUDES);
        assertThat(metadataProto.getComponentsList()).containsExactly(expectedComponent);
    }

    @Test
    public void testCommandLine_errorIfBothMetadataAreEmpty() {
        // No command line option are set
        // Set configuration metadata to an empty list: no metadata found in the configuration.
        MultiMap<String, String> metadata = new MultiMap<>();

        mCollector.setToolchain(CoverageMetadataCollector.Toolchain.JACK);

        // Set up the context.
        InvocationContext mainContext = getInvocationContext(BUILD_INFO, metadata);

        // Simulate a test run.
        mCollector.invocationStarted(mainContext);
        try {
            mCollector.testRunStarted(RUN_NAME, TEST_COUNT);
            fail("Exception not thrown");
        } catch (RuntimeException e) {
            // Expected
        }
    }

    private static Component getJacocoComponent(
            String name, List<String> includes, List<String> excludes) {
        Component.Builder component = Component.newBuilder().setName(name);
        component
                .getConfigurationBuilder()
                .getJacocoBuilder()
                .addAllIncludeFilters(includes)
                .addAllExcludeFilters(excludes);
        return component.build();
    }

    private static Component getClangComponent(
            String name,
            List<String> binaryIncludes,
            List<String> binaryExcludes,
            List<String> includes,
            List<String> excludes) {
        Component.Builder component = Component.newBuilder().setName(name);
        component
                .getConfigurationBuilder()
                .getClangBuilder()
                .addAllBinaryIncludeFilters(binaryIncludes)
                .addAllBinaryExcludeFilters(binaryExcludes)
                .addAllIncludeFilters(includes)
                .addAllExcludeFilters(excludes);
        return component.build();
    }

    private static Component getGcovKernelComponent(String name) {
        Component.Builder component = Component.newBuilder().setName(name);
        component.getConfigurationBuilder().getGcovKernelBuilder();
        return component.build();
    }

    private static Component getGcovComponent(
            String name, List<String> includes, List<String> excludes) {
        Component.Builder component = Component.newBuilder().setName(name);
        component
                .getConfigurationBuilder()
                .getGcovBuilder()
                .addAllIncludeFilters(includes)
                .addAllExcludeFilters(excludes);
        return component.build();
    }

    private static Component getJackComponent(String name, List<String> filters) {
        Component.Builder component = Component.newBuilder().setName(name);
        component.getConfigurationBuilder().getJackBuilder().addAllMetadataFilters(filters);
        return component.build();
    }

    /** Returns a {@link BuildInfo} with the given values. */
    private static BuildInfo buildInfo(String buildId, String buildFlavor) {
        BuildInfo ret = new BuildInfo(buildId, "unused");
        ret.setBuildFlavor(buildFlavor);
        return ret;
    }

    /** Returns a {@link BuildInfo} with the given values. */
    private static BuildInfo buildInfo(String buildId, String buildFlavor, String buildAttempt) {
        BuildInfo ret = buildInfo(buildId, buildFlavor);
        ret.addBuildAttribute(AndroidBuildResultReporter.BUILD_ATTEMPT_ID, buildAttempt);
        return ret;
    }

    /** Returns an {@link InvocationContext} with the given values. */
    private InvocationContext getInvocationContext(
            IBuildInfo build, MultiMap<String, String> metadata) {
        ConfigurationDescriptor configuration = new ConfigurationDescriptor();
        configuration.setMetaData(metadata);

        InvocationContext context = new InvocationContext();
        context.setConfigurationDescriptor(configuration);
        context.addDeviceBuildInfo(ConfigurationDef.DEFAULT_DEVICE_NAME, build);

        return context;
    }

    /** Returns a {@link CoverageMeasurement} message for the given {@code resourceId}. */
    private static CoverageMeasurement coverageMeasurement(String resourceId) {
        return CoverageMeasurement.newBuilder().setTestArtifact(testArtifact(resourceId)).build();
    }

    /** Returns a {@link TestArtifact} message for the given {@code resourceId}. */
    private static TestArtifact testArtifact(String resourceId) {
        return TestArtifact.newBuilder()
                .setBuildType(BUILD_TYPE)
                .setBuildId(BUILD_ID)
                .setTarget(BUILD_FLAVOR)
                .setAttemptId(BUILD_ATTEMPT)
                .setTestResultId(TEST_RESULT_ID)
                .setResourceId(resourceId)
                .build();
    }

    /** Returns a {@link LogFile} for the given {@code resourceId} and data type. */
    private static LogFile logFile(String resourceId, LogDataType dataType) {
        return new LogFile(
                Paths.get(LOG_ROOT_PATH).resolve(resourceId).toString(), "unused", dataType);
    }
}
