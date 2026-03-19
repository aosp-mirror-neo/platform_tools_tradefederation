/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tradefed.postprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.MockitoAnnotations.initMocks;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Dimension;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.DimensionalMetric;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.MetricRecord;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.MetricReport;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Polarity;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.RawValues;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Source;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.TestIdentifier;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.TestMetricResult;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Unit;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Value;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.postprocessor.PerfettoGenericPostProcessor.METRIC_FILE_FORMAT;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import perfetto.protos.PerfettoMergedMetrics.TraceMetrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link PerfettoGenericPostProcessor}. */
@RunWith(JUnit4.class)
public class PerfettoGenericPostProcessorTest {

    @Mock private ITestInvocationListener mListener;
    private PerfettoGenericPostProcessor mProcessor;
    private OptionSetter mOptionSetter;

    private static final String PREFIX_OPTION = "perfetto-proto-file-prefix";
    private static final String PREFIX_OPTION_VALUE = "metric-perfetto";
    private static final String INDEX_OPTION = "perfetto-indexed-list-field";
    private static final String KEY_PREFIX_OPTION = "perfetto-prefix-key-field";
    private static final String INNER_MSG_KEY_PREFIX_OPTION =
            "perfetto-prefix-inner-message-key-field";
    private static final String INDEXED_DUPLICATE_KEYS_OPTION =
            "perfetto-indexed-duplicate-keys-field";
    private static final String REGEX_OPTION_VALUE = "perfetto-metric-filter-regex";
    private static final String ALL_METRICS_OPTION = "perfetto-include-all-metrics";
    private static final String ALL_METRICS_PREFIX_OPTION = "perfetto-all-metric-prefix";
    private static final String REPLACE_REGEX_OPTION = "perfetto-metric-replace-prefix";
    private static final String FILE_FORMAT_OPTION = "trace-processor-output-format";
    private static final String ALTERNATIVE_PARSE_FORMAT_OPTION =
            "perfetto-alternative-parse-format";
    private static final String SKIP_MESSAGE_FIELD_OPTION = "perfetto-skip-message-field";

    File perfettoMetricProtoFile = null;
    File perfettoV2MetricProtoFile = null;

    private static final Boolean DEBUG = false;

    @Before
    public void setUp() throws ConfigurationException {
        initMocks(this);
        mProcessor = new PerfettoGenericPostProcessor();
        mProcessor.init(mListener);
        mOptionSetter = new OptionSetter(mProcessor);
    }

