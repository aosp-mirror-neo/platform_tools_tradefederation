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

import com.android.annotations.VisibleForTesting;
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
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Measurements;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.math.Quantiles;
import com.google.protobuf.TextFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Contains common utility methods for storing the test metrics, aggregating the metrics in similar
 * tests and writing the metrics to a file.
 */
public class MetricUtility {

    private static final String TEST_HEADER_SEPARATOR = "\n\n";
    private static final String METRIC_SEPARATOR = "\n";
    private static final String METRIC_KEY_VALUE_SEPARATOR = ":";
    private static final String STATS_KEY_MIN = "min";
    private static final String STATS_KEY_MAX = "max";
    private static final String STATS_KEY_MEAN = "mean";
    private static final String STATS_KEY_VAR = "var";
    private static final String STATS_KEY_STDEV = "stdev";
    private static final String STATS_KEY_MEDIAN = "median";
    private static final String STATS_KEY_TOTAL = "total";
    private static final String STATS_KEY_COUNT = "metric-count";
    private static final String STATS_KEY_DIFF = "last-first-diff";
    private static final String STATS_KEY_MAD = "mad";
    private static final String STATS_KEY_MAD_COEFF = "mad-coeff";
    private static final String STATS_KEY_PERCENTILE_PREFIX = "p";
    private static final String STATS_KEY_SEPARATOR = "-";
    private static final Joiner CLASS_METHOD_JOINER = Joiner.on("#").skipNulls();

    // Used to separate the package name from the iteration number. Default is set to "$".
    private String mTestIterationSeparator = "$";

    // Percentiles to include when calculating the aggregates.
    private Set<Integer> mActualPercentiles = new LinkedHashSet<>();

    // Store the test metrics for aggregation at the end of test run.
    // Outer map key is the test id and inner map key is the metric key name.
    private Map<String, ArrayListMultimap<String, Metric>> mStoredTestMetrics =
            new LinkedHashMap<String, ArrayListMultimap<String, Metric>>();

    private Map<String, Integer> mTestIterationCounts = new HashMap<>();
    private boolean mEnableCountTestIterations = false;

    private List<Pattern> mMetricPatterns = new ArrayList<>();

