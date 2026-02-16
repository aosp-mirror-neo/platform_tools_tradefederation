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

import com.android.annotations.Nullable;
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
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.SearchArtifactUtil;
import com.android.tradefed.util.ZipUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A post processor which retrieves perfetto trace files from the test logs and runs the trace
 * processor on them to produce a perfetto metric proto.
 */
@OptionClass(alias = "perfetto-trace-processor")
public class PerfettoTracePostProcessor extends BasePostProcessor {

    private static final String PROCESSOR_STATUS_V1 = "trace_processor_status";
    private static final String PROCESSOR_STATUS_V2 = "trace_processor_status_v2";
    private static final String STATUS_SUCCESS = "1";
    private static final String STATUS_FAILURE = "0";
    private static final String PROCESSOR_RUNTIME_V1 = "trace_processor_runtime";
    private static final String PROCESSOR_RUNTIME_V2 = "trace_processor_runtime_v2";
    private static final String RAW_TRACE_FILE_SIZE = "perfetto_trace_file_size_bytes";
    private static final String METRIC_VERSION_V1 = "v1";
    private static final String METRIC_VERSION_V2 = "v2";

    public enum METRIC_FILE_FORMAT {
        text,
        binary,
        json,
    }

    @Option(
            name = "decompress-perfetto-timeout",
            description = "Timeout to decompress perfetto compressed file.",
            isTimeVal = true)
    private long mDecompressTimeoutMs = TimeUnit.MINUTES.toMillis(20);

    @Option(
            name = "perfetto-metric-prefix",
            description = "Prefix to be used with the metrics collected from perfetto.")
    @Deprecated
    private String mMetricPrefix = "perfetto";

    @Option(
            name = "convert-metric-file",
            description = "Convert the raw trace file to perfetto metric file.")
    private boolean mConvertToMetricFile = true;

    @Option(
            name = "collect-perfetto-file-size",
            description =
                    "Set it to true to collect the perfetto file size as part" + " of the metrics.")
    private boolean mCollectPerfettoFileSize = false;

    @Option(
            name = "trace-processor-binary",
            description =
                    "Path to the trace processor shell. This will"
                            + " override the trace-processor-name which is used to "
                            + " lookup in build artifacts. Used for converting the raw"
                            + " trace into perfetto metric file.")
    private File mTraceProcessorBinary = null;

    @Option(
            name = "trace-processor-name",
            description =
                    "Trace processor name to look up from the test artifacts"
                            + " or module artifacts.")
    private String mTraceProcessorName = "trace_processor_shell";

    @Option(
            name = "perfetto-v1-metrics",
            description = "This flag is to control the metric v1 computation.")
    private boolean mMetricV1 = true;

    @Option(
            name = "perfetto-v2-metrics",
            description = "This flag is to control the metric v2 computation.")
    private boolean mMetricV2 = false;

    @Option(
            name = "trace-processor-run-metrics",
            description =
                    "Comma separated list of metrics to extract from raw trace file."
                            + " For example android_mem.")
    private String mTraceProcessorMetrics = "android_mem";

    @Option(
            name = "trace-processor-output-format",
            description = "Trace processor output format. [binary|text|json]")
    private METRIC_FILE_FORMAT mTraceProcessorOutputFormat = METRIC_FILE_FORMAT.text;

    @Option(
            name = "trace-processor-timeout",
            description = "Timeout to convert the raw trace file to metric proto file.",
            isTimeVal = true)
    private long mTraceConversionTimeout = TimeUnit.MINUTES.toMillis(20);

    @Option(
            name = "perfetto-v2-metrics-ids",
            description =
                    "Specifies that the given v2 metrics (as defined by a comma separated set of"
                        + " ids) should be computed and returned as part of the trace summary. The"
                        + " spec for every metric must exist in one of the files passed to"
                        + " --summary-spec.")
    private String mComputeMetricsV2Ids = "memory_per_process-avg_rss_and_swap";

