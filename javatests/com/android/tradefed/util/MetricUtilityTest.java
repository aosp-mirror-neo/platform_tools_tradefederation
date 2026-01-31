/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.ComputedStatistics;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Dimension;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.DimensionalMetric;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.MetricRecord;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.MetricReport;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.RawValues;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Stat;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.TestIdentifier;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.TestMetricResult;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Value;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.util.proto.TfMetricProtoUtil;
import com.google.protobuf.TextFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Unit tests for {@link MetricUtility}. */
@RunWith(JUnit4.class)
public class MetricUtilityTest {

    private static final String OUTPUT_1 = "case_1";
    private static final String OUTPUT_2 = "case_2";
    private static final String OUTPUT_3 = "case_3";
    private static final String OUTPUT_4 = "case_4";
    private static final String OUTPUT_5 = "case_5";
    private static final String OUTPUT_6 = "case_6";
    private static final String OUTPUT_7 = "case_7";
    private static final String OUTPUT_8 = "case_8";
    private static final String OUTPUT_9 = "case_9";
    private static final String OUTPUT_10 = "case_10";
    private static final String OUTPUT_11 = "case_11";
    private static final String OUTPUT_12 = "case_12";
    private static final String OUTPUT_13 = "case_13";

    private MetricUtility mMetricUtil;
    private File mResultsFile;

    private static final TestDescription TEST_1_ITERATION_1_ON_CLASS =
            new TestDescription("pkg$1", "test1");
    private static final TestDescription TEST_1_ITERATION_2_ON_CLASS =
            new TestDescription("pkg$2", "test1");
    private static final TestDescription TEST_1_ITERATION_3_ON_CLASS =
            new TestDescription("pkg$3", "test1");
    private static final TestDescription TEST_2_ITERATION_1_ON_CLASS =
            new TestDescription("pkg$1", "test2");
    private static final TestDescription TEST_1_ITERATION_1_ON_METHOD =
            new TestDescription("pkg", "test1$1");
    private static final TestDescription TEST_1_ITERATION_2_ON_METHOD =
            new TestDescription("pkg", "test1$2");
    private static final TestDescription TEST_1_ITERATION_3_ON_METHOD =
            new TestDescription("pkg", "test1$3");
    private static final TestDescription TEST_2_ITERATION_1_ON_METHOD =
            new TestDescription("pkg", "test2$1");
    private static final TestDescription TEST_3_WITHOUT_ITERATION =
            new TestDescription("pkg", "test3");

    @Before
    public void setUp() {
        mMetricUtil = new MetricUtility();
    }

    @After
    public void tearDown() {
        FileUtil.deleteFile(mResultsFile);
    }

    @Test
    public void testStoreMultipleTestSingleIterationMetrics_iterationOnClass() {
        testStoreMultipleTestSingleIterationMetrics(
                TEST_1_ITERATION_1_ON_CLASS, TEST_2_ITERATION_1_ON_CLASS);
    }

    @Test
    public void testStoreMultipleTestSingleIterationMetrics_iterationOnMethod() {
        testStoreMultipleTestSingleIterationMetrics(
                TEST_1_ITERATION_1_ON_METHOD, TEST_2_ITERATION_1_ON_METHOD);
    }

    private void testStoreMultipleTestSingleIterationMetrics(
            TestDescription test1, TestDescription test2) {
        mMetricUtil.setIterationSeparator("$");

        // Build first test metric.
        Map<String, Metric> firstTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder1 = Metric.newBuilder();
        metricBuilder1.getMeasurementsBuilder().setSingleString("2.9");
        Metric currentTestMetric = metricBuilder1.build();
        firstTestMetric.put("first_test_metric", currentTestMetric);

        // Build second test metric.
        Map<String, Metric> secondTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder2 = Metric.newBuilder();
        metricBuilder2.getMeasurementsBuilder().setSingleString("1.5");
        Metric currentTestMetric2 = metricBuilder2.build();
        secondTestMetric.put("second_test_metric", currentTestMetric2);

        mMetricUtil.storeTestMetrics(test1, firstTestMetric, false);
        mMetricUtil.storeTestMetrics(test2, secondTestMetric, false);

        Map<String, List<MetricUtility.IterationData>> storedTestmetrics =
                mMetricUtil.getTestIterations();

        assertTrue(storedTestmetrics.size() == 2);
        assertTrue(storedTestmetrics.get("pkg#test1").size() == 1);
        assertTrue(storedTestmetrics.get("pkg#test2").size() == 1);
        assertEquals(
                "2.9",
                storedTestmetrics
                        .get("pkg#test1")
                        .get(0)
                        .metrics
                        .get("first_test_metric")
                        .getMeasurements()
                        .getSingleString());
        assertEquals(
                "1.5",
                storedTestmetrics
                        .get("pkg#test2")
                        .get(0)
                        .metrics
                        .get("second_test_metric")
                        .getMeasurements()
                        .getSingleString());
    }

    @Test
    public void testStoreSingleTestMultipleIterationMetrics_iterationOnClass() {
        testStoreSingleTestMultipleIterationMetrics(
                TEST_1_ITERATION_1_ON_CLASS, TEST_1_ITERATION_2_ON_CLASS);
    }

    @Test
    public void testStoreSingleTestMultipleIterationMetrics_iterationOnMethod() {
        testStoreSingleTestMultipleIterationMetrics(
                TEST_1_ITERATION_1_ON_METHOD, TEST_1_ITERATION_2_ON_METHOD);
    }