    /**
     * Used for storing the individual test metrics and use it for aggregation.
     *
     * @param testDescription contains the test details like class name and test name.
     * @param testMetrics metrics collected for the test.
     */
    public void storeTestMetrics(TestDescription testDescription, Map<String, Metric> testMetrics) {

        if (testMetrics == null) {
            return;
        }

        // Group test cases which differs only by the iteration separator or test the same name.
        // Removes iteration numbers like "$17".
        String newTestId =
                CLASS_METHOD_JOINER
                        .join(testDescription.getClassName(), testDescription.getTestName())
                        .replaceFirst(Pattern.quote(mTestIterationSeparator) + "\\d+", "");
        if (!mStoredTestMetrics.containsKey(newTestId)) {
            mStoredTestMetrics.put(newTestId, ArrayListMultimap.create());
            if (mEnableCountTestIterations) {
                mTestIterationCounts.put(newTestId, 0);
            }
        }
        if (mEnableCountTestIterations) {
            mTestIterationCounts.put(newTestId, mTestIterationCounts.get(newTestId) + 1);
        }
        ArrayListMultimap<String, Metric> storedMetricsForThisTest =
                mStoredTestMetrics.get(newTestId);
        for (Map.Entry<String, Metric> entry : testMetrics.entrySet()) {
            storedMetricsForThisTest.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Write metrics to a file.
     *
     * @param testFileSuffix is used as suffix in the test metric file name.
     * @param testHeaderName metrics will be written under the test header name.
     * @param metrics to write in the file.
     * @param resultsFile if null create a new file and write the metrics otherwise append the test
     *     header name and metric to the file.
     * @return file with the metric.
     */
    public File writeResultsToFile(
            String testFileSuffix,
            String testHeaderName,
            Map<String, String> metrics,
            File resultsFile) {

        if (resultsFile == null) {
            try {
                resultsFile =
                        FileUtil.createTempFile(
                                String.format("test_results_%s_", testFileSuffix), "");
            } catch (IOException e) {
                CLog.e(e);
                return resultsFile;
            }
        }

        try (FileOutputStream outputStream = new FileOutputStream(resultsFile, true)) {
            // Write the header description name.
            outputStream.write(
                    String.format("%s%s", testHeaderName, TEST_HEADER_SEPARATOR).getBytes());
            for (Map.Entry<String, String> entry : metrics.entrySet()) {
                String test_metric =
                        String.format(
                                "%s%s%s",
                                entry.getKey(), METRIC_KEY_VALUE_SEPARATOR, entry.getValue());
                outputStream.write(String.format("%s%s", test_metric, METRIC_SEPARATOR).getBytes());
            }
            outputStream.write(TEST_HEADER_SEPARATOR.getBytes());
        } catch (IOException ioe) {
            CLog.e(ioe);
        }
        return resultsFile;
    }

    /**
     * Aggregate comma separated metrics.
     *
     * @param rawMetrics metrics collected during the test run.
     * @return aggregated metrics.
     */
    public Map<String, Metric> aggregateMetrics(Map<String, Metric> rawMetrics) {
        Map<String, Metric> aggregateMetrics = new LinkedHashMap<String, Metric>();
        for (Map.Entry<String, Metric> entry : rawMetrics.entrySet()) {
            String values = getMeasurementAsString(entry.getValue().getMeasurements());
            List<String> splitVals = Arrays.asList(values.split(",", 0));
            // Build stats for keys with any values, even only one.
            if (isAllDoubleValues(splitVals)) {
                buildStats(entry.getKey(), splitVals, aggregateMetrics, 0);
            }
        }
        return aggregateMetrics;
    }

    /**
     * Aggregate the metrics collected from multiple iterations of the test and write the aggregated
     * metrics to a test result file.
     *
     * @param fileName file name to use while writing the metrics.
     */
    public File aggregateStoredTestMetricsAndWriteToFile(String fileName) {
        File resultsFile = null;
        for (String testName : mStoredTestMetrics.keySet()) {
            ArrayListMultimap<String, Metric> currentTest = mStoredTestMetrics.get(testName);
            int iterations = mTestIterationCounts.getOrDefault(testName, 0);
            Map<String, Metric> aggregateMetrics = new LinkedHashMap<String, Metric>();
            List<String> keys = new ArrayList<>(currentTest.keySet());
            Collections.sort(keys);
            for (String metricKey : keys) {
                List<Metric> metrics = currentTest.get(metricKey);
                List<Measurements> measures =
                        metrics.stream().map(Metric::getMeasurements).collect(Collectors.toList());
                // Parse metrics into a list of SingleString values, concating lists in the process
                List<String> rawValues =
                        measures.stream()
                                .map(MetricUtility::getMeasurementAsString)
                                .map(
                                        m -> {
                                            // Split results; also deals with the case of empty
                                            // results
                                            // in a certain run
                                            List<String> splitVals = Arrays.asList(m.split(",", 0));
                                            if (splitVals.size() == 1
                                                    && splitVals.get(0).isEmpty()) {
                                                return Collections.<String>emptyList();
                                            }
                                            return splitVals;
                                        })
                                .flatMap(Collection::stream)
                                .map(String::trim)
                                .collect(Collectors.toList());
                // Do not report empty metrics
                if (rawValues.isEmpty()) {
                    continue;
                }
                if (isAllDoubleValues(rawValues)) {
                    buildStats(metricKey, rawValues, aggregateMetrics, iterations);
                }
            }

            aggregateMetrics =
                    mMetricPatterns.size() > 0 ? filterMetrics(aggregateMetrics) : aggregateMetrics;
            Map<String, String> compatibleTestMetrics =
                    TfMetricProtoUtil.compatibleConvert(aggregateMetrics);

            resultsFile =
                    writeResultsToFile(fileName, testName, compatibleTestMetrics, resultsFile);
        }
        return resultsFile;
    }

    public void setPercentiles(Set<Integer> percentiles) {
        mActualPercentiles = percentiles;
    }

    public void setIterationSeparator(String separator) {
        mTestIterationSeparator = separator;
    }

    public void setCountTestIterationsFlag(boolean enableCountTestIterations) {
        mEnableCountTestIterations = enableCountTestIterations;
    }

    @VisibleForTesting
    public Map<String, ArrayListMultimap<String, Metric>> getStoredTestMetric() {
        return mStoredTestMetrics;
    }

    /**
     * Return true is all the values can be parsed to double value. Otherwise return false.
     *
     * @param rawValues list whose values are validated.
     */
    public static boolean isAllDoubleValues(List<String> rawValues) {
        return rawValues.stream()
                .allMatch(
                        val -> {
                            try {
                                Double.parseDouble(val);
                                return true;
                            } catch (NumberFormatException e) {
                                return false;
                            }
                        });
    }

    /**
     * Compute the stats from the give list of values.
     *
     * @param values raw values to compute the aggregation.
     * @param percentiles stats to include in the final metrics.
     * @param testIterations number of iterations of the test.
     * @return aggregated values.
     */
    public static Map<String, Double> getStats(
            List<Double> values, Set<Integer> percentiles, int testIterations) {
        Map<String, Double> stats = new LinkedHashMap<>();
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double count = values.size();
        // The orElse situation should never happen.
        double mean;
        if (testIterations > 0) {
            mean = sum / testIterations;
        } else {
            mean =
                    values.stream()
                            .mapToDouble(Double::doubleValue)
                            .average()
                            .orElseThrow(IllegalStateException::new);
        }
        double variance = values.stream().reduce(0.0, (a, b) -> a + Math.pow(b - mean, 2) / count);
        // Calculate percentiles. 50 th percentile will be used as median.
        Set<Integer> updatedPercentile = new LinkedHashSet<>(percentiles);
        updatedPercentile.add(50);
        Map<Integer, Double> percentileStat =
                Quantiles.percentiles().indexes(updatedPercentile).compute(values);
        double median = percentileStat.get(50);
        // Diff of last and first value from the list. Return the first value if only one value is
        // present.
        double diff =
                (values.size() > 1)
                        ? (values.get(values.size() - 1) - values.get(0))
                        : values.get(0);
        // Calculate median absolute deviation
        List<Double> absoluteDeviations =
                values.stream().map(v -> Math.abs(v - median)).collect(Collectors.toList());
        double mad = Quantiles.median().compute(absoluteDeviations);
        double madCoefficient = (mad / median) * 100;

        stats.put(STATS_KEY_MIN, Collections.min(values));
        stats.put(STATS_KEY_MAX, Collections.max(values));
        stats.put(STATS_KEY_MEAN, mean);
        stats.put(STATS_KEY_VAR, variance);
        stats.put(STATS_KEY_STDEV, Math.sqrt(variance));
        stats.put(STATS_KEY_MEDIAN, median);
        stats.put(STATS_KEY_TOTAL, sum);
        stats.put(STATS_KEY_COUNT, count);
        stats.put(STATS_KEY_DIFF, diff);
        stats.put(STATS_KEY_MAD, mad);
        stats.put(STATS_KEY_MAD_COEFF, madCoefficient);
        percentileStat.entrySet().stream()
                .forEach(
                        e -> {
                            // If the percentile is 50, only include it if the user asks for it
                            // explicitly.
                            if (e.getKey() != 50 || percentiles.contains(50)) {
                                stats.put(
                                        STATS_KEY_PERCENTILE_PREFIX + e.getKey().toString(),
                                        e.getValue());
                            }
                        });
        return stats;
    }

    /**
     * Build stats for the given set of values and build the metrics using the metric key and stats
     * name and update the results in aggregated metrics.
     *
     * @param metricKey key to which the values correspond to.
     * @param values list of raw values.
     * @param aggregateMetrics where final metrics will be stored.
     * @param testIterations number of iterations of the test.
     */
    private void buildStats(
            String metricKey,
            List<String> values,
            Map<String, Metric> aggregateMetrics,
            int testIterations) {
        List<Double> doubleValues =
                values.stream().map(Double::parseDouble).collect(Collectors.toList());
        Map<String, Double> stats = getStats(doubleValues, mActualPercentiles, testIterations);
        for (String statKey : stats.keySet()) {
            Metric.Builder metricBuilder = Metric.newBuilder();
            metricBuilder
                    .getMeasurementsBuilder()
                    .setSingleString(String.format("%2.2f", stats.get(statKey)));
            aggregateMetrics.put(
                    String.join(STATS_KEY_SEPARATOR, metricKey, statKey), metricBuilder.build());
        }
    }

    /** Build regular expression patterns to filter the metrics. */
    public void buildMetricFilterPatterns(Set<String> strictIncludeRegEx) {
        if (!strictIncludeRegEx.isEmpty() && mMetricPatterns.isEmpty()) {
            for (String regEx : strictIncludeRegEx) {
                mMetricPatterns.add(Pattern.compile(regEx));
            }
        }
    }

    /** Filter the metrics that matches the pattern. */
    public Map<String, Metric> filterMetrics(Map<String, Metric> parsedMetrics) {
        Map<String, Metric> filteredMetrics = new HashMap<>();
        for (Entry<String, Metric> metricEntry : parsedMetrics.entrySet()) {
            for (Pattern pattern : mMetricPatterns) {
                if (pattern.matcher(metricEntry.getKey()).matches()) {
                    filteredMetrics.put(metricEntry.getKey(), metricEntry.getValue());
                    break;
                }
            }
        }
        return filteredMetrics;
    }

    /**
     * Extracts the metric value from a {@link Measurements} proto as a string.
     *
     * <p>Handles single string and single double values.
     *
     * @param measurements The proto to extract from.
     * @return The value as a string, or an empty string if not set or not a supported type.
     */
    private static String getMeasurementAsString(Measurements measurements) {
        if (measurements.hasSingleString()) {
            return measurements.getSingleString();
        }
        if (measurements.hasSingleDouble()) {
            return String.valueOf(measurements.getSingleDouble());
        }
        if (measurements.hasSingleInt()) {
            return String.valueOf(measurements.getSingleInt());
        }
        return "";
    }

    /** A type-safe key for grouping dimensional metrics for aggregation. */
    private static final class DimensionalMetricKey {
        private final String mBaseTestName;
        private final String mTestClass;
        private final String mTestMethod;
        private final String mMetricId;
        private final List<Dimension> mDimensions;

        public DimensionalMetricKey(
                String baseTestName,
                String testClass,
                String testMethod,
                String metricId,
                List<Dimension> dimensions) {
            mBaseTestName = baseTestName;
            mTestClass = testClass;
            mTestMethod = testMethod;
            mMetricId = metricId;
            List<Dimension> sortedDimensions = new ArrayList<>(dimensions);
            sortedDimensions.sort(Comparator.comparing(Dimension::getKey));
            mDimensions = Collections.unmodifiableList(sortedDimensions);
        }

        public String getBaseTestName() {
            return mBaseTestName;
        }

        public String getTestClass() {
            return mTestClass;
        }

        public String getTestMethod() {
            return mTestMethod;
        }

        public String getMetricId() {
            return mMetricId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DimensionalMetricKey that = (DimensionalMetricKey) o;
            return Objects.equals(mBaseTestName, that.mBaseTestName)
                    && Objects.equals(mTestClass, that.mTestClass)
                    && Objects.equals(mTestMethod, that.mTestMethod)
                    && Objects.equals(mMetricId, that.mMetricId)
                    && Objects.equals(mDimensions, that.mDimensions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mBaseTestName, mTestClass, mTestMethod, mMetricId, mDimensions);
        }
    }

    /** Helper class to hold metric data during aggregation. */
    private static class AggregationData {
        MetricRecord recordTemplate;
        DimensionalMetric dimensionalMetricTemplate;
        List<Double> rawValues = new ArrayList<>();
    }

    /**
     * Reads all collected dimensional metric report files, aggregates the raw values, computes
     * statistics, and writes a new aggregated report file.
     *
     * @param dimensionalMetricFiles The list of metric report files.
     * @param percentiles The percentiles to compute.
     * @param testIterationSeparator The separator for test iterations.
     * @return A {@link File} containing the aggregated report, or null if no aggregation was
     *     performed.
     */
    public File aggregateDimensionalMetricsAndWriteToFile(
            List<LogFile> dimensionalMetricFiles,
            Set<Integer> percentiles,
            String testIterationSeparator) {
        if (dimensionalMetricFiles.isEmpty()) {
            return null;
        }

        Map<DimensionalMetricKey, AggregationData> aggregationMap =
                parseAndCollectRawValues(dimensionalMetricFiles, testIterationSeparator);

        if (aggregationMap.isEmpty()) {
            CLog.w("No dimensional metrics with raw values were found to aggregate.");
            return null;
        }

        MetricReport aggregatedReport = buildAggregatedReport(aggregationMap, percentiles);

        return writeAggregatedReportToFile(aggregatedReport);
    }

    private Map<DimensionalMetricKey, AggregationData> parseAndCollectRawValues(
            List<LogFile> dimensionalMetricFiles, String testIterationSeparator) {
        Map<DimensionalMetricKey, AggregationData> aggregationMap = new HashMap<>();

        for (LogFile logFile : dimensionalMetricFiles) {
            MetricReport report;
            try (FileReader reader = new FileReader(logFile.getPath())) {
                MetricReport.Builder reportBuilder = MetricReport.newBuilder();
                TextFormat.Parser.newBuilder()
                        .setAllowUnknownFields(true)
                        .build()
                        .merge(reader, reportBuilder);
                report = reportBuilder.build();
            } catch (IOException e) {
                CLog.e("Error reading or parsing dimensional metric file: %s", logFile.getPath());
                CLog.e(e);
                continue;
            }
            for (TestMetricResult result : report.getTestMetricResultsList()) {
                String baseTestName =
                        getBaseTestName(result.getIdentifier(), testIterationSeparator);
                String baseTestClass =
                        getBaseTestClass(result.getIdentifier(), testIterationSeparator);
                String baseTestMethod =
                        getBaseTestMethod(result.getIdentifier(), testIterationSeparator);
                for (MetricRecord record : result.getMetricRecordsList()) {
                    for (DimensionalMetric dimensionalMetric : record.getDimensionalMetricsList()) {
                        if (!dimensionalMetric.hasRawValues()) {
                            continue;
                        }
                        DimensionalMetricKey aggregationKey =
                                new DimensionalMetricKey(
                                        baseTestName,
                                        baseTestClass,
                                        baseTestMethod,
                                        record.getMetricId(),
                                        dimensionalMetric.getDimensionsList());

                        AggregationData data =
                                aggregationMap.computeIfAbsent(
                                        aggregationKey, k -> new AggregationData());
                        // Store the templates. They will be overwritten on each iteration.
                        // This assumes that for a given metric (test name + id +
                        // dimensions), all metadata (unit, polarity, etc.) is identical
                        // across all iterations. The last seen value will be used as the
                        // template for the new aggregated record.
                        data.recordTemplate = record.toBuilder().clearDimensionalMetrics().build();
                        data.dimensionalMetricTemplate =
                                dimensionalMetric.toBuilder().clearDataPayload().build();
                        data.rawValues.addAll(extractDoubles(dimensionalMetric.getRawValues()));
                    }
                }
            }
        }
        return aggregationMap;
    }

    private MetricReport buildAggregatedReport(
            Map<DimensionalMetricKey, AggregationData> aggregationMap, Set<Integer> percentiles) {
        MetricReport.Builder aggregatedReportBuilder = MetricReport.newBuilder();

        Map<String, List<Map.Entry<DimensionalMetricKey, AggregationData>>> groupedByTest =
                aggregationMap.entrySet().stream()
                        .collect(Collectors.groupingBy(entry -> entry.getKey().getBaseTestName()));

        for (Map.Entry<String, List<Map.Entry<DimensionalMetricKey, AggregationData>>> testEntry :
                groupedByTest.entrySet()) {
            String baseTestName = testEntry.getKey();
            List<Map.Entry<DimensionalMetricKey, AggregationData>> metricsForTest =
                    testEntry.getValue();

            DimensionalMetricKey firstKey = metricsForTest.get(0).getKey();

            TestMetricResult.Builder testResultBuilder = TestMetricResult.newBuilder();
            testResultBuilder
                    .getIdentifierBuilder()
                    .setFullTestName(baseTestName)
                    .setTestClass(firstKey.getTestClass())
                    .setMethod(firstKey.getTestMethod());

            for (Map.Entry<DimensionalMetricKey, AggregationData> metricEntry : metricsForTest) {
                AggregationData data = metricEntry.getValue();
                if (data.rawValues.isEmpty()) {
                    continue;
                }

                Map<String, Double> stats = getStats(data.rawValues, percentiles, 0);

                for (Map.Entry<String, Double> statEntry : stats.entrySet()) {
                    String newLegacyMetricKey =
                            String.join(
                                    STATS_KEY_SEPARATOR,
                                    data.dimensionalMetricTemplate.getLegacyMetricKey(),
                                    statEntry.getKey());

                    // Create a new dimensional metric with a single computed statistic.
                    // We can't have multiple computed stats in the same dimensional metric, so
                    // we create a new one with a new legacy metric key.
                    testResultBuilder.addMetricRecords(
                            MetricRecord.newBuilder()
                                    .mergeFrom(data.recordTemplate)
                                    .addDimensionalMetrics(
                                            DimensionalMetric.newBuilder()
                                                    .mergeFrom(data.dimensionalMetricTemplate)
                                                    .setComputedStatistics(
                                                            ComputedStatistics.newBuilder()
                                                                    .addStats(
                                                                            Stat.newBuilder()
                                                                                    .setKey(
                                                                                            statEntry
                                                                                                    .getKey())
                                                                                    .setValue(
                                                                                            Value
                                                                                                    .newBuilder()
                                                                                                    .setDoubleValue(
                                                                                                            statEntry
                                                                                                                    .getValue()))))
                                                    .setLegacyMetricKey(newLegacyMetricKey)));
                }
            }
            aggregatedReportBuilder.addTestMetricResults(testResultBuilder);
        }
        return aggregatedReportBuilder.build();
    }

    private File writeAggregatedReportToFile(MetricReport aggregatedReport) {
        File aggregatedReportFile = null;
        try {
            aggregatedReportFile =
                    FileUtil.createTempFile("aggregated_dimensional_metrics", ".textproto");
            FileUtil.writeToFile(
                    TextFormat.printer().printToString(aggregatedReport), aggregatedReportFile);
            return aggregatedReportFile;
        } catch (IOException e) {
            CLog.e("Failed to write aggregated dimensional metrics report.");
            CLog.e(e);
            FileUtil.deleteFile(aggregatedReportFile);
        }
        return null;
    }

    /**
     * Extracts a base test name from a TestIdentifier, stripping any iteration suffixes.
     *
     * @param identifier The TestIdentifier proto.
     * @param separator The separator used for iterations (e.g., "$").
     * @return The base test name.
     */
    private static String getBaseTestName(TestIdentifier identifier, String separator) {
        String fullTestName = identifier.getFullTestName();
        if (fullTestName.isEmpty()) {
            fullTestName =
                    String.format("%s#%s", identifier.getTestClass(), identifier.getMethod());
        }
        return fullTestName.replaceFirst(Pattern.quote(separator) + "\\d+", "");
    }

    private static String getBaseTestClass(TestIdentifier identifier, String separator) {
        return identifier.getTestClass().replaceFirst(Pattern.quote(separator) + "\\d+", "");
    }

    private static String getBaseTestMethod(TestIdentifier identifier, String separator) {
        return identifier.getMethod().replaceFirst(Pattern.quote(separator) + "\\d+", "");
    }

    /**
     * Extracts numeric values from a {@link RawValues} proto into a list of doubles.
     *
     * <p>This method will log a warning and skip any non-numeric values (e.g., strings, booleans)
     * found in the list, aggregating only the numbers.
     *
     * @param rawValues The proto containing raw values.
     * @return A list of doubles.
     */
    private static List<Double> extractDoubles(RawValues rawValues) {
        List<Double> doubles = new ArrayList<>();
        for (Value v : rawValues.getValuesList()) {
            switch (v.getValueCase()) {
                case DOUBLE_VALUE:
                    doubles.add(v.getDoubleValue());
                    break;
                case INT_VALUE:
                    doubles.add((double) v.getIntValue());
                    break;
                case STRING_VALUE:
                    try {
                        for (String val : v.getStringValue().split(",")) {
                            doubles.add(Double.parseDouble(val.trim()));
                        }
                    } catch (NumberFormatException e) {
                        CLog.w(
                                "Skipping non-numeric value in dimensional metric aggregation: %s",
                                v);
                    }
                    break;
                default:
                    CLog.w("Skipping non-numeric value in dimensional metric aggregation: %s", v);
            }
        }
        return doubles;
    }
}