    @Option(name = "summary-spec-files", description = "Summary spec files abusolutle paths.")
    private List<File> mSummarySpecFiles = new ArrayList<File>();

    @Option(
            name = "add-sql-packages",
            description =
                    "Path to the directory of additional SQL packages to load when running the"
                        + " trace processor. A path can be either absolute or relative to the test"
                        + " artifacts. The Files from the directory will be treated as a new SQL"
                        + " package and can be used for INCLUDE PERFETTO MODULE statements.")
    private List<File> mAddSqlPackages = new ArrayList<File>();

    @Option(
            name = "summary-spec-file-name",
            description =
                    "Parses the spec at the specified path and makes it available to other"
                        + " summarization operators (--summary-metrics-v2). Spec files must be"
                        + " instances of the perfetto.protos.TraceSummarySpec proto. If the file"
                        + " extension is `.textproto` then the spec file will be parsed as a"
                        + " textproto. If the file extension is `.pb` then it will be parsed as a"
                        + " binary protobuf. Otherwise, heureustics will be used to determine the"
                        + " format.")
    private String mSummarySpecFileName = "memory_per_process-avg_rss_and_swap.textproto";

    @Option(
            name = "perfetto-trace-file-regex",
            description =
                    "Regex pattern specifying the trace file names to be processed."
                            + " By default uses the .perfetto-trace extension since this works for"
                            + " both raw and compressed traces.")
    private String mPerfettoTraceFileRegex = ".*\\.perfetto\\-trace.*";

    @Option(
            name = "include-failed-tests",
            description =
                    "Set it to true to process metrics for the failed tests. By default metrics for"
                            + " failed tests are not generated including v1 and v2.")
    private boolean mIncludeFailedTests = false;

    private Map<String, File> mTestArtifactFilePathMap = new HashMap<>();

    private Map<String, File> mTestArtifactDirectoryMap = new HashMap<>();

    @Override
    public Map<String, Metric.Builder> processTestMetricsAndLogs(
            TestDescription testDescription,
            HashMap<String, Metric> testMetrics,
            Map<String, LogFile> testLogs) {
        return processLogs(testLogs);
    }

    @Override
    public Map<String, Metric.Builder> processRunMetricsAndLogs(
            HashMap<String, Metric> rawMetrics, Map<String, LogFile> runLogs) {
        return processLogs(runLogs);
    }

    /**
     * Find any perfetto trace files in the logs and run the trace processor on them to produce a
     * perfetto metric proto.
     *
     * @param logs log files from the test or test run.
     * @return metrics produced while processing trace files in the logs.
     */
    private Map<String, Metric.Builder> processLogs(Map<String, LogFile> logs) {
        Map<String, Metric.Builder> metrics = new HashMap<>();
        if (!mIncludeFailedTests && isTestFailed()) {
            CLog.d("Skipping Perfetto trace processing due to test failure.");
            return metrics;
        }

        Pattern pattern = Pattern.compile(mPerfettoTraceFileRegex);
        for (String fileName : logs.keySet()) {
            if (pattern.matcher(fileName).matches()) {
                metrics.putAll(processTraceFile(logs.get(fileName)));
            }
        }
        return metrics;
    }

    /**
     * Takes a perfetto trace file and runs the trace processor on it to produce a perfetto metric
     * proto.
     *
     * @param logFile the perfetto trace file to process, either raw or compressed.
     * @return metrics produced while processing trace file.
     */
    private Map<String, Metric.Builder> processTraceFile(LogFile logFile) {
        Map<String, Metric.Builder> metrics = new HashMap<>();

        // The trace file from the test logs, either raw or compressed.
        File traceFile = new File(logFile.getPath());

        // Add a metric for the uncompressed trace file size.
        if (mCollectPerfettoFileSize) {
            metrics.putAll(getFileSizeMetric(traceFile, logFile.isCompressed()));
        }

        // Convert to perfetto metric format.
        if (mConvertToMetricFile) {
            if (mMetricV1) {
                metrics.putAll(
                        wrapConvertedMetricFile(
                                convertToMetricProto(traceFile, METRIC_VERSION_V1),
                                METRIC_VERSION_V1));
            }
            if (mMetricV2) {
                metrics.putAll(
                        wrapConvertedMetricFile(
                                convertToMetricProto(traceFile, METRIC_VERSION_V2),
                                METRIC_VERSION_V2));
            }
        }

        return metrics;
    }

