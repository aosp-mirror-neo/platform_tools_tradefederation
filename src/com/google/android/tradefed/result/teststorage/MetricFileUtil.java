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
package com.google.android.tradefed.result.teststorage;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.common.base.Joiner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility to store the test run and test metrics at the end of invocation in a text file and upload
 * it to Android Build API.
 */
public class MetricFileUtil {

    private static final String TEST_HEADER_SEPARATOR = "\n\n";
    private static final String METRIC_SEPARATOR = "\n";
    private static final String METRIC_KEY_VALUE_SEPARATOR = ":";
    private static final String SEPARATOR = "#";

    private ILogSaver mLogSaver;
    private IConfiguration mConfiguration;

    // To keep track of test run name and test run details.
    private Map<String, List<TestRunMetrics>> mTestRunDetailsMap = new LinkedHashMap<>();
    // To keep track test run and multiple test metrics in a test run.
    private Map<String, Map<String, String>> mTestMetricsMap;

    private String mCurrentRunName;

    public void setLogSaver(ILogSaver logSaver) {
        mLogSaver = logSaver;
    }

    @VisibleForTesting
    ILogSaver getLogSaver() {
        return mLogSaver;
    }

    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    public void testRunStarted(String runName) {
        mCurrentRunName = runName;
        mTestMetricsMap = new LinkedHashMap<>();
    }

    public void testEnded(
            TestDescription test, HashMap<String, MetricMeasurement.Metric> testMetrics) {
        // Add the test metrics only if it's not empty.
        if (null != testMetrics && !testMetrics.isEmpty()) {
            Joiner joiner = Joiner.on(SEPARATOR).skipNulls();
            String testName = joiner.join(test.getClassName(), test.getTestName());
            mTestMetricsMap.put(testName, TfMetricProtoUtil.compatibleConvert(testMetrics));
        }
    }

    public void testRunEnded(HashMap<String, MetricMeasurement.Metric> runMetrics) {
        if (null != mCurrentRunName) {
            // Do not update the run details map if both run and test metrics are empty.
            if ((null == runMetrics || runMetrics.isEmpty()) && mTestMetricsMap.isEmpty()) {
                return;
            }

            if (mTestRunDetailsMap.containsKey(mCurrentRunName)) {
                mTestRunDetailsMap
                        .get(mCurrentRunName)
                        .add(
                                new TestRunMetrics(
                                        runMetrics != null
                                                ? TfMetricProtoUtil.compatibleConvert(runMetrics)
                                                : null,
                                        mTestMetricsMap));
            } else {
                List<TestRunMetrics> testRunMetricsList = new LinkedList<>();
                testRunMetricsList.add(
                        new TestRunMetrics(
                                runMetrics != null
                                        ? TfMetricProtoUtil.compatibleConvert(runMetrics)
                                        : null,
                                mTestMetricsMap));
                mTestRunDetailsMap.put(mCurrentRunName, testRunMetricsList);
            }
        }
    }

    public void invocationEnded(Set<String> metricFilterRegEx) {
        try {
            if (!mTestRunDetailsMap.isEmpty()) {
                List<Pattern> metricFilterPatterns = buildMetricFilterPatterns(metricFilterRegEx);
                // Write metrics matching the filter patterns. If metric filter pattern is
                // empty write all the metric.
                upload(writeResultsToFile(metricFilterPatterns));
            }
        } catch (IOException e) {
            CLog.e("Not able to create test results file due to : %s", e.getMessage());
        }
    }

    public boolean isMetricAvailable() {
        return !mTestRunDetailsMap.isEmpty();
    }

    /**
     * Write the test run metrics and test metrics into a file.
     *
     * @param metricPatterns used to filter the metrics written into the test results file.
     * @return File containing the metrics.
     * @throws IOException
     */
    private File writeResultsToFile(List<Pattern> metricPatterns) throws IOException {
        File resultsFile = FileUtil.createTempFile("test_results", "");

        // loops over all test runs
        try (FileOutputStream outputStream = new FileOutputStream(resultsFile)) {
            for (Map.Entry<String, List<TestRunMetrics>> testRunEntry :
                    mTestRunDetailsMap.entrySet()) {
                for (TestRunMetrics runMmetric : testRunEntry.getValue()) {
                    // Parse run metrics
                    if (runMmetric.getRunMetrics() != null
                            && runMmetric.getRunMetrics().size() > 0) {
                        // Write the test run name.
                        outputStream.write(
                                String.format("%s%s", testRunEntry.getKey(), TEST_HEADER_SEPARATOR)
                                        .getBytes());
                        // Write the test run metrics.
                        for (Entry<String, String> entry : runMmetric.getRunMetrics().entrySet()) {

                            // If filter patterns are available and if it does not matches any of
                            // the pattern do not write the metric to a file. Otherwise write the
                            // metric to a file.
                            if (!metricPatterns.isEmpty()
                                    && !isMetricKeyMatchesFilter(metricPatterns, entry.getKey())) {
                                continue;
                            }
                            String test_metric =
                                    String.format(
                                            "%s%s%s",
                                            entry.getKey(),
                                            METRIC_KEY_VALUE_SEPARATOR,
                                            entry.getValue());
                            outputStream.write(
                                    String.format("%s%s", test_metric, METRIC_SEPARATOR)
                                            .getBytes());
                        }
                        outputStream.write(TEST_HEADER_SEPARATOR.getBytes());
                    }

                    // Parse test metrics
                    Map<String, Map<String, String>> testResultMap = runMmetric.getTestMetricsMap();
                    for (Entry<String, Map<String, String>> entry : testResultMap.entrySet()) {
                        // Write the test description name.
                        outputStream.write(
                                String.format("%s%s", entry.getKey(), TEST_HEADER_SEPARATOR)
                                        .getBytes());
                        // Write the test metrics.
                        if (entry.getValue().size() > 0) {
                            for (Entry<String, String> metric : entry.getValue().entrySet()) {
                                // If filter patterns are available and if it does not matches any
                                // of the pattern do not write the metric to a file. Otherwise
                                // write the metric to a file.
                                if (!metricPatterns.isEmpty()
                                        && !isMetricKeyMatchesFilter(
                                                metricPatterns, metric.getKey())) {
                                    continue;
                                }
                                String test_metric =
                                        String.format(
                                                "%s%s%s",
                                                metric.getKey(),
                                                METRIC_KEY_VALUE_SEPARATOR,
                                                metric.getValue());
                                outputStream.write(
                                        String.format("%s%s", test_metric, METRIC_SEPARATOR)
                                                .getBytes());
                            }
                        }
                        outputStream.write(TEST_HEADER_SEPARATOR.getBytes());
                    }
                }
            }
            outputStream.flush();
        }
        return resultsFile;
    }