    /**
     * Test metrics count should be zero if "perfetto-include-all-metrics" is not set or set to
     * false;
     */
    @Test
    public void testNoMetricsByDefault() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertTrue(
                "Number of metrics parsed without indexing is incorrect.",
                parsedMetrics.size() == 0);
    }

    /**
     * Test metrics are filtered correctly when filter regex are passed and
     * "perfetto-include-all-metrics" is set to false (Note: by default false)
     */
    @Test
    public void testMetricsFilterWithRegEx() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        mOptionSetter.setOptionValue(REGEX_OPTION_VALUE, "android_startup-startup-1.*");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContainString(
                parsedMetrics, "perfetto_android_startup-startup-1-startup_id", 1);
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_startup-startup-1-package_name-com.google."
                        + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                36175473);
    }

    /**
     * Test metrics are filtered correctly when filter regex are passed and prefix are replaced with
     * the given string.
     */
    @Test
    public void testMetricsFilterWithRegExAndReplacePrefix()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        mOptionSetter.setOptionValue(REGEX_OPTION_VALUE, "android_startup-startup-1.*");
        mOptionSetter.setOptionValue(
                REPLACE_REGEX_OPTION, "android_startup-startup-1", "newprefix");

        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertFalse(
                "Metric key not expected but found",
                parsedMetrics.containsKey("android_startup-startup-1-startup_id"));
        assertMetricsContainString(parsedMetrics, "perfetto_newprefix-startup_id", 1);
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_newprefix-package_name-com.google."
                        + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                36175473);
    }

    /**
     * Test all metrics are included when "perfetto-include-all-metrics" is set to true and ignores
     * any of the filter regex set.
     */
    @Test
    public void testAllMetricsOptionIgnoresFilter() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        // Test for non startup metrics exists.
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /** Test that the post processor can parse reports from test metrics. */
    @Test
    public void testParsingTestMetrics() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processTestMetricsAndLogs(
                        new TestDescription("class", "test"), new HashMap<>(), testLogs);
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /** Test custom all metric suffix is applied correctly. */
    @Test
    public void testParsingWithAllMetricsPrefix() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        mOptionSetter.setOptionValue(ALL_METRICS_PREFIX_OPTION, "custom_all_prefix");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processTestMetricsAndLogs(
                        new TestDescription("class", "test"), new HashMap<>(), testLogs);
        assertMetricsContainString(
                parsedMetrics,
                "custom_all_prefix_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /** Test the post processor can parse reports from run metrics. */
    @Test
    public void testParsingRunMetrics() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /** Test Wattson metrics with indexing and inner message key field. */
    @Test
    public void testWattsonMetrics_withIndexingDuplicates()
            throws ConfigurationException, IOException {
        testWattsonMetrics(true);
    }

    /** Test Wattson metrics with indexing and inner message key field. */
    @Test
    public void testWattsonMetrics_withoutIndexingDuplicates()
            throws ConfigurationException, IOException {
        testWattsonMetrics(false);
    }

    private void testWattsonMetrics(boolean indexing) throws ConfigurationException, IOException {
        String wattsonContent =
                "wattson_atrace_apps_rails {\n"
                        + "  metric_version: 4\n"
                        + "  power_model_version: 1\n"
                        + "  is_crude_estimate: 0\n"
                        + "  period_info {\n"
                        + "    period_id: 1\n"
                        + "    period_name: \"SPLASHSCREEN_EXIT_ANIM\"\n"
                        + "    period_dur: 275232056\n"
                        + "    cpu_subsystem {\n"
                        + "      estimated_mw: 5640\n"
                        + "      estimated_mws: 1552\n"
                        + "    }\n"
                        + "  }\n"
                        + "  period_info {\n"
                        + "    period_id: 2\n"
                        + "    period_name: \"LAUNCHER_APP_CLOSE_TO_HOME\"\n"
                        + "    period_dur: 992449015\n"
                        + "    cpu_subsystem {\n"
                        + "      estimated_mw: 1683\n"
                        + "      estimated_mws: 1671\n"
                        + "      }\n"
                        + "  }\n"
                        + "  period_info {\n"
                        + "    period_id: 3\n"
                        + "    period_name: \"SPLASHSCREEN_EXIT_ANIM\"\n"
                        + "    period_dur: 281844035\n"
                        + "    cpu_subsystem {\n"
                        + "      estimated_mw: 822\n"
                        + "      estimated_mws: 231\n"
                        + "    }\n"
                        + "  }\n"
                        + "}";
        FileWriter fileWriter = null;
        try {
            perfettoMetricProtoFile = FileUtil.createTempFile("wattson_perfetto", "");
            fileWriter = new FileWriter(perfettoMetricProtoFile);
            fileWriter.write(wattsonContent);
        } finally {
            if (fileWriter != null) {
                fileWriter.close();
            }
        }

        if (indexing) {
            mOptionSetter.setOptionValue(INDEXED_DUPLICATE_KEYS_OPTION, "true");
        }
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(
                KEY_PREFIX_OPTION, "perfetto.protos.AndroidWattsonEstimateInfo.period_name");
        mOptionSetter.setOptionValue(
                REGEX_OPTION_VALUE,
                "wattson_atrace_apps_rails-period_info-period_name-*.*-cpu_subsystem-estimated_mw-*.*");
        mOptionSetter.setOptionValue(
                REGEX_OPTION_VALUE,
                "wattson_atrace_apps_rails-period_info-period_name-*.*-cpu_subsystem-estimated_mws-*.*");
        mOptionSetter.setOptionValue(
                REGEX_OPTION_VALUE,
                "wattson_atrace_apps_rails-period_info-period_name-*.*-period_dur-*.*");
        mOptionSetter.setOptionValue(
                REPLACE_REGEX_OPTION,
                "wattson_atrace_apps_rails-period_info-period_name-",
                "wattson_apps_rails-name-");

        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        if (DEBUG) {
            printOutputMetricsForDebug(parsedMetrics);
        }

        if (indexing) {
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_wattson_apps_rails-name-SPLASHSCREEN_EXIT_ANIM-cpu_subsystem-estimated_mw-1",
                    5640.0);
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_wattson_apps_rails-name-SPLASHSCREEN_EXIT_ANIM-cpu_subsystem-estimated_mw-2",
                    822.0);
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_wattson_apps_rails-name-SPLASHSCREEN_EXIT_ANIM-period_dur-1",
                    275232056);
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_wattson_apps_rails-name-SPLASHSCREEN_EXIT_ANIM-period_dur-2",
                    281844035);
        } else {
            // Without indexing, the last occurrence (period_id: 3) should overwrite the first
            // (period_id: 1).
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_wattson_apps_rails-name-SPLASHSCREEN_EXIT_ANIM-cpu_subsystem-estimated_mw",
                    822.0);
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_wattson_apps_rails-name-SPLASHSCREEN_EXIT_ANIM-period_dur",
                    281844035);
        }

        assertMetricsContainString(
                parsedMetrics,
                "perfetto_wattson_apps_rails-name-LAUNCHER_APP_CLOSE_TO_HOME-cpu_subsystem-estimated_mw",
                1683.0);
    }

    /**
     * Test metrics count and metrics without indexing. In case of app startup metrics startup
     * messages for same package name will be overridden without indexing.
     */
    @Test
    public void testParsingWithoutIndexingListsAndWithoutIndexingDuplicateKeys()
            throws ConfigurationException, IOException {
        testParsingStartupMetrics(false, false);
    }

    /**
     * Test metrics count and metrics without indexing. In case of app startup metrics startup
     * messages for same package name will be overridden without indexing.
     */
    @Test
    public void testParsingWithoutIndexingListsAndWithIndexingDuplicateKeys()
            throws ConfigurationException, IOException {
        testParsingStartupMetrics(false, true);
    }

    /**
     * Test metrics count and metrics with indexing. In case of app startup metrics, startup
     * messages for same package name will not be overridden with indexing.
     */
    @Test
    public void testParsingWithIndexingListsAndWithoutIndexingDuplicateKeys()
            throws ConfigurationException, IOException {
        testParsingStartupMetrics(true, false);
    }

    /**
     * Test metrics count and metrics with indexing. In case of app startup metrics, startup
     * messages for same package name will not be overridden with indexing.
     */
    @Test
    public void testParsingWithIndexingListsAndWithIndexingDuplicateKeys()
            throws ConfigurationException, IOException {
        testParsingStartupMetrics(true, true);
    }

    private void testParsingStartupMetrics(boolean indexLists, boolean indexDuplicates)
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        if (indexLists) {
            mOptionSetter.setOptionValue(
                    INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        }
        if (indexDuplicates) {
            mOptionSetter.setOptionValue(INDEXED_DUPLICATE_KEYS_OPTION, "true");
        }
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        if (indexLists) {
            assertMetricsContainString(
                    parsedMetrics, "perfetto_android_startup-startup-1-startup_id", 1);
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_android_startup-startup-1-package_name-com.google."
                            + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                    36175473);
            assertMetricsContainString(
                    parsedMetrics, "perfetto_android_startup-startup-2-startup_id", 2);
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_android_startup-startup-2-package_name-com.google."
                            + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                    53102401);

            // Make sure there are no indexed duplicate keys
            assertFalse(
                    "Metric key not expected but found",
                    parsedMetrics.containsKey("perfetto_android_startup-startup-1-startup_id-1"));
        } else if (indexDuplicates) {
            assertMetricsContainString(
                    parsedMetrics, "perfetto_android_startup-startup-startup_id-1", 1);
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_android_startup-startup-package_name-com.google."
                            + "android.apps.nexuslauncher-to_first_frame-dur_ns-1",
                    36175473);
            assertMetricsContainString(
                    parsedMetrics, "perfetto_android_startup-startup-startup_id-2", 2);
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_android_startup-startup-package_name-com.google."
                            + "android.apps.nexuslauncher-to_first_frame-dur_ns-2",
                    53102401);
        } else {
            assertMetricsContainString(
                    parsedMetrics, "perfetto_android_startup-startup-startup_id", 2);
            assertMetricsContainString(
                    parsedMetrics,
                    "perfetto_android_startup-startup-package_name-com.google."
                            + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                    53102401);

            // Make sure there are no indexed duplicate keys
            assertFalse(
                    "Metric key not expected but found",
                    parsedMetrics.containsKey("perfetto_android_startup-startup-startup_id-1"));
        }
    }

    /** Test metrics enabled with key and string value prefixing. */
    @Test
    public void testParsingWithKeyAndStringValuePrefixing()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(
                KEY_PREFIX_OPTION, "perfetto.protos.ProcessRenderInfo.process_name");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_hwui_metric-process_info-process_name-com.android.systemui-all_mem_min",
                15120269);
    }

    /** Test metrics enabled with multiple key and string value prefixing. */
    @Test
    public void testParsingWithMultipleKeyAndStringValuePrefixing()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(
                KEY_PREFIX_OPTION, "perfetto.protos.ProcessRenderInfo.process_name");
        mOptionSetter.setOptionValue(
                KEY_PREFIX_OPTION, "perfetto.protos.ProcessRenderInfo.rt_cpu_time_ms");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_hwui_metric-process_info-process_name-com.android.systemui-"
                        + "rt_cpu_time_ms-2481-all_mem_min",
                15120269);
    }

    /** Test metrics enabled with key and integer value prefixing. */
    @Test
    public void testParsingWithKeyAndIntegerValuePrefixing()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(
                KEY_PREFIX_OPTION, "perfetto.protos.AndroidCpuMetric.CoreData.id");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_cpu-process_info-name-com.google.android.apps.messaging-threads-name-BG"
                    + "_Thread_#1-core-id-1-metrics-runtime_ns",
                14376405);
    }

    /** Test the post processor can parse binary perfetto metric proto format. */
    @Test
    public void testParsingBinaryProto() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.binary, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        mOptionSetter.setOptionValue(FILE_FORMAT_OPTION, "binary");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.PB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /** Test the post processor can parse binary perfetto metric proto format. */
    @Test
    public void testNoSupportForJsonParsing() throws ConfigurationException, IOException {
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        mOptionSetter.setOptionValue(FILE_FORMAT_OPTION, "json");
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertTrue("Should not have any metrics if json format is set", parsedMetrics.size() == 0);
    }

    /**
     * Test the post processor can parse reports from run metrics when the text proto file is
     * compressed format.
     */
    @Test
    public void testParsingRunMetricsWithCompressedFile()
            throws ConfigurationException, IOException {
        // Setup compressed text proto metric file.
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /**
     * Test metrics are parsed correctly when there are files with and without corresponding proto
     * definition
     */
    @Test
    public void testMetricsWithAndWithoutProto() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, false);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALTERNATIVE_PARSE_FORMAT_OPTION, "json");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        File metricProtoFile = perfettoMetricProtoFile;
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        testLogs.put(
                PREFIX_OPTION_VALUE + "-2",
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContainString(
                parsedMetrics,
                "perfetto.protos.camera_app_metrics-camera_close_latencies-close_ms",
                388.299723);

        if (null != metricProtoFile) {
            metricProtoFile.delete();
        }
    }

    /**
     * Test metrics are parsed correctly when there are files with and without corresponding proto
     * definition
     */
    @Test
    public void testMetricsWithPrefixInnerMessageField()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(
                KEY_PREFIX_OPTION, "perfetto.protos.AndroidJankCujMetric.Cuj.name");
        mOptionSetter.setOptionValue(
                INNER_MSG_KEY_PREFIX_OPTION, "perfetto.protos.AndroidProcessMetadata.name");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        if (DEBUG) {
            printOutputMetricsForDebug(parsedMetrics);
        }
        assertMetricsContainString(
                parsedMetrics,
                "android_jank_cuj-cuj-name-com.android.systemui-name-NOTIFICATION_ADD-timeline_"
                        + "metrics-frame_dur_avg",
                5040562);
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_jank_cuj-cuj-name-NOTIFICATION_ADD-dur",
                460793302);
    }

    @Test
    public void testBlockingCallsMetric() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(KEY_PREFIX_OPTION, "perfetto.protos.AndroidBlockingCall.name");

        mOptionSetter.setOptionValue(
                REPLACE_REGEX_OPTION, "android_blocking_calls_cuj_metric", "android_blocking_call");
        mOptionSetter.setOptionValue(
                REGEX_OPTION_VALUE,
                "android_blocking_calls_cuj_metric.*calls-name-.*"
                        + "(min_dur_ms|max_dur|total_dur|cnt).*");

        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        if (DEBUG) {
            printOutputMetricsForDebug(parsedMetrics);
        }
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_blocking_call-cuj-name-TASKBAR_EXPAND-blocking_calls-name-AIDL"
                        + "::java::ITrustManager::isDeviceSecure::server-total_dur_ms",
                1);

        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_blocking_call-cuj-name-TASKBAR_EXPAND-blocking_calls-name-AIDL"
                        + "::java::ITrustManager::isDeviceSecure::server-min_dur_ms",
                0);
        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_blocking_call-cuj-name-TASKBAR_EXPAND-blocking_calls-name-AIDL"
                        + "::java::ITrustManager::isDeviceSecure::server-max_dur_ms",
                1);

        assertMetricsContainString(
                parsedMetrics,
                "perfetto_android_blocking_call-cuj-name-ACTION_REQUEST_IME_HIDDEN"
                        + "::HIDE_SOFT_INPUT-blocking_calls-name-AIDL::java::ITrustManager"
                        + "::isDeviceSecure::server-total_dur_ms",
                1);
    }

    /**
     * Test metrics are skipped correctly when skip field is passed and
     * "perfetto-include-all-metrics" is set to true.
     */
    @Test
    public void testSkipMessageField() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        // Skip the 'android_mem' field.
        mOptionSetter.setOptionValue(
                SKIP_MESSAGE_FIELD_OPTION, "perfetto.protos.TraceMetrics.android_mem");

        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        // android_mem metrics should be skipped.
        assertFalse(
                "android_mem metrics should have been skipped.",
                parsedMetrics.keySet().stream().anyMatch(key -> key.contains("android_mem")));
        // Other metrics should still be present.
        assertTrue(
                "android_startup metrics should still be present.",
                parsedMetrics.keySet().stream().anyMatch(key -> key.contains("android_startup")));
    }

    private void printOutputMetricsForDebug(Map<String, Metric.Builder> metrics) {
        System.out.println("\n\noutput metrics:\n\n");
        metrics.forEach((k, v) -> System.out.println(k + " value: " + v));
    }

    /** Test that post processor runtime is reported if metrics are present. */
    @Test
    public void testReportsRuntime() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        mOptionSetter.setOptionValue(REGEX_OPTION_VALUE, "android_startup-startup-1.*");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertTrue(parsedMetrics.containsKey(PerfettoGenericPostProcessor.RUNTIME_METRIC_KEY));
    }

    /** Test the default metric type is set to RAW. */
    @Test
    public void testMetricTypeIsRaw() {
        assertTrue(mProcessor.getMetricType().equals(DataType.RAW));
    }

    /** Test v2 metrics are filtered correctly */
    @Test
    public void testParsingRunV2Metrics() throws ConfigurationException, IOException {
        setupPerfettoV2MetricFile(METRIC_FILE_FORMAT.text, true);

        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoV2MetricProtoFile.getAbsolutePath(), "some.url", LogDataType.ZIP));
        Map<String, Metric.Builder> parsedV2Metrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        if (DEBUG) {
            printOutputMetricsForDebug(parsedV2Metrics);
        }
        // 6 test metrics + 1 perfetto_post_processor_runtime metric.
        assertEquals("count of metrics is 6", parsedV2Metrics.size(), 7);
        assertMetricsContainDoubleRow(
                parsedV2Metrics, "foo_bar-.ShannonImsService", Arrays.asList(123.0, 456.0));
        assertMetricsContainDouble(parsedV2Metrics, "foo_bar-null", 10464441.0);
        assertMetricsContainDouble(
                parsedV2Metrics, "baza-(Paused)KernelPreparation-1234567890-123", 260051.0);
        assertMetricsContainDouble(
                parsedV2Metrics, "baza-(Paused)KernelPreparation-12907-456", 158854.0);
        assertMetricsContainDouble(
                parsedV2Metrics, "biz-(Paused)KernelPreparation-12907-456", 345.0);
        assertMetricsContainDouble(
                parsedV2Metrics, "biz-(Paused)MarkingPause-1234567890-234", 321.0);
    }

    @Test
    public void testParsingMultipleRunV2Metrics() throws ConfigurationException, IOException {
        setupPerfettoV2MetricFile(METRIC_FILE_FORMAT.text, true);

        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();

        // Add the same file twice to test merging.
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoV2MetricProtoFile.getAbsolutePath(), "some.url", LogDataType.ZIP));
        testLogs.put(
                PREFIX_OPTION_VALUE + "2",
                new LogFile(
                        perfettoV2MetricProtoFile.getAbsolutePath(), "some.url", LogDataType.ZIP));

        Map<String, Metric.Builder> parsedV2Metrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        if (DEBUG) {
            printOutputMetricsForDebug(parsedV2Metrics);
        }
        // 6 unique test metrics + 1 perfetto_post_processor_runtime metric.
        assertEquals("count of metrics is correct", 7, parsedV2Metrics.size());
        // All metrics are merged and now lists of doubles.
        assertMetricsContainDoubleRow(
                parsedV2Metrics,
                "foo_bar-.ShannonImsService",
                Arrays.asList(123.0, 456.0, 123.0, 456.0));
        assertMetricsContainDoubleRow(
                parsedV2Metrics, "foo_bar-null", Arrays.asList(10464441.0, 10464441.0));
        assertMetricsContainDoubleRow(
                parsedV2Metrics,
                "baza-(Paused)KernelPreparation-1234567890-123",
                Arrays.asList(260051.0, 260051.0));
        assertMetricsContainDoubleRow(
                parsedV2Metrics,
                "baza-(Paused)KernelPreparation-12907-456",
                Arrays.asList(158854.0, 158854.0));
        assertMetricsContainDoubleRow(
                parsedV2Metrics,
                "biz-(Paused)KernelPreparation-12907-456",
                Arrays.asList(345.0, 345.0));
        assertMetricsContainDoubleRow(
                parsedV2Metrics,
                "biz-(Paused)MarkingPause-1234567890-234",
                Arrays.asList(321.0, 321.0));
    }

    @Test
    public void testDimensionalReport_v2Metrics() throws Throwable {
        setupPerfettoV2MetricFile(METRIC_FILE_FORMAT.text, true);

        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        mOptionSetter.setOptionValue("enable-dimensional-metrics", "true");

        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoV2MetricProtoFile.getAbsolutePath(), "some.url", LogDataType.ZIP));

        final MetricReport[] capturedReport = new MetricReport[1];
        doAnswer(
                        (invocation) -> {
                            InputStreamSource source = invocation.getArgument(2);
                            try {
                                capturedReport[0] =
                                        MetricReport.parser().parseFrom(source.createInputStream());
                            } finally {
                                source.cancel(); // Clean up the source
                            }
                            return null;
                        })
                .when(mListener)
                .testLog(
                        startsWith("metrics_report"),
                        eq(LogDataType.CB_METRICS_REPORT_FILE),
                        any());

        mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        // Build expected report for comparison
        MetricReport expectedReport = buildExpectedDimensionalReport();

        assertEquals(expectedReport, capturedReport[0]);
    }

    /**
     * Creates sample perfetto metric proto file used for testing.
     *
     * @param hasProto TODO
     */
    private File setupPerfettoMetricFile(
            METRIC_FILE_FORMAT format, boolean isCompressed, boolean hasProto) throws IOException {
        String perfettoTextContent =
                "android_mem {\n"
                        + "  process_metrics {\n"
                        + "    process_name: \".dataservices\"\n"
                        + "    total_counters {\n"
                        + "      anon_rss {\n"
                        + "        min: 27938816\n"
                        + "        max: 27938816\n"
                        + "        avg: 27938816\n"
                        + "      }\n"
                        + "      file_rss {\n"
                        + "        min: 62390272\n"
                        + "        max: 62390272\n"
                        + "        avg: 62390272\n"
                        + "      }\n"
                        + "      swap {\n"
                        + "        min: 0\n"
                        + "        max: 0\n"
                        + "        avg: 0\n"
                        + "      }\n"
                        + "      anon_and_swap {\n"
                        + "        min: 27938816\n"
                        + "        max: 27938816\n"
                        + "        avg: 27938816\n"
                        + "      }\n"
                        + "    }\n"
                        + "}}"
                        + "android_startup {\n"
                        + "  startup {\n"
                        + "    startup_id: 1\n"
                        + "    package_name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "    process_name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "    zygote_new_process: false\n"
                        + "    to_first_frame {\n"
                        + "      dur_ns: 36175473\n"
                        + "      main_thread_by_task_state {\n"
                        + "        running_dur_ns: 11496200\n"
                        + "        runnable_dur_ns: 487290\n"
                        + "        uninterruptible_sleep_dur_ns: 0\n"
                        + "        interruptible_sleep_dur_ns: 23645107\n"
                        + "      }\n"
                        + "      other_processes_spawned_count: 0\n"
                        + "      time_activity_manager {\n"
                        + "        dur_ns: 4135001\n"
                        + "      }\n"
                        + "      time_activity_resume {\n"
                        + "        dur_ns: 345105\n"
                        + "      }\n"
                        + "      time_choreographer {\n"
                        + "        dur_ns: 15314324\n"
                        + "      }\n"
                        + "    }\n"
                        + "    activity_hosting_process_count: 1\n"
                        + "  }\n"
                        + "  startup {\n"
                        + "    startup_id: 2\n"
                        + "    package_name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "    process_name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "    zygote_new_process: false\n"
                        + "    to_first_frame {\n"
                        + "      dur_ns: 53102401\n"
                        + "      main_thread_by_task_state {\n"
                        + "        running_dur_ns: 9766774\n"
                        + "        runnable_dur_ns: 320103\n"
                        + "        uninterruptible_sleep_dur_ns: 0\n"
                        + "        interruptible_sleep_dur_ns: 42358858\n"
                        + "      }\n"
                        + "      other_processes_spawned_count: 0\n"
                        + "      time_activity_manager {\n"
                        + "        dur_ns: 4742396\n"
                        + "      }\n"
                        + "      time_activity_resume {\n"
                        + "        dur_ns: 280208\n"
                        + "      }\n"
                        + "      time_choreographer {\n"
                        + "        dur_ns: 13705366\n"
                        + "      }\n"
                        + "    }\n"
                        + "    activity_hosting_process_count: 1\n"
                        + "  }\n"
                        + "}\n"
                        + "android_hwui_metric {\n"
                        + "  process_info {\n"
                        + "    process_name: \"com.android.systemui\"\n"
                        + "    rt_cpu_time_ms: 2481\n"
                        + "    draw_frame_count: 889\n"
                        + "    draw_frame_max: 21990523\n"
                        + "    draw_frame_min: 660573\n"
                        + "    draw_frame_avg: 3515215.0101237344\n"
                        + "    flush_count: 884\n"
                        + "    flush_max: 8101094\n"
                        + "    flush_min: 127760\n"
                        + "    flush_avg: 773943.91515837109\n"
                        + "    prepare_tree_count: 889\n"
                        + "    prepare_tree_max: 1718593\n"
                        + "    prepare_tree_min: 25052\n"
                        + "    prepare_tree_avg: 133403.03374578178\n"
                        + "    gpu_completion_count: 572\n"
                        + "    gpu_completion_max: 8600365\n"
                        + "    gpu_completion_min: 3594\n"
                        + "    gpu_completion_avg: 737765.40209790214\n"
                        + "    ui_record_count: 889\n"
                        + "    ui_record_max: 7079949\n"
                        + "    ui_record_min: 4583\n"
                        + "    ui_record_avg: 477551.82902137231\n"
                        + "    graphics_cpu_mem_max: 265242\n"
                        + "    graphics_cpu_mem_min: 244198\n"
                        + "    graphics_cpu_mem_avg: 260553.33484162897\n"
                        + "    graphics_gpu_mem_max: 34792176\n"
                        + "    graphics_gpu_mem_min: 9855728\n"
                        + "    graphics_gpu_mem_avg: 19030174.914027151\n"
                        + "    texture_mem_max: 5217091\n"
                        + "    texture_mem_min: 5020343\n"
                        + "    texture_mem_avg: 5177376.0407239823\n"
                        + "    all_mem_max: 40274509\n"
                        + "    all_mem_min: 15120269\n"
                        + "    all_mem_avg: 24468104.289592762\n"
                        + "  }\n"
                        + "}\n"
                        + "android_jank_cuj {\n"
                        + "  cuj {\n"
                        + "    id: 1\n"
                        + "    name: \"NOTIFICATION_ADD\"\n"
                        + "    process {\n"
                        + "      name: \"com.android.systemui\"\n"
                        + "      uid: 10240\n"
                        + "    }\n"
                        + "    ts: 70088466677776\n"
                        + "    dur: 460793302\n"
                        + "     counter_metrics {\n"
                        + "      }\n"
                        + "      trace_metrics {\n"
                        + "        total_frames: 54\n"
                        + "        missed_frames: 0\n"
                        + "        missed_app_frames: 0\n"
                        + "        missed_sf_frames: 0\n"
                        + "        frame_dur_max: 9845520\n"
                        + "        frame_dur_avg: 6003033\n"
                        + "        frame_dur_p50: 5871663\n"
                        + "        frame_dur_p90: 7130112\n"
                        + "        frame_dur_p95: 7406218\n"
                        + "        frame_dur_p99: 9188145\n"
                        + "     }\n"
                        + "      timeline_metrics {\n"
                        + "        total_frames: 54\n"
                        + "        missed_frames: 0\n"
                        + "        missed_app_frames: 0\n"
                        + "        missed_sf_frames: 0\n"
                        + "        frame_dur_max: 9111735\n"
                        + "        frame_dur_avg: 5040562\n"
                        + "        frame_dur_p50: 4961384\n"
                        + "        frame_dur_p90: 6045320\n"
                        + "        frame_dur_p95: 6621224\n"
                        + "        frame_dur_p99: 7827968\n"
                        + "      }\n"
                        + "    }\n"
                        + " }\n"
                        + "android_cpu {\n"
                        + "  process_info {\n"
                        + "    name: \"com.google.android.apps.messaging\"\n"
                        + "    metrics {\n"
                        + "      mcycles: 139\n"
                        + "      runtime_ns: 639064902\n"
                        + "      min_freq_khz: 576000\n"
                        + "      max_freq_khz: 2016000\n"
                        + "      avg_freq_khz: 324000\n"
                        + "    }\n"
                        + "    threads {\n"
                        + "      name: \"BG Thread #1\"\n"
                        + "      core {\n"
                        + "        id: 0\n"
                        + "        metrics {\n"
                        + "          runtime_ns: 8371202\n"
                        + "        }\n"
                        + "      }\n"
                        + "      core {\n"
                        + "        id: 1\n"
                        + "        metrics {\n"
                        + "          mcycles: 0\n"
                        + "          runtime_ns: 14376405\n"
                        + "          min_freq_khz: 1785600\n"
                        + "          max_freq_khz: 1785600\n"
                        + "          avg_freq_khz: 57977\n"
                        + "        }\n"
                        + "      }\n"
                        + "      metrics {\n"
                        + "        mcycles: 0\n"
                        + "        runtime_ns: 22747607\n"
                        + "        min_freq_khz: 1785600\n"
                        + "        max_freq_khz: 1785600\n"
                        + "        avg_freq_khz: 36000\n"
                        + "      }\n"
                        + "      core_type {\n"
                        + "        type: \"little\"\n"
                        + "        metrics {\n"
                        + "          mcycles: 0\n"
                        + "          runtime_ns: 22747607\n"
                        + "          min_freq_khz: 1785600\n"
                        + "          max_freq_khz: 1785600\n"
                        + "          avg_freq_khz: 36000\n"
                        + "        }\n"
                        + "      }\n"
                        + "    }\n"
                        + " }\n"
                        + "}\n"
                        + "android_blocking_calls_cuj_metric {\n"
                        + "  cuj {\n"
                        + "    id: 1\n"
                        + "    name: \"TASKBAR_EXPAND\"\n"
                        + "    process {\n"
                        + "      name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "      uid: 10248\n"
                        + "      pid: 8834\n"
                        + "    }\n"
                        + "    ts: 5124565418314\n"
                        + "    dur: 215758545\n"
                        + "    blocking_calls {\n"
                        + "      name: \"AIDL::java::ITrustManager::isDeviceSecure::server\"\n"
                        + "      cnt: 3\n"
                        + "      total_dur_ms: 1\n"
                        + "      max_dur_ms: 1\n"
                        + "      min_dur_ms: 0\n"
                        + "    }\n"
                        + "  }\n"
                        + "  cuj {\n"
                        + "    id: 2\n"
                        + "    name: \"ACTION_REQUEST_IME_HIDDEN::HIDE_SOFT_INPUT\"\n"
                        + "    process {\n"
                        + "      name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "      uid: 10248\n"
                        + "      pid: 8834\n"
                        + "    }\n"
                        + "    ts: 5125365576395\n"
                        + "    dur: 306844\n"
                        + "    blocking_calls {\n"
                        + "      name: \"AIDL::java::ITrustManager::isDeviceSecure::server\"\n"
                        + "      cnt: 3\n"
                        + "      total_dur_ms: 1\n"
                        + "      max_dur_ms: 1\n"
                        + "      min_dur_ms: 0\n"
                        + "    }\n"
                        + "  }\n"
                        + "}";

        String perfettoTextContentWithoutMetricProto =
                "{\n"
                        + "  \"perfetto.protos.camera_app_metrics\": {\n"
                        + "    \"camera_close_latencies\": {\n"
                        + "      \"close_ms\": 388.299723\n"
                        + "    }\n"
                        + "  }\n"
                        + "}";

        FileWriter fileWriter = null;
        try {
            perfettoMetricProtoFile = FileUtil.createTempFile("metric_perfetto", "");
            fileWriter = new FileWriter(perfettoMetricProtoFile);
            fileWriter.write(
                    hasProto ? perfettoTextContent : perfettoTextContentWithoutMetricProto);
        } finally {
            if (fileWriter != null) {
                fileWriter.close();
            }
        }

        if (format.equals(METRIC_FILE_FORMAT.binary)) {
            File perfettoBinaryFile = FileUtil.createTempFile("metric_perfetto_binary", ".pb");
            try (BufferedReader bufferedReader =
                    new BufferedReader(
                            new InputStreamReader(new FileInputStream(perfettoMetricProtoFile)))) {
                TraceMetrics.Builder builder = TraceMetrics.newBuilder();
                TextFormat.merge(bufferedReader, builder);
                builder.build().writeTo(new FileOutputStream(perfettoBinaryFile));
            } catch (ParseException e) {
                CLog.e("Failed to merge the perfetto metric file." + e.getMessage());
            } catch (IOException ioe) {
                CLog.e(
                        "IOException happened when reading the perfetto metric file."
                                + ioe.getMessage());
            } finally {
                perfettoMetricProtoFile.delete();
                perfettoMetricProtoFile = perfettoBinaryFile;
            }
            return perfettoMetricProtoFile;
        }

        if (isCompressed) {
            perfettoMetricProtoFile = compressFile(perfettoMetricProtoFile);
        }
        return perfettoMetricProtoFile;
    }

    /**
     * Creates sample perfetto metric proto file used for testing.
     *
     * @param hasProto TODO
     */
    private File setupPerfettoV2MetricFile(METRIC_FILE_FORMAT format, boolean isCompressed)
            throws IOException {
        String perfettoTextContent =
                "metric_bundles {\n"
                        + "  specs {\n"
                        + "    id: \"foo_bar\"\n"
                        + "    value: \"double_value\"\n"
                        + "    dimensions: \"process\"\n"
                        + "    unit: TIME_NANOS\n"
                        + "    polarity: LOWER_IS_BETTER\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \".ShannonImsService\"\n"
                        + "    }\n"
                        + "    values {\n"
                        + "      double_value: 123.0\n"
                        + "    }\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \".ShannonImsService\"\n"
                        + "    }\n"
                        + "    values {\n"
                        + "      double_value: 456.0\n"
                        + "    }\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      null_value {}\n"
                        + "    }\n"
                        + "    values {\n"
                        + "      double_value: 10464441.0\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n"
                        + "metric_bundles {\n"
                        + "  specs {\n"
                        + "    id: \"baza\"\n"
                        + "    value: \"double_value\"\n"
                        + "    dimensions: \"state\"\n"
                        + "    dimensions: \"id\"\n"
                        + "    dimensions: \"value\"\n"
                        + "    unit: TIME_MILLIS\n"
                        + "    polarity: HIGHER_IS_BETTER\n"
                        + "  }\n"
                        + "  specs {\n"
                        + "    id: \"biz\"\n"
                        + "    value: \"double_value\"\n"
                        + "    dimensions: \"state\"\n"
                        + "    dimensions: \"id\"\n"
                        + "    dimensions: \"value\"\n"
                        + "    unit: COUNT\n"
                        + "    polarity: NOT_APPLICABLE\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \"(Paused)KernelPreparation\"\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      int64_value: 1234567890\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      double_value: 123.0\n"
                        + "    }\n"
                        + "    values {\n"
                        + "      double_value: 260051.0\n"
                        + "    }\n"
                        + "    values {\n"
                        + "      null_value {}\n"
                        + "    }\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \"(Paused)KernelPreparation\"\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      int64_value: 12907\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      double_value: 456.0\n"
                        + "    }\n"
                        + "    values {\n"
                        + "      double_value: 158854.0\n"
                        + "    }\n"
                        + "    values {\n"
                        + "      double_value: 345.0\n"
                        + "    }\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \"(Paused)MarkingPause\"\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      int64_value: 1234567890\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      double_value: 234.0\n"
                        + "    }\n"
                        + "    values {\n"
                        + "      null_value {}\n"
                        + "    }\n"
                        + "    values {\n"
                        + "      double_value: 321.0\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n";

        FileWriter fileWriter = null;
        try {
            perfettoV2MetricProtoFile = FileUtil.createTempFile("metric_v2_perfetto", "");
            fileWriter = new FileWriter(perfettoV2MetricProtoFile);
            fileWriter.write(perfettoTextContent);
        } finally {
            if (fileWriter != null) {
                fileWriter.close();
            }
        }

        if (format.equals(METRIC_FILE_FORMAT.binary)) {
            File perfettoBinaryFile = FileUtil.createTempFile("metric_v2_perfetto_binary", ".pb");
            try (BufferedReader bufferedReader =
                    new BufferedReader(
                            new InputStreamReader(
                                    new FileInputStream(perfettoV2MetricProtoFile)))) {
                TraceMetrics.Builder builder = TraceMetrics.newBuilder();
                TextFormat.merge(bufferedReader, builder);
                builder.build().writeTo(new FileOutputStream(perfettoBinaryFile));
            } catch (ParseException e) {
                CLog.e("Failed to merge the perfetto v2 metric file." + e.getMessage());
            } catch (IOException ioe) {
                CLog.e(
                        "IOException happened when reading the perfetto v2 metric file."
                                + ioe.getMessage());
            } finally {
                perfettoV2MetricProtoFile.delete();
                perfettoV2MetricProtoFile = perfettoBinaryFile;
            }
            return perfettoV2MetricProtoFile;
        }

        if (isCompressed) {
            perfettoV2MetricProtoFile = compressFile(perfettoV2MetricProtoFile);
        }
        return perfettoV2MetricProtoFile;
    }

    /** Create a zip file with perfetto metric proto file */
    private File compressFile(File decompressedFile) throws IOException {
        File compressedFile = FileUtil.createTempFile("compressed_temp", ".zip");
        try {
            ZipUtil.createZip(decompressedFile, compressedFile);
            // Only delete on success
            decompressedFile.delete();
        } catch (IOException ioe) {
            CLog.e("Unable to gzip the file.");
            // Rethrow to fail test clearly
            throw ioe;
        }
        return compressedFile;
    }

    @After
    public void teardown() {
        if (perfettoMetricProtoFile != null) {
            perfettoMetricProtoFile.delete();
        }
        if (perfettoV2MetricProtoFile != null) {
            perfettoV2MetricProtoFile.delete();
        }
    }

    /** Assert that metrics contain a key and a corresponding string value. */
    private void assertMetricsContainString(
            Map<String, Metric.Builder> metrics, String key, Object value) {
        assertTrue(
                String.format(
                        "Metric with key containing %s and value %s was expected but not found.",
                        key, value),
                metrics.entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getKey().contains(key)
                                                && String.valueOf(value)
                                                        .equals(
                                                                e.getValue()
                                                                        .build()
                                                                        .getMeasurements()
                                                                        .getSingleString())));
    }

    /** Assert that metrics contain a key and a corresponding double value. */
    private void assertMetricsContainDouble(
            Map<String, Metric.Builder> metrics, String key, Double value) {
        assertTrue(
                String.format(
                        "Metric with key containing %s and value %s was expected but not found.",
                        key, value),
                metrics.entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getKey().contains(key)
                                                && value.equals(
                                                        e.getValue()
                                                                .build()
                                                                .getMeasurements()
                                                                .getSingleDouble())));
    }

    /** Assert that metrics contain a key and row of corresponding values. */
    private void assertMetricsContainDoubleRow(
            Map<String, Metric.Builder> metrics, String key, List<Double> values) {
        assertTrue(
                String.format(
                        "Metric with key containing %s and values %s was expected but not found.",
                        key, values),
                metrics.entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getKey().contains(key)
                                                && values.equals(
                                                        e.getValue()
                                                                .build()
                                                                .getMeasurements()
                                                                .getDoubleValues()
                                                                .getDoubleValueList())));
    }

    private MetricReport buildExpectedDimensionalReport() {
        MetricRecord fooBarRecord =
                MetricRecord.newBuilder()
                        .setMetricId("foo_bar")
                        .setHumanReadableName("foo_bar")
                        .setUnit(Unit.TIME_NANOS)
                        .setPolarity(Polarity.LOWER_IS_BETTER)
                        .setSource(Source.PERFETTO_V2)
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("process")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                ".ShannonImsService")))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(123.0))
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(456.0)))
                                        .setLegacyMetricKey("foo_bar-.ShannonImsService"))
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("process")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue("null")))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(
                                                                                10464441.0)))
                                        .setLegacyMetricKey("foo_bar-null"))
                        .build();

        MetricRecord bazaRecord =
                MetricRecord.newBuilder()
                        .setMetricId("baza")
                        .setHumanReadableName("baza")
                        .setUnit(Unit.TIME_MILLIS)
                        .setPolarity(Polarity.HIGHER_IS_BETTER)
                        .setSource(Source.PERFETTO_V2)
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("id")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(1234567890)))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("state")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "(Paused)KernelPreparation")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("value")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(123.0)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(260051.0)))
                                        .setLegacyMetricKey(
                                                "baza-(Paused)KernelPreparation-1234567890-123.0"))
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("id")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(12907)))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("state")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "(Paused)KernelPreparation")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("value")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(456.0)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(158854.0)))
                                        .setLegacyMetricKey(
                                                "baza-(Paused)KernelPreparation-12907-456.0"))
                        .build();
        MetricRecord bizRecord =
                MetricRecord.newBuilder()
                        .setMetricId("biz")
                        .setHumanReadableName("biz")
                        .setUnit(Unit.COUNT)
                        .setPolarity(Polarity.NOT_APPLICABLE)
                        .setSource(Source.PERFETTO_V2)
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("id")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(12907)))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("state")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "(Paused)KernelPreparation")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("value")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(456.0)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(345.0)))
                                        .setLegacyMetricKey(
                                                "biz-(Paused)KernelPreparation-12907-456.0"))
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("id")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(1234567890)))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("state")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "(Paused)MarkingPause")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("value")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(234.0)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(321.0)))
                                        .setLegacyMetricKey(
                                                "biz-(Paused)MarkingPause-1234567890-234.0"))
                        .build();

        TestMetricResult testResult =
                TestMetricResult.newBuilder()
                        .setIdentifier(TestIdentifier.getDefaultInstance())
                        .addMetricRecords(fooBarRecord)
                        .addMetricRecords(bazaRecord)
                        .addMetricRecords(bizRecord)
                        .build();

        return MetricReport.newBuilder().addTestMetricResults(testResult).build();
    }
}