    private static class TraceProcessorResult {
        public final File file;
        // runtime is set to -1 if the trace processor was never run.
        public final long runtime;
        public final String status;

        public TraceProcessorResult(File file, long runtime, String status) {
            this.file = file;
            this.runtime = runtime;
            this.status = status;
        }
    }

    /**
     * Converts the raw trace file into perfetto metric file.
     *
     * @param perfettoRawTraceFile Raw perfetto trace file that needs to be converted.
     * @param metricVersion The version of the metric fileto be generated.
     * @return The result of the conversion.
     */
    private TraceProcessorResult convertToMetricProto(
            File perfettoRawTraceFile, String metricVersion) {

        // Use absolute path to the trace file if it is available otherwise
        // resolve the trace processor name from the test or module artifacts.
        if (mTraceProcessorBinary == null || !mTraceProcessorBinary.exists()) {
            mTraceProcessorBinary = getFileFromTestArtifacts(mTraceProcessorName);
        }

        File metricOutputFile = null;
        String metricOutputFilePrefix = "";
        long runtime = -1L;

        if (mTraceProcessorBinary == null) {
            CLog.e("Failed to locate the trace processor shell binary file.");
            return new TraceProcessorResult(metricOutputFile, runtime, STATUS_FAILURE);
        }

        FileUtil.chmodGroupRWX(mTraceProcessorBinary);
        List<String> commandArgsList = new ArrayList<String>();
        commandArgsList.add(mTraceProcessorBinary.getAbsolutePath());

        for (File sqlPackage : mAddSqlPackages) {
            commandArgsList.add("--add-sql-package");
            // Resolve the SQL package directory from the absolute path or from the test artifacts.
            File resolvedSqlPackage = maybeResolveTestDir(sqlPackage);
            if (resolvedSqlPackage == null) {
                CLog.e(
                        "Failed to locate the SQL package directory for %s",
                        sqlPackage.getAbsolutePath());
                return new TraceProcessorResult(metricOutputFile, runtime, STATUS_FAILURE);
            }
            commandArgsList.add(resolvedSqlPackage.getAbsolutePath());
        }

        if (metricVersion.contains(METRIC_VERSION_V1)) {
            metricOutputFilePrefix = "metric_";

            // Comma separated list of metrics to extract.
            if (!mTraceProcessorMetrics.isEmpty()) {
                commandArgsList.add("--run-metrics");
                commandArgsList.add(mTraceProcessorMetrics);
            }
        }

        if (metricVersion.contains(METRIC_VERSION_V2)) {
            metricOutputFilePrefix = "metric_v2_";
            // Turn on the summary metrics v2 flag.
            commandArgsList.add("--summary");

            // Comma separated list of metrics ids to extract.
            if (!mComputeMetricsV2Ids.isEmpty()) {
                commandArgsList.add("--summary-metrics-v2");
                commandArgsList.add(mComputeMetricsV2Ids);
            }

            // resolve the summary spec names from the test or module artifacts.
            if (mSummarySpecFiles.isEmpty() && !mSummarySpecFileName.isEmpty()) {
                String[] FileNames = mSummarySpecFileName.split(",");
                for (String name : FileNames) {
                    mSummarySpecFiles.add(getFileFromTestArtifacts(name));
                }
            }

            if (mSummarySpecFiles.isEmpty()) {
                CLog.e("Failed to locate the summary spec file.");
                return new TraceProcessorResult(metricOutputFile, runtime, STATUS_FAILURE);
            }

            for (File summarySpecFile : mSummarySpecFiles) {
                commandArgsList.add("--summary-spec");
                commandArgsList.add(summarySpecFile.getAbsolutePath());
            }
        }

        // Metric file output format.
        commandArgsList.add("--metrics-output=" + mTraceProcessorOutputFormat);
        commandArgsList.add(perfettoRawTraceFile.getAbsolutePath());

        try {
            metricOutputFile =
                    FileUtil.createTempFile(
                            constructOutputFileName(
                                    metricOutputFilePrefix, perfettoRawTraceFile.getName()),
                            "");
        } catch (IOException e) {
            CLog.e("Not able to create metric perfetto output file.");
            CLog.e(e);
            return new TraceProcessorResult(null, runtime, STATUS_FAILURE);
        }

        // Running the trace conversion.
        CLog.i("Run the trace convertor.");
        boolean isConversionSuccess = true;
        try (FileOutputStream outStream = new FileOutputStream(metricOutputFile);
                ByteArrayOutputStream errStream = new ByteArrayOutputStream()) {
            long startTime = System.currentTimeMillis();
            CommandResult conversionResult =
                    runHostCommand(
                            mTraceConversionTimeout,
                            commandArgsList.toArray(new String[commandArgsList.size()]),
                            outStream,
                            errStream);
            runtime = System.currentTimeMillis() - startTime;
            if (!CommandStatus.SUCCESS.equals(conversionResult.getStatus())) {
                CLog.e(
                        "Unable to convert the raw trace - %s to metric file due to"
                                + " %s - Status - %s ",
                        perfettoRawTraceFile.getName(),
                        errStream.toString(),
                        conversionResult.getStatus());
                isConversionSuccess = false;
            } else if (mTraceProcessorOutputFormat.equals(METRIC_FILE_FORMAT.text)
                    || mTraceProcessorOutputFormat.equals(METRIC_FILE_FORMAT.json)) {
                File compressedFile = compressFile(metricOutputFile);
                metricOutputFile.delete();
                return new TraceProcessorResult(compressedFile, runtime, STATUS_SUCCESS);
            }
        } catch (FileNotFoundException e) {
            CLog.e("Not able to find the result metric file to write the " + "metric output.");
            CLog.e(e);
            isConversionSuccess = false;
        } catch (IOException e1) {
            CLog.e("Unable to close the streams.");
            CLog.e(e1);
            isConversionSuccess = false;
        } finally {
            if (!isConversionSuccess) {
                metricOutputFile.delete();
                return new TraceProcessorResult(null, runtime, STATUS_FAILURE);
            }
        }
        return new TraceProcessorResult(metricOutputFile, runtime, STATUS_SUCCESS);
    }