    /**
     * Check if the given metric matches the metric filters.
     *
     * @param metricPatterns
     * @param metricKey
     * @return true if the metric key matches the pattern otherwise return false.
     */
    private boolean isMetricKeyMatchesFilter(List<Pattern> metricPatterns, String metricKey) {
        for (Pattern pattern : metricPatterns) {
            if (pattern.matcher(metricKey).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Upload the test result file in the Android Build API.
     *
     * @param resultsFile which contains the run and test metrics.
     * @throws IOException
     */
    private void upload(File resultsFile) throws IOException {

        if (resultsFile == null) {
            CLog.e("Skip uploading the test metric file.");
            return;
        }

        // This is temporary until all the use cases can read the metrics
        // from ANTS.
        CLog.i(FileUtil.readStringFromFile(resultsFile));

        try (InputStreamSource inputStreamSrc = new FileInputStreamSource(resultsFile)) {
            LogFile log =
                    mLogSaver.saveLogData(
                            resultsFile.getName(),
                            LogDataType.CB_METRICS_FILE,
                            inputStreamSrc.createInputStream());
            if (null != log) {
                CLog.i("Uploading file: %s.", resultsFile.getName());
                for (ITestInvocationListener listener :
                        mConfiguration.getTestInvocationListeners()) {
                    if (listener instanceof ILogSaverListener) {
                        try {
                            ((ILogSaverListener) listener)
                                    .testLogSaved(
                                            resultsFile.getName(),
                                            LogDataType.CB_METRICS_FILE,
                                            inputStreamSrc,
                                            log);
                            ((ILogSaverListener) listener)
                                    .logAssociation(resultsFile.getName(), log);
                        } catch (Exception e) {
                            CLog.e("Error while logging metric file on %s", listener);
                            CLog.e(e);
                        }
                    }
                }
            } else {
                CLog.i("Results or log file is null");
            }
        } catch (IOException e) {
            CLog.e("Not able to save log file due to : %s", e.getMessage());
        } finally {
            FileUtil.deleteFile(resultsFile);
        }
    }

    public class TestRunMetrics {

        private Map<String, String> mRunMetrics;
        private Map<String, Map<String, String>> mTestMetricsMap;

        TestRunMetrics(
                Map<String, String> runMetrics, Map<String, Map<String, String>> testMetricsMap) {
            mRunMetrics = runMetrics;
            mTestMetricsMap = testMetricsMap;
        }

        /**
         * @return the mRunMetrics
         */
        public Map<String, String> getRunMetrics() {
            return mRunMetrics;
        }

        /**
         * @param runMetrics the mRunMetrics to set
         */
        public void setRunMetrics(Map<String, String> runMetrics) {
            this.mRunMetrics = runMetrics;
        }

        /**
         * @return the mTestMetricsMap
         */
        public Map<String, Map<String, String>> getTestMetricsMap() {
            return mTestMetricsMap;
        }

        /**
         * @param testMetricsMap the mTestMetricsMap to set
         */
        public void setTestMetricsMap(Map<String, Map<String, String>> testMetricsMap) {
            this.mTestMetricsMap = testMetricsMap;
        }
    }

    /**
     * Build regular expression patterns to filter the metrics written into the test results file.
     */
    private List<Pattern> buildMetricFilterPatterns(Set<String> metricFilterRegex) {
        List<Pattern> mMetricPatterns = new ArrayList<>();
        if (!metricFilterRegex.isEmpty() && mMetricPatterns.isEmpty()) {
            for (String regEx : metricFilterRegex) {
                try {
                    mMetricPatterns.add(Pattern.compile(regEx));
                } catch (IllegalArgumentException e) {
                    CLog.d("Invalid regex pattern: %s", e.getMessage());
                }
            }
        }
        return mMetricPatterns;
    }
}