    private void testStoreSingleTestMultipleIterationMetrics(
            TestDescription test1, TestDescription test2) {
        mMetricUtil.setIterationSeparator("$");

        // Build first test metric.
        Map<String, Metric> firstIterationMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder1 = Metric.newBuilder();
        metricBuilder1.getMeasurementsBuilder().setSingleString("2.9");
        Metric currentTestMetric = metricBuilder1.build();
        firstIterationMetric.put("first_test_metric", currentTestMetric);

        // Build second test metric.
        Map<String, Metric> secondIterationMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder2 = Metric.newBuilder();
        metricBuilder2.getMeasurementsBuilder().setSingleString("1.5");
        Metric currentTestMetric2 = metricBuilder2.build();
        secondIterationMetric.put("first_test_metric", currentTestMetric2);

        mMetricUtil.storeTestMetrics(test1, firstIterationMetric, false);
        mMetricUtil.storeTestMetrics(test2, secondIterationMetric, false);

        Map<String, List<MetricUtility.IterationData>> storedTestmetrics =
                mMetricUtil.getTestIterations();

        assertTrue(storedTestmetrics.size() == 1);

        List<MetricUtility.IterationData> test1Data = storedTestmetrics.get("pkg#test1");
        assertTrue(test1Data.size() == 2);
        assertEquals(
                "2.9",
                test1Data
                        .get(0)
                        .metrics
                        .get("first_test_metric")
                        .getMeasurements()
                        .getSingleString());
        assertEquals(
                "1.5",
                test1Data
                        .get(1)
                        .metrics
                        .get("first_test_metric")
                        .getMeasurements()
                        .getSingleString());
    }

    @Test
    public void testStoreWithoutIteration() {
        // Build first test metric.
        HashMap<String, Metric> thirdTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder1 = Metric.newBuilder();
        metricBuilder1.getMeasurementsBuilder().setSingleString("2.9");
        Metric currentTestMetric = metricBuilder1.build();
        thirdTestMetric.put("third_test_metric", currentTestMetric);

        mMetricUtil.storeTestMetrics(TEST_3_WITHOUT_ITERATION, thirdTestMetric, false);

        Map<String, List<MetricUtility.IterationData>> storedTestmetrics =
                mMetricUtil.getTestIterations();

        assertTrue(storedTestmetrics.size() == 1);
        assertTrue(storedTestmetrics.get("pkg#test3").size() == 1);
    }

    @Test
    public void testStoreEmptyTestMetrics() {
        mMetricUtil.setIterationSeparator("$");
        // Make sure we have the entry for the test name with empty metrics.
        mMetricUtil.storeTestMetrics(
                TEST_1_ITERATION_1_ON_CLASS, new HashMap<String, Metric>(), false);
        Map<String, List<MetricUtility.IterationData>> storedTestmetrics =
                mMetricUtil.getTestIterations();

        // Entry for test name with zero metrics.
        assertTrue(storedTestmetrics.size() == 1 && storedTestmetrics.get("pkg#test1").size() == 1);
        assertTrue(storedTestmetrics.get("pkg#test1").get(0).metrics.isEmpty());
    }