    /**
     * Wraps the converted metric file into test artifacts.
     *
     * @param result TraceProcessorResult
     * @param version The version of the metric file.
     * @return metrics for the processor runtime and status.
     */
    private Map<String, Metric.Builder> wrapConvertedMetricFile(
            TraceProcessorResult result, String version) {
        Map<String, Metric.Builder> metrics = new HashMap<>();

        File convertedMetricFile = result.file;
        if (convertedMetricFile != null) {
            try (InputStreamSource source = new FileInputStreamSource(convertedMetricFile, true)) {
                testLog(convertedMetricFile.getName(), getLogDataType(), source);
            }
        }

        Metric.Builder processorRuntimeBuilder = Metric.newBuilder();
        processorRuntimeBuilder.getMeasurementsBuilder().setSingleDouble((double) result.runtime);
        String runtimeKey =
                version.equals(METRIC_VERSION_V2) ? PROCESSOR_RUNTIME_V2 : PROCESSOR_RUNTIME_V1;
        metrics.put(
                String.format("%s_%s", mMetricPrefix, runtimeKey),
                processorRuntimeBuilder.setType(DataType.RAW));

        Metric.Builder processorStatusBuilder = Metric.newBuilder();
        processorStatusBuilder.getMeasurementsBuilder().setSingleString(result.status);
        String statusKey =
                version.equals(METRIC_VERSION_V2) ? PROCESSOR_STATUS_V2 : PROCESSOR_STATUS_V1;
        metrics.put(
                String.format("%s_%s", mMetricPrefix, statusKey),
                processorStatusBuilder.setType(DataType.RAW));

        return metrics;
    }

