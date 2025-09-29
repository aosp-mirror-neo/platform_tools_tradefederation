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

package com.android.tradefed.postprocessor;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.SearchArtifactUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** A post processor that finds simpleperf profiling data and generates a pprof profile. */
@OptionClass(alias = "simpleperf-profile-post-processor")
public class SimpleperfProfilePostProcessor extends BasePostProcessor {

    private static final String SIMPLEPERF_PPROF_PREFIX = "pprof_";

    @Option(
            name = "simpleperf-profiling-prefix",
            description = "Prefix for the simpleperf profiling data file.")
    private String mSimpleperfProfilingPrefix = ".*simpleperf_profiling.*";

    @Option(name = "post-processor-name", description = "The name of the post processor binary.")
    private String mPostProcessorName = "pprof_proto_generator";

    @Option(
            name = "post-processor-timeout",
            description = "Timeout for the post processor to run.",
            isTimeVal = true)
    private long mPostProcessorTimeout = TimeUnit.MINUTES.toMillis(5);

    private File mPostProcessorBinary;
    private Map<String, File> mTestArtifactFilePathMap = new HashMap<>();

    @Override
    public Map<String, Metric.Builder> processTestMetricsAndLogs(
            TestDescription testDescription,
            HashMap<String, Metric> testMetrics,
            Map<String, LogFile> testLogs) {
        CLog.i("SimpleperfProfilePostProcessor: processTestMetricsAndLogs");
        return processLogs(testLogs, testDescription);
    }

    @Override
    public Map<String, Metric.Builder> processRunMetricsAndLogs(
            HashMap<String, Metric> rawMetrics, Map<String, LogFile> runLogs) {
        CLog.i("SimpleperfProfilePostProcessor: processRunMetricsAndLogs");
        return processLogs(runLogs, null);
    }

    /**
     * Finds and processes simpleperf profiling files from the logs.
     *
     * @param logs Log files from the test or test run.
     * @return An empty map, as this processor only generates log files.
     */
    private Map<String, Metric.Builder> processLogs(
            Map<String, LogFile> logs, TestDescription testDescription) {
        CLog.i("SimpleperfProfilePostProcessor: processLogs");
        Pattern pattern = Pattern.compile(mSimpleperfProfilingPrefix);
        for (String fileName : logs.keySet()) {
            if (pattern.matcher(fileName).matches()) {
                CLog.i("Processing profiling file: %s", fileName);
                processProfilingFile(logs.get(fileName), testDescription);
            } else {
                CLog.i("Cannot find simpleperf profiling data file.");
            }
        }
        return new HashMap<>();
    }

    /**
     * Runs the pprof proto generator on the given simpleperf profiling data file.
     *
     * @param logFile The simpleperf profiling data file.
     */
    private void processProfilingFile(LogFile logFile, TestDescription testDescription) {
        CLog.i("SimpleperfProfilePostProcessor: processProfilingFile");
        File simpleperfDataFile = null;
        File pprofOutputFile = null;

        if (mPostProcessorBinary == null || !mPostProcessorBinary.exists()) {
            mPostProcessorBinary = getFileFromTestArtifacts(mPostProcessorName);
        }

        if (mPostProcessorBinary == null) {
            CLog.e("Failed to locate the post processor binary: %s", mPostProcessorName);
            return;
        }
        FileUtil.chmodGroupRWX(mPostProcessorBinary);

        try {
            // Create a local copy of the simpleperf data to ensure file integrity and naming.
            try (InputStreamSource source = new FileInputStreamSource(new File(logFile.getPath()));
                    InputStream stream = source.createInputStream()) {
                if (stream == null) {
                    CLog.e("Failed to get stream for %s", logFile.getPath());
                    return;
                }
                simpleperfDataFile = createTempFile("simpleperf_data_copy", ".data");
                FileUtil.writeToFile(stream, simpleperfDataFile);
            } catch (IOException e) {
                CLog.e("Failed to read simpleperf data file.", e);
                return;
            }

            try {
                String fileName;
                if (testDescription != null) {
                    fileName =
                            String.format(
                                    "%s%s_%s",
                                    SIMPLEPERF_PPROF_PREFIX,
                                    testDescription.getClassName(),
                                    testDescription.getTestName());
                } else {
                    fileName = String.format("%srun", SIMPLEPERF_PPROF_PREFIX);
                }
                pprofOutputFile = createTempFile(fileName, "");
            } catch (IOException e) {
                CLog.e("Failed to create temporary file for pprof output.", e);
                return;
            }

            List<String> command = new ArrayList<>();
            command.add(mPostProcessorBinary.getAbsolutePath());
            command.add("-i");
            command.add(simpleperfDataFile.getAbsolutePath());
            command.add("-o");
            command.add(pprofOutputFile.getAbsolutePath());

            CLog.i("Running pprof proto generator on: %s", simpleperfDataFile.getName());
            CommandResult result =
                    getRunUtil()
                            .runTimedCmd(
                                    mPostProcessorTimeout,
                                    command.toArray(new String[command.size()]));

            if (result != null && CommandStatus.SUCCESS.equals(result.getStatus())) {
                CLog.i("Successfully generated pprof profile: %s", pprofOutputFile.getName());
                try (InputStreamSource source = new FileInputStreamSource(pprofOutputFile, true)) {
                    testLog(pprofOutputFile.getName(), LogDataType.PB, source);
                }
            } else {
                CLog.e(
                        "Failed to generate pprof profile for %s. Stderr: %s",
                        simpleperfDataFile.getName(), result == null ? "null" : result.getStderr());
                deleteFile(pprofOutputFile);
            }
        } finally {
            deleteFile(simpleperfDataFile);
        }
    }

    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    @VisibleForTesting
    File createTempFile(String prefix, String suffix) throws IOException {
        return FileUtil.createTempFile(prefix, suffix);
    }

    @VisibleForTesting
    void deleteFile(File file) {
        FileUtil.deleteFile(file);
    }

    @VisibleForTesting
    File getFileFromTestArtifacts(String fileName) {
        if (mTestArtifactFilePathMap.containsKey(fileName)) {
            return mTestArtifactFilePathMap.get(fileName);
        }

        File resolvedFile = SearchArtifactUtil.searchFile(fileName, true);
        if (resolvedFile == null || !resolvedFile.exists()) {
            CLog.e("Failed to retrieve file %s from test artifacts", fileName);
            return null;
        }

        CLog.i("Using file %s from %s", fileName, resolvedFile.getAbsolutePath());
        mTestArtifactFilePathMap.put(fileName, resolvedFile);
        return resolvedFile;
    }

    @Override
    protected DataType getMetricType() {
        return DataType.RAW;
    }
}