    @Test
    public void testResultFileWithSingleTest() throws IOException {
        mMetricUtil.setIterationSeparator("$");
        // Build first test metric.
        Map<String, String> firstTestMetric = new LinkedHashMap<String, String>();
        firstTestMetric.put("first_test_metric_1", "2.9");
        firstTestMetric.put("first_test_metric_2", "3");

        mResultsFile =
                mMetricUtil.writeResultsToFile(
                        "file_suffix", "pkg_name#test_name", firstTestMetric, null);

        assertTrue(mResultsFile.getName().contains("file_suffix"));
        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_1)));
    }

    @Test
    public void testResultFileWithMultipleTest() throws IOException {

        mMetricUtil.setIterationSeparator("$");

        // Build first test metric.
        Map<String, String> firstTestMetric = new LinkedHashMap<String, String>();
        firstTestMetric.put("first_test_metric_1", "2.9");
        firstTestMetric.put("first_test_metric_2", "3");

        // Build second test metric.
        Map<String, String> secondTestMetric = new LinkedHashMap<String, String>();
        secondTestMetric.put("second_test_metric_1", "5.9");
        secondTestMetric.put("second_test_metric_2", "0");

        mResultsFile =
                mMetricUtil.writeResultsToFile(
                        "file_suffix", "pkg_name#test_name_1", firstTestMetric, null);

        // Write second test metrics
        mMetricUtil.writeResultsToFile(
                "file_suffix", "pkg_name#test_name_2", secondTestMetric, mResultsFile);

        assertTrue(mResultsFile.getName().contains("file_suffix"));
        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_2)));
    }

    @Test
    public void testResultFileWithEmptyTestMetrics() throws IOException {

        mMetricUtil.setIterationSeparator("$");
        Map<String, String> firstTestMetric = new LinkedHashMap<String, String>();

        // Create a file with empty test metrics
        mResultsFile =
                mMetricUtil.writeResultsToFile(
                        "file_suffix", "pkg_name#test_name", firstTestMetric, null);
        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_3)));
    }

    @Test
    public void testAggregateCommaSeparatedMetrics() throws IOException {

        mMetricUtil.setPercentiles(new HashSet<>());

        // Build first test metric.
        Map<String, Metric> thirdTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder1 = Metric.newBuilder();
        metricBuilder1.getMeasurementsBuilder().setSingleString("1,2,3");
        Metric currentTestMetric = metricBuilder1.build();
        thirdTestMetric.put("third_test_metric", currentTestMetric);

        Map<String, Metric> aggregatedMetric = mMetricUtil.aggregateMetrics(thirdTestMetric);

        Map<String, String> compatibleTestMetrics =
                TfMetricProtoUtil.compatibleConvert(aggregatedMetric);

        mResultsFile =
                mMetricUtil.writeResultsToFile(
                        "file_suffix", "pkg_name#test_name", compatibleTestMetrics, null);

        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_4)));
    }

    @Test
    public void testCustomPercentiles() throws IOException {

        mMetricUtil.setPercentiles(new LinkedHashSet<>(Arrays.asList(50, 90, 99)));

        // Build first test metric.
        Map<String, Metric> thirdTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder1 = Metric.newBuilder();
        metricBuilder1.getMeasurementsBuilder().setSingleString("1,2,3");
        Metric currentTestMetric = metricBuilder1.build();
        thirdTestMetric.put("third_test_metric", currentTestMetric);

        Map<String, Metric> aggregatedMetric = mMetricUtil.aggregateMetrics(thirdTestMetric);

        Map<String, String> compatibleTestMetrics =
                TfMetricProtoUtil.compatibleConvert(aggregatedMetric);

        mResultsFile =
                mMetricUtil.writeResultsToFile(
                        "file_suffix", "pkg_name#test_name", compatibleTestMetrics, null);

        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_5)));
    }

    @Test
    public void testAggregateStoredTestMetricsForMultipleIterations_iterationOnClass()
            throws IOException {
        testAggregateStoredTestMetricsForMultipleIterations(
                TEST_1_ITERATION_1_ON_CLASS, TEST_1_ITERATION_2_ON_CLASS);
    }

    @Test
    public void testAggregateStoredTestMetricsForMultipleIterations_iterationOnMethod()
            throws IOException {
        testAggregateStoredTestMetricsForMultipleIterations(
                TEST_1_ITERATION_1_ON_METHOD, TEST_1_ITERATION_2_ON_METHOD);
    }

    private void testAggregateStoredTestMetricsForMultipleIterations(
            TestDescription test1, TestDescription test2) throws IOException {

        mMetricUtil.setIterationSeparator("$");
        mMetricUtil.setPercentiles(new HashSet<>());
        // Scenario 1:
        // Default Metric Count for All Metrics: This is achieved by keeping
        // metrics-adjust-for-passed-iterations as an empty set. When no regex patterns are
        // provided, the system defaults to calculating the mean using the total number of metric
        // values collected.
        mMetricUtil.setMetricsUseForTestIterations(new HashSet<>());

        // Build first test metric.
        Map<String, Metric> firstIterationMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder1 = Metric.newBuilder();
        metricBuilder1.getMeasurementsBuilder().setSingleString("2.9");
        Metric currentTestMetric = metricBuilder1.build();
        firstIterationMetric.put("first_test_metric", currentTestMetric);

        // Build second test metric.
        HashMap<String, Metric> secondIterationMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder2 = Metric.newBuilder();
        metricBuilder2.getMeasurementsBuilder().setSingleString("1.5");
        Metric currentTestMetric2 = metricBuilder2.build();
        secondIterationMetric.put("first_test_metric", currentTestMetric2);

        mMetricUtil.storeTestMetrics(test1, firstIterationMetric, false);
        mMetricUtil.storeTestMetrics(test2, secondIterationMetric, false);

        mResultsFile = mMetricUtil.aggregateStoredTestMetricsAndWriteToFile("run_name");

        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_6)));
    }

    @Test
    public void testAggregateStoredTestMetricsForSingleIterationMultipleTest_iterationOnClass()
            throws IOException {
        testAggregateStoredTestMetricsForSingleIterationMultipleTest(
                TEST_1_ITERATION_1_ON_CLASS, TEST_2_ITERATION_1_ON_CLASS);
    }

    @Test
    public void testAggregateStoredTestMetricsForSingleIterationMultipleTest_iterationOnMethod()
            throws IOException {
        testAggregateStoredTestMetricsForSingleIterationMultipleTest(
                TEST_1_ITERATION_1_ON_METHOD, TEST_2_ITERATION_1_ON_METHOD);
    }

    private void testAggregateStoredTestMetricsForSingleIterationMultipleTest(
            TestDescription test1, TestDescription test2) throws IOException {
        mMetricUtil.setIterationSeparator("$");
        mMetricUtil.setPercentiles(new HashSet<>());
        mMetricUtil.setMetricsUseForTestIterations(new HashSet<>()); // Scenario 1

        // Build first test metric.
        Map<String, Metric> firstTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder1 = Metric.newBuilder();
        metricBuilder1.getMeasurementsBuilder().setSingleString("2.9");
        Metric currentTestMetric = metricBuilder1.build();
        firstTestMetric.put("first_test_metric", currentTestMetric);

        // Build second test metric.
        HashMap<String, Metric> secondTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder2 = Metric.newBuilder();
        metricBuilder2.getMeasurementsBuilder().setSingleString("1.5");
        Metric currentTestMetric2 = metricBuilder2.build();
        secondTestMetric.put("second_test_metric", currentTestMetric2);

        mMetricUtil.storeTestMetrics(test1, firstTestMetric, false);
        mMetricUtil.storeTestMetrics(test2, secondTestMetric, false);

        mResultsFile = mMetricUtil.aggregateStoredTestMetricsAndWriteToFile("run_name");

        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_7)));
    }

    @Test
    public void testAggregateEmptyStoredTestMetricsForMultipleIterations_iterationOnClass()
            throws IOException {
        testAggregateEmptyStoredTestMetricsForMultipleIterations(
                TEST_1_ITERATION_1_ON_CLASS, TEST_1_ITERATION_2_ON_CLASS);
    }

    @Test
    public void testAggregateEmptyStoredTestMetricsForMultipleIterations_iterationOnMethod()
            throws IOException {
        testAggregateEmptyStoredTestMetricsForMultipleIterations(
                TEST_1_ITERATION_1_ON_METHOD, TEST_1_ITERATION_2_ON_METHOD);
    }

    private void testAggregateEmptyStoredTestMetricsForMultipleIterations(
            TestDescription test1, TestDescription test2) throws IOException {

        mMetricUtil.setIterationSeparator("$");
        mMetricUtil.setPercentiles(new HashSet<>());
        mMetricUtil.setMetricsUseForTestIterations(new HashSet<>()); // Scenario 1

        // Build first empty test metric.
        Map<String, Metric> firstIterationMetric = new HashMap<String, Metric>();

        // Build second empty test metric.
        Map<String, Metric> secondIterationMetric = new HashMap<String, Metric>();

        mMetricUtil.storeTestMetrics(test1, firstIterationMetric, false);
        mMetricUtil.storeTestMetrics(test2, secondIterationMetric, false);

        mResultsFile = mMetricUtil.aggregateStoredTestMetricsAndWriteToFile("run_name");

        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_8)));
    }

    @Test
    public void testAggregateStoredTestMetricsWithStrictFilters_iterationOnClass()
            throws IOException {
        testAggregateStoredTestMetricsWithStrictFilters(
                TEST_1_ITERATION_1_ON_CLASS, TEST_2_ITERATION_1_ON_CLASS);
    }

    @Test
    public void testAggregateStoredTestMetricsWithStrictFilters_iterationOnMethod()
            throws IOException {
        testAggregateStoredTestMetricsWithStrictFilters(
                TEST_1_ITERATION_1_ON_METHOD, TEST_2_ITERATION_1_ON_METHOD);
    }

    private void testAggregateStoredTestMetricsWithStrictFilters(
            TestDescription test1, TestDescription test2) throws IOException {
        mMetricUtil.setIterationSeparator("$");
        mMetricUtil.setPercentiles(new HashSet<>());
        mMetricUtil.buildMetricFilterPatterns(
                new HashSet<>(Arrays.asList("first_test_metric-min", "second_test_metric-min")));
        mMetricUtil.setMetricsUseForTestIterations(new HashSet<>()); // Scenario 1

        // Build first test metric.
        Map<String, Metric> firstTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder1 = Metric.newBuilder();
        metricBuilder1.getMeasurementsBuilder().setSingleString("2.9");
        Metric currentTestMetric = metricBuilder1.build();
        firstTestMetric.put("first_test_metric", currentTestMetric);

        // Build second test metric.
        HashMap<String, Metric> secondTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder2 = Metric.newBuilder();
        metricBuilder2.getMeasurementsBuilder().setSingleString("1.5");
        Metric currentTestMetric2 = metricBuilder2.build();
        secondTestMetric.put("second_test_metric", currentTestMetric2);

        mMetricUtil.storeTestMetrics(test1, firstTestMetric, false);
        mMetricUtil.storeTestMetrics(test2, secondTestMetric, false);

        mResultsFile = mMetricUtil.aggregateStoredTestMetricsAndWriteToFile("run_name");

        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_9)));
    }

    @Test
    public void testAggregateStoredTestMetricsWithStrictFiltersNotMatching() throws IOException {
        mMetricUtil.setIterationSeparator("$");
        mMetricUtil.setPercentiles(new HashSet<>());
        mMetricUtil.buildMetricFilterPatterns(new HashSet<>(Arrays.asList("no_metric")));
        mMetricUtil.setMetricsUseForTestIterations(new HashSet<>()); // Scenario 1

        // Build first test metric.
        Map<String, Metric> firstTestMetric = new HashMap<String, Metric>();
        Metric.Builder metricBuilder1 = Metric.newBuilder();
        metricBuilder1.getMeasurementsBuilder().setSingleString("2.9");
        Metric currentTestMetric = metricBuilder1.build();
        firstTestMetric.put("first_test_metric", currentTestMetric); // No change here

        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_1_ON_CLASS, firstTestMetric, false);

        mResultsFile = mMetricUtil.aggregateStoredTestMetricsAndWriteToFile("run_name");

        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_8)));
    }

    @Test
    public void testAggregateStoredTestMetrics_allMetricsUsePassedIterations() throws IOException {
        mMetricUtil.setIterationSeparator("$");
        mMetricUtil.setPercentiles(new HashSet<>());
        // Scenario 2:
        // Passed Test Iterations for All Metrics: This can be configured by setting
        // metrics-adjust-for-passed-iterations to include a wildcard regex, specifically [".*"].
        // Since . matches all metric keys, every metric will have its mean calculated based on the
        // passed test iterations.
        mMetricUtil.setMetricsUseForTestIterations(new HashSet<>(Arrays.asList(".*")));

        // Iteration 1
        Map<String, Metric> i1_metrics = new HashMap<>();
        i1_metrics.put("my_metric", TfMetricProtoUtil.stringToMetric("1.0,2.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_1_ON_CLASS, i1_metrics, false);

        // Iteration 2
        Map<String, Metric> i2_metrics = new HashMap<>();
        i2_metrics.put("my_metric", TfMetricProtoUtil.stringToMetric("3.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_2_ON_CLASS, i2_metrics, false);

        // Iteration 3
        Map<String, Metric> i3_metrics = new HashMap<>();
        i3_metrics.put("another_metric", TfMetricProtoUtil.stringToMetric("2.0,4.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_3_ON_CLASS, i3_metrics, false);

        mResultsFile = mMetricUtil.aggregateStoredTestMetricsAndWriteToFile("run_name");
        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_10)));
    }

    @Test
    public void testAggregateStoredTestMetrics_allMetricsUsePassedIterations_singleValue()
            throws IOException {
        mMetricUtil.setIterationSeparator("$");
        mMetricUtil.setPercentiles(new HashSet<>());
        // Scenario 2: Use passed iterations for all metrics
        mMetricUtil.setMetricsUseForTestIterations(new HashSet<>(Arrays.asList(".*")));

        // Iteration 1: has my_metric
        Map<String, Metric> i1_metrics = new HashMap<>();
        i1_metrics.put("my_metric", TfMetricProtoUtil.stringToMetric("1.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_1_ON_CLASS, i1_metrics, false);

        // Iteration 2: also has my_metric
        Map<String, Metric> i2_metrics = new HashMap<>();
        i2_metrics.put("my_metric", TfMetricProtoUtil.stringToMetric("2.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_2_ON_CLASS, i2_metrics, false);

        // Iteration 3: has a different metric
        Map<String, Metric> i3_metrics = new HashMap<>();
        i3_metrics.put("another_metric", TfMetricProtoUtil.stringToMetric("9.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_3_ON_CLASS, i3_metrics, false);

        mResultsFile = mMetricUtil.aggregateStoredTestMetricsAndWriteToFile("run_name");
        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_11)));
    }

    @Test
    public void testAggregateStoredTestMetrics_withFailedIterationsAndUsePassed()
            throws IOException {
        mMetricUtil.setIterationSeparator("$");
        mMetricUtil.setPercentiles(new HashSet<>());
        // Scenario 2: Use passed iterations for all metrics
        mMetricUtil.setMetricsUseForTestIterations(new HashSet<>(Arrays.asList(".*")));

        // Iteration 1: passes
        Map<String, Metric> i1_metrics = new HashMap<>();
        i1_metrics.put("my_metric", TfMetricProtoUtil.stringToMetric("10.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_1_ON_CLASS, i1_metrics, false);

        // Iteration 2: fails
        Map<String, Metric> i2_metrics = new HashMap<>();
        i2_metrics.put("my_metric", TfMetricProtoUtil.stringToMetric("20.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_2_ON_CLASS, i2_metrics, true);

        // Iteration 3: fails
        Map<String, Metric> i3_metrics = new HashMap<>();
        i3_metrics.put("my_metric", TfMetricProtoUtil.stringToMetric("30.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_3_ON_CLASS, i3_metrics, true);

        mResultsFile = mMetricUtil.aggregateStoredTestMetricsAndWriteToFile("run_name");
        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_12)));
    }

    @Test
    public void testAggregateStoredTestMetrics_withRegexFilterForIterations() throws IOException {
        mMetricUtil.setIterationSeparator("$");
        mMetricUtil.setPercentiles(new HashSet<>());
        // Scenario 3:
        // Passed Test Iterations for Matched Metrics: Providing specific regex patterns in
        // metrics-adjust-for-passed-iterations (e.g., ["your_metric_regex"]) enables the passed
        // iteration mean calculation only for those metrics whose keys match the provided patterns.
        mMetricUtil.setMetricsUseForTestIterations(
                new HashSet<>(Collections.singletonList(".*matching_metric.*")));

        // 3 iterations, all passing, so passedIterations = 3.
        Map<String, Metric> i1_metrics = new HashMap<>();
        i1_metrics.put("matching_metric", TfMetricProtoUtil.stringToMetric("10.0"));
        i1_metrics.put("unmatched_metric", TfMetricProtoUtil.stringToMetric("100.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_1_ON_CLASS, i1_metrics, false);

        Map<String, Metric> i2_metrics = new HashMap<>();
        i2_metrics.put("matching_metric", TfMetricProtoUtil.stringToMetric("20.0"));
        i2_metrics.put("unmatched_metric", TfMetricProtoUtil.stringToMetric("200.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_2_ON_CLASS, i2_metrics, false);

        Map<String, Metric> i3_metrics = new HashMap<>();
        i3_metrics.put("matching_metric", TfMetricProtoUtil.stringToMetric("30.0"));
        i3_metrics.put("unmatched_metric", TfMetricProtoUtil.stringToMetric("300.0"));
        mMetricUtil.storeTestMetrics(TEST_1_ITERATION_3_ON_CLASS, i3_metrics, false);

        mResultsFile = mMetricUtil.aggregateStoredTestMetricsAndWriteToFile("run_name");
        assertTrue(FileUtil.readStringFromFile(mResultsFile).equals(getSampleOutput(OUTPUT_13)));
    }

    @Test
    public void testGetStats_medianIsZero() {
        List<Double> values = Arrays.asList(-1.0, 0.0, 1.0);
        Map<String, Double> stats = MetricUtility.getStats(values, new HashSet<>(), 0);
        // mad is median of absolute deviations from median.
        // median is 0.0.
        // absolute deviations: [1.0, 0.0, 1.0] -> sorted [0.0, 1.0, 1.0] -> median is 1.0
        assertEquals(1.0, stats.get("mad"), 0.001);
        // mad-coeff should be 0 when median is 0 to avoid division by zero.
        assertEquals(0.0, stats.get("mad-coeff"), 0.001);
    }

    @Test
    public void testAggregateDimensionalMetrics_iterationOnClass() throws IOException {
        TestIdentifier testId1 =
                TestIdentifier.newBuilder()
                        .setFullTestName("pkg.class$1#test")
                        .setTestClass("pkg.class$1")
                        .setMethod("test")
                        .build();
        File reportFile1 =
                createDimensionalMetricReportFile("report1.textproto", testId1, 1.0, 3.0);
        TestIdentifier testId2 =
                TestIdentifier.newBuilder()
                        .setFullTestName("pkg.class$2#test")
                        .setTestClass("pkg.class$2")
                        .setMethod("test")
                        .build();
        File reportFile2 = createDimensionalMetricReportFile("report2.textproto", testId2, 5.0);

        List<LogFile> reportFiles =
                Arrays.asList(
                        new LogFile(reportFile1.getAbsolutePath(), "url1", LogDataType.TEXTPB),
                        new LogFile(reportFile2.getAbsolutePath(), "url2", LogDataType.TEXTPB));

        try {
            mResultsFile =
                    mMetricUtil.aggregateDimensionalMetricsAndWriteToFile(
                            reportFiles, new HashSet<>(Arrays.asList(90)), "$");

            assertNotNull(mResultsFile);
            MetricReport.Builder aggregatedReportBuilder = MetricReport.newBuilder();
            TextFormat.getParser()
                    .merge(FileUtil.readStringFromFile(mResultsFile), aggregatedReportBuilder);
            MetricReport aggregatedReport = aggregatedReportBuilder.build();

            assertEquals(1, aggregatedReport.getTestMetricResultsCount());
            TestMetricResult aggregatedResult = aggregatedReport.getTestMetricResults(0);
            assertEquals("pkg.class#test", aggregatedResult.getIdentifier().getFullTestName());
            assertEquals("pkg.class", aggregatedResult.getIdentifier().getTestClass());
            assertEquals(12, aggregatedResult.getMetricRecordsCount());

        } finally {
            FileUtil.deleteFile(reportFile1);
            FileUtil.deleteFile(reportFile2);
        }
    }

    @Test
    public void testAggregateDimensionalMetrics() throws IOException {
        TestIdentifier testId =
                TestIdentifier.newBuilder()
                        .setFullTestName("pkg.class#test$1")
                        .setTestClass("pkg.class")
                        .setMethod("test")
                        .build();
        File reportFile1 = createDimensionalMetricReportFile("report1.textproto", testId, 1.0, 3.0);
        File reportFile2 = createDimensionalMetricReportFile("report2.textproto", testId, 5.0);

        List<LogFile> reportFiles =
                Arrays.asList(
                        new LogFile(reportFile1.getAbsolutePath(), "url1", LogDataType.TEXTPB),
                        new LogFile(reportFile2.getAbsolutePath(), "url2", LogDataType.TEXTPB));

        try {
            mResultsFile =
                    mMetricUtil.aggregateDimensionalMetricsAndWriteToFile(
                            reportFiles, new HashSet<>(Arrays.asList(90)), "$");

            assertNotNull(mResultsFile);
            MetricReport.Builder aggregatedReportBuilder = MetricReport.newBuilder();
            TextFormat.getParser()
                    .merge(FileUtil.readStringFromFile(mResultsFile), aggregatedReportBuilder);
            MetricReport aggregatedReport = aggregatedReportBuilder.build();

            assertEquals(1, aggregatedReport.getTestMetricResultsCount());
            TestMetricResult aggregatedResult = aggregatedReport.getTestMetricResults(0);
            assertEquals("pkg.class#test", aggregatedResult.getIdentifier().getFullTestName());
            assertEquals(12, aggregatedResult.getMetricRecordsCount());

            Map<String, Double> stats = new HashMap<>();
            for (MetricRecord record : aggregatedResult.getMetricRecordsList()) {
                assertEquals("metric_id_1", record.getMetricId());
                assertEquals(1, record.getDimensionalMetricsCount());
                DimensionalMetric dm = record.getDimensionalMetrics(0);
                ComputedStatistics computed = dm.getComputedStatistics();
                assertEquals(1, computed.getStatsCount());
                Stat stat = computed.getStats(0);
                stats.put(stat.getKey(), stat.getValue().getDoubleValue());
            }

            assertEquals(1.0, stats.get("min"), 0.01);
            assertEquals(5.0, stats.get("max"), 0.01);
            assertEquals(3.0, stats.get("mean"), 0.01);
            assertEquals(3.0, stats.get("median"), 0.01);
            assertEquals(9.0, stats.get("total"), 0.01);
            assertEquals(3.0, stats.get("metric-count"), 0.01);
            assertEquals(2.67, stats.get("var"), 0.01);
            assertEquals(1.63, stats.get("stdev"), 0.01);
            assertEquals(4.6, stats.get("p90"), 0.01);

        } finally {
            FileUtil.deleteFile(reportFile1);
            FileUtil.deleteFile(reportFile2);
        }
    }

    @Test
    public void testAggregateDimensionalMetricsWithStrings() throws IOException {
        TestIdentifier testId =
                TestIdentifier.newBuilder()
                        .setFullTestName("pkg.class#test$1")
                        .setTestClass("pkg.class")
                        .setMethod("test")
                        .build();
        File reportFile1 =
                createDimensionalMetricReportFile("report1.textproto", testId, "1.0", "3.0, 5.0");
        File reportFile2 = createDimensionalMetricReportFile("report2.textproto", testId, "2.0");

        List<LogFile> reportFiles =
                Arrays.asList(
                        new LogFile(reportFile1.getAbsolutePath(), "url1", LogDataType.TEXTPB),
                        new LogFile(reportFile2.getAbsolutePath(), "url2", LogDataType.TEXTPB));

        try {
            mResultsFile =
                    mMetricUtil.aggregateDimensionalMetricsAndWriteToFile(
                            reportFiles, new HashSet<>(Arrays.asList(90)), "$");

            assertNotNull(mResultsFile);
            MetricReport.Builder aggregatedReportBuilder = MetricReport.newBuilder();
            TextFormat.getParser()
                    .merge(FileUtil.readStringFromFile(mResultsFile), aggregatedReportBuilder);
            MetricReport aggregatedReport = aggregatedReportBuilder.build();

            assertEquals(1, aggregatedReport.getTestMetricResultsCount());
            TestMetricResult aggregatedResult = aggregatedReport.getTestMetricResults(0);
            assertEquals("pkg.class#test", aggregatedResult.getIdentifier().getFullTestName());
            assertEquals(12, aggregatedResult.getMetricRecordsCount());

            Map<String, Double> stats = new HashMap<>();
            for (MetricRecord record : aggregatedResult.getMetricRecordsList()) {
                assertEquals("metric_id_1", record.getMetricId());
                assertEquals(1, record.getDimensionalMetricsCount());
                DimensionalMetric dm = record.getDimensionalMetrics(0);
                ComputedStatistics computed = dm.getComputedStatistics();
                assertEquals(1, computed.getStatsCount());
                Stat stat = computed.getStats(0);
                stats.put(stat.getKey(), stat.getValue().getDoubleValue());
            }

            assertEquals(1.0, stats.get("min"), 0.01);
            assertEquals(5.0, stats.get("max"), 0.01);
            assertEquals(2.75, stats.get("mean"), 0.01);
            assertEquals(2.5, stats.get("median"), 0.01);
            assertEquals(11.0, stats.get("total"), 0.01);
            assertEquals(4.0, stats.get("metric-count"), 0.01);

        } finally {
            FileUtil.deleteFile(reportFile1);
            FileUtil.deleteFile(reportFile2);
        }
    }

    private File createDimensionalMetricReportFile(
            String name, TestIdentifier testId, Object... values) throws IOException {
        RawValues.Builder rawValuesBuilder = RawValues.newBuilder();
        for (Object v : values) {
            if (v instanceof Double) {
                rawValuesBuilder.addValues(Value.newBuilder().setDoubleValue((Double) v).build());
            } else if (v instanceof String) {
                rawValuesBuilder.addValues(Value.newBuilder().setStringValue((String) v).build());
            }
        }

        DimensionalMetric dimensionalMetric =
                DimensionalMetric.newBuilder()
                        .addDimensions(
                                Dimension.newBuilder()
                                        .setKey("dim1")
                                        .setValue(Value.newBuilder().setStringValue("val1").build())
                                        .build())
                        .setRawValues(rawValuesBuilder.build())
                        .setLegacyMetricKey("legacy_metric_key")
                        .build();

        MetricRecord metricRecord =
                MetricRecord.newBuilder()
                        .setMetricId("metric_id_1")
                        .addDimensionalMetrics(dimensionalMetric)
                        .build();

        TestMetricResult testResult =
                TestMetricResult.newBuilder()
                        .setIdentifier(testId)
                        .addMetricRecords(metricRecord)
                        .build();

        MetricReport report = MetricReport.newBuilder().addTestMetricResults(testResult).build();

        File reportFile = FileUtil.createTempFile(name, ".textproto");
        FileUtil.writeToFile(TextFormat.printer().printToString(report), reportFile);
        return reportFile;
    }

    /**
     * Method to get the sample output based on the give test cases.
     *
     * @param outputType
     * @return sample output in string format.
     */
    private String getSampleOutput(String outputType) {
        switch (outputType) {
            case OUTPUT_1:
                return "pkg_name#test_name\n"
                        + "\n"
                        + "first_test_metric_1:2.9\n"
                        + "first_test_metric_2:3\n"
                        + "\n"
                        + "\n";
            case OUTPUT_2:
                return "pkg_name#test_name_1\n"
                        + "\n"
                        + "first_test_metric_1:2.9\n"
                        + "first_test_metric_2:3\n"
                        + "\n"
                        + "\n"
                        + "pkg_name#test_name_2\n"
                        + "\n"
                        + "second_test_metric_1:5.9\n"
                        + "second_test_metric_2:0\n"
                        + "\n"
                        + "\n";
            case OUTPUT_3:
                return "pkg_name#test_name\n" + "\n" + "\n" + "\n";
            case OUTPUT_4:
                return "pkg_name#test_name\n"
                        + "\n"
                        + "third_test_metric-min:1.00\n"
                        + "third_test_metric-max:3.00\n"
                        + "third_test_metric-mean:2.00\n"
                        + "third_test_metric-var:0.67\n"
                        + "third_test_metric-stdev:0.82\n"
                        + "third_test_metric-median:2.00\n"
                        + "third_test_metric-total:6.00\n"
                        + "third_test_metric-metric-count:3.00\n"
                        + "third_test_metric-last-first-diff:2.00\n"
                        + "third_test_metric-mad:1.00\n"
                        + "third_test_metric-mad-coeff:50.00\n"
                        + "\n"
                        + "\n";
            case OUTPUT_5:
                return "pkg_name#test_name\n"
                        + "\n"
                        + "third_test_metric-min:1.00\n"
                        + "third_test_metric-max:3.00\n"
                        + "third_test_metric-mean:2.00\n"
                        + "third_test_metric-var:0.67\n"
                        + "third_test_metric-stdev:0.82\n"
                        + "third_test_metric-median:2.00\n"
                        + "third_test_metric-total:6.00\n"
                        + "third_test_metric-metric-count:3.00\n"
                        + "third_test_metric-last-first-diff:2.00\n"
                        + "third_test_metric-mad:1.00\n"
                        + "third_test_metric-mad-coeff:50.00\n"
                        + "third_test_metric-p50:2.00\n"
                        + "third_test_metric-p90:2.80\n"
                        + "third_test_metric-p99:2.98\n"
                        + "\n"
                        + "\n";
            case OUTPUT_6:
                return "pkg#test1\n"
                        + "\n"
                        + "first_test_metric-min:1.50\n"
                        + "first_test_metric-max:2.90\n"
                        + "first_test_metric-mean:2.20\n"
                        + "first_test_metric-var:0.49\n"
                        + "first_test_metric-stdev:0.70\n"
                        + "first_test_metric-median:2.20\n"
                        + "first_test_metric-total:4.40\n"
                        + "first_test_metric-metric-count:2.00\n"
                        + "first_test_metric-last-first-diff:-1.40\n"
                        + "first_test_metric-mad:0.70\n"
                        + "first_test_metric-mad-coeff:31.82\n"
                        + "\n"
                        + "\n";
            case OUTPUT_7:
                return "pkg#test1\n"
                        + "\n"
                        + "first_test_metric-min:2.90\n"
                        + "first_test_metric-max:2.90\n"
                        + "first_test_metric-mean:2.90\n"
                        + "first_test_metric-var:0.00\n"
                        + "first_test_metric-stdev:0.00\n"
                        + "first_test_metric-median:2.90\n"
                        + "first_test_metric-total:2.90\n"
                        + "first_test_metric-metric-count:1.00\n"
                        + "first_test_metric-last-first-diff:2.90\n"
                        + "first_test_metric-mad:0.00\n"
                        + "first_test_metric-mad-coeff:0.00\n"
                        + "\n"
                        + "\n"
                        + "pkg#test2\n"
                        + "\n"
                        + "second_test_metric-min:1.50\n"
                        + "second_test_metric-max:1.50\n"
                        + "second_test_metric-mean:1.50\n"
                        + "second_test_metric-var:0.00\n"
                        + "second_test_metric-stdev:0.00\n"
                        + "second_test_metric-median:1.50\n"
                        + "second_test_metric-total:1.50\n"
                        + "second_test_metric-metric-count:1.00\n"
                        + "second_test_metric-last-first-diff:1.50\n"
                        + "second_test_metric-mad:0.00\n"
                        + "second_test_metric-mad-coeff:0.00\n"
                        + "\n"
                        + "\n";
            case OUTPUT_8:
                return "pkg#test1\n" + "\n" + "\n" + "\n";
            case OUTPUT_9:
                return "pkg#test1\n"
                        + "\n"
                        + "first_test_metric-min:2.90\n"
                        + "\n"
                        + "\n"
                        + "pkg#test2\n"
                        + "\n"
                        + "second_test_metric-min:1.50\n"
                        + "\n"
                        + "\n";
            case OUTPUT_10: // Scenario 2: metrics-use-for-test-iterations = [".*"]
                // my_metric-mean: (1.0 + 2.0 + 3.0) / 3 passed iterations = 2.00
                // another_metric-mean: (2.0 + 4.0) / 3 passed iterations = 2.00
                return "pkg#test1\n"
                        + "\n"
                        + "another_metric-min:2.00\n"
                        + "another_metric-max:4.00\n"
                        + "another_metric-mean:2.00\n"
                        + "another_metric-var:2.00\n"
                        + "another_metric-stdev:1.41\n"
                        + "another_metric-median:3.00\n"
                        + "another_metric-total:6.00\n"
                        + "another_metric-metric-count:2.00\n"
                        + "another_metric-last-first-diff:2.00\n"
                        + "another_metric-mad:1.00\n"
                        + "another_metric-mad-coeff:33.33\n"
                        + "my_metric-min:1.00\n"
                        + "my_metric-max:3.00\n"
                        + "my_metric-mean:2.00\n"
                        + "my_metric-var:0.67\n"
                        + "my_metric-stdev:0.82\n"
                        + "my_metric-median:2.00\n"
                        + "my_metric-total:6.00\n"
                        + "my_metric-metric-count:3.00\n"
                        + "my_metric-last-first-diff:2.00\n"
                        + "my_metric-mad:1.00\n"
                        + "my_metric-mad-coeff:50.00\n"
                        + "\n"
                        + "\n";
            case OUTPUT_11: // Scenario 2: metrics-use-for-test-iterations = [".*"]
                // another_metric-mean: 9.0 / 3 passed iterations = 3.00
                // my_metric-mean: (1.0 + 2.0) / 3 passed iterations = 1.00
                return "pkg#test1\n"
                        + "\n"
                        + "another_metric-min:9.00\n"
                        + "another_metric-max:9.00\n"
                        + "another_metric-mean:3.00\n"
                        + "another_metric-var:36.00\n"
                        + "another_metric-stdev:6.00\n"
                        + "another_metric-median:9.00\n"
                        + "another_metric-total:9.00\n"
                        + "another_metric-metric-count:1.00\n"
                        + "another_metric-last-first-diff:9.00\n"
                        + "another_metric-mad:0.00\n"
                        + "another_metric-mad-coeff:0.00\n"
                        + "my_metric-min:1.00\n"
                        + "my_metric-max:2.00\n"
                        + "my_metric-mean:1.00\n"
                        + "my_metric-var:0.50\n"
                        + "my_metric-stdev:0.71\n"
                        + "my_metric-median:1.50\n"
                        + "my_metric-total:3.00\n"
                        + "my_metric-metric-count:2.00\n"
                        + "my_metric-last-first-diff:1.00\n"
                        + "my_metric-mad:0.50\n"
                        + "my_metric-mad-coeff:33.33\n"
                        + "\n"
                        + "\n";
            case OUTPUT_12: // Failed iterations excluded from sum and count for mean
                // my_metric-mean: 10.0 / 1 passed iterations = 10.00
                // my_metric-total is sum of passed: 10 = 10
                // my_metric-metric-count is from passed iterations
                return "pkg#test1\n"
                        + "\n"
                        + "my_metric-min:10.00\n"
                        + "my_metric-max:10.00\n"
                        + "my_metric-mean:10.00\n"
                        + "my_metric-var:0.00\n"
                        + "my_metric-stdev:0.00\n"
                        + "my_metric-median:10.00\n"
                        + "my_metric-total:10.00\n"
                        + "my_metric-metric-count:1.00\n"
                        + "my_metric-last-first-diff:10.00\n"
                        + "my_metric-mad:0.00\n"
                        + "my_metric-mad-coeff:0.00\n"
                        + "\n"
                        + "\n";
            case OUTPUT_13: // Scenario 3: Regex filter for passed iterations
                // matching_metric-mean: (10+20+30) / 3 passed iterations = 20.00
                // unmatched_metric-mean: (100+200+300) / 3 metric values = 200.00
                return "pkg#test1\n"
                        + "\n"
                        + "matching_metric-min:10.00\n"
                        + "matching_metric-max:30.00\n"
                        + "matching_metric-mean:20.00\n"
                        + "matching_metric-var:66.67\n"
                        + "matching_metric-stdev:8.16\n"
                        + "matching_metric-median:20.00\n"
                        + "matching_metric-total:60.00\n"
                        + "matching_metric-metric-count:3.00\n"
                        + "matching_metric-last-first-diff:20.00\n"
                        + "matching_metric-mad:10.00\n"
                        + "matching_metric-mad-coeff:50.00\n"
                        + "unmatched_metric-min:100.00\n"
                        + "unmatched_metric-max:300.00\n"
                        + "unmatched_metric-mean:200.00\n"
                        + "unmatched_metric-var:6666.67\n"
                        + "unmatched_metric-stdev:81.65\n"
                        + "unmatched_metric-median:200.00\n"
                        + "unmatched_metric-total:600.00\n"
                        + "unmatched_metric-metric-count:3.00\n"
                        + "unmatched_metric-last-first-diff:200.00\n"
                        + "unmatched_metric-mad:100.00\n"
                        + "unmatched_metric-mad-coeff:50.00\n"
                        + "\n"
                        + "\n";
            default:
                return null;
        }
    }
}