    /**
     * Return a metric for the uncompressed trace file size.
     *
     * @param traceFile the trace file, either raw or compressed.
     * @param isCompressed whether the trace file is compressed.
     * @return metrics for the uncompressed trace file size.
     */
    private Map<String, Metric.Builder> getFileSizeMetric(File traceFile, boolean isCompressed) {
        Map<String, Metric.Builder> metrics = new HashMap<>();
        long traceFileSizeBytes = -1;

        if (!isCompressed) {
            traceFileSizeBytes = traceFile.length();
        } else {
            CLog.i("Decompress trace file to get file size metric.");
            File decompressedFile = decompressFile(traceFile);
            if (decompressedFile == null) {
                CLog.e("Failed to decompress trace file to get file size metric.");
                return metrics;
            }
            traceFileSizeBytes = decompressedFile.length();
            decompressedFile.delete();
        }

        Metric.Builder metricBuilder = Metric.newBuilder();
        metricBuilder.getMeasurementsBuilder().setSingleDouble(traceFileSizeBytes);
        metrics.put(RAW_TRACE_FILE_SIZE, metricBuilder.setType(DataType.RAW));

        return metrics;
    }

    /**
     * Decompress the file to a temporary file in the host.
     *
     * @param compressedFile file to be decompressed.
     * @return decompressed file used for postprocessing.
     */
    @VisibleForTesting
    File decompressFile(File compressedFile) {
        File decompressedFile = null;
        try {
            decompressedFile = FileUtil.createTempFile("perfetto_decompressed", ".pb");
        } catch (IOException e) {
            CLog.e("Not able to create decompressed perfetto file.");
            CLog.e(e);
            return null;
        }
        // Keep the original file for uploading.
        List<String> decompressArgsList = new ArrayList<String>();
        decompressArgsList.add("gzip");
        decompressArgsList.add("-k");
        decompressArgsList.add("-c");
        decompressArgsList.add("-d");
        decompressArgsList.add(compressedFile.getAbsolutePath());

        // Decompress perfetto trace file.
        CLog.i("Start decompressing the perfetto trace file.");
        try (FileOutputStream outStream = new FileOutputStream(decompressedFile);
                ByteArrayOutputStream errStream = new ByteArrayOutputStream()) {
            CommandResult decompressResult =
                    runHostCommand(
                            mDecompressTimeoutMs,
                            decompressArgsList.toArray(new String[decompressArgsList.size()]),
                            outStream,
                            errStream);

            if (!CommandStatus.SUCCESS.equals(decompressResult.getStatus())) {
                CLog.e(
                        "Unable decompress the metric file %s due to %s - Status - %s ",
                        compressedFile.getName(),
                        errStream.toString(),
                        decompressResult.getStatus());
                decompressedFile.delete();
                return null;
            }
        } catch (FileNotFoundException e) {
            CLog.e(
                    "Not able to find the decompressed file to copy the"
                            + " decompressed contents.");
            CLog.e(e);
            return null;
        } catch (IOException e1) {
            CLog.e("Unable to close the streams.");
            CLog.e(e1);
        }
        CLog.i("Successfully decompressed the perfetto trace file.");
        return decompressedFile;
    }

    /**
     * Resolve the directory from the absolute path or from the test artifacts.
     *
     * @param directory directory to be resolved.
     * @return resolved directory. Returns null if directory is not found.
     */
    @Nullable
    File maybeResolveTestDir(File directory) {
        // If the path is absolute, check if the file exists.
        if (directory.isAbsolute()) {
            return directory.exists() ? directory : null;
        }
        return getDirectoryFromTestArtifacts(directory.getName());
    }

    @VisibleForTesting
    @Nullable
    File getDirectoryFromTestArtifacts(String directoryName) {
        if (mTestArtifactDirectoryMap.containsKey(directoryName)) {
            return mTestArtifactDirectoryMap.get(directoryName);
        }
        // A relative path is searched from the test artifact dir.
        File testArtifactDirectory =
                SearchArtifactUtil.searchFile(
                        directoryName, false, null, null, null, null, /* includeDirectory= */ true);
        if (testArtifactDirectory == null || !testArtifactDirectory.exists()) {
            CLog.e("Failed to retrieve directory %s from test artifacts", directoryName);
            return null;
        }

        CLog.i(
                "Using directory %s from %s",
                directoryName, testArtifactDirectory.getAbsolutePath());
        mTestArtifactDirectoryMap.put(directoryName, testArtifactDirectory);
        return testArtifactDirectory;
    }

    /**
     * Retrieve the file from the test artifacts or module artifacts and cache it in a map for the
     * subsequent calls.
     *
     * @param fileName name of the file to look up in the artifacts.
     * @return File from the test artifact or module artifact. Returns null if file is not found.
     */
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

    /**
     * Run a host command with the given array of command args.
     *
     * @param commandArgs args to be used to construct the host command.
     * @param stdout output of the command.
     * @param stderr error message if any from the command.
     * @return return the command results.
     */
    @VisibleForTesting
    CommandResult runHostCommand(
            long timeOut, String[] commandArgs, OutputStream stdout, OutputStream stderr) {
        if (stdout != null && stderr != null) {
            return RunUtil.getDefault().runTimedCmd(timeOut, stdout, stderr, commandArgs);
        }
        return RunUtil.getDefault().runTimedCmd(timeOut, commandArgs);
    }

    /**
     * Get the log data type based on the output metric perfetto file.
     *
     * @return LogDataType type of the file used for uploading the artifacts.
     */
    private LogDataType getLogDataType() {
        // text option in perfetto trace processor means text proto.
        if (mTraceProcessorOutputFormat.equals(METRIC_FILE_FORMAT.text)) {
            return LogDataType.ZIP;
        } else if (mTraceProcessorOutputFormat.equals(METRIC_FILE_FORMAT.binary)) {
            return LogDataType.PB;
        } else {
            return LogDataType.TEXT;
        }
    }

    /**
     * Construct the file name for the output metric proto file.
     *
     * @param prefix prefix to add to the file name.
     * @param rawTraceFileName file name of the raw trace file.
     * @return String file name to use for the metric proto file.
     */
    @VisibleForTesting
    String constructOutputFileName(String prefix, String rawTraceFileName) {
        String fileName = rawTraceFileName;

        // Remove the UID from the end of the raw trace file name. For example:
        // perfetto_<test_name>-1_13388308985625987330.pb -> perfetto_<test_name>-1_
        int lastIndex = fileName.lastIndexOf("_");
        if (lastIndex != -1) {
            fileName = fileName.substring(0, lastIndex + 1);
        }

        // If the file name does not already start with "perfetto", add that to make it easier for
        // PerfettoGenericPostProcessor to find.
        if (!fileName.startsWith("perfetto")) {
            fileName = "perfetto_" + fileName;
        }

        return prefix + fileName;
    }

    /**
     * Compress the given file.
     *
     * @return File compressed version of the file.
     */
    @VisibleForTesting
    File compressFile(File metricOutputFile) throws IOException {
        return ZipUtil.createZip(metricOutputFile, metricOutputFile.getName());
    }

    /**
     * Set the metric type to RAW so that metrics will be processed by subsequent post processors.
     *
     * <p>This is necessary for MetricFilePostProcessor to write the metrics to the results file.
     */
    @Override
    protected DataType getMetricType() {
        return DataType.RAW;
    }
}
