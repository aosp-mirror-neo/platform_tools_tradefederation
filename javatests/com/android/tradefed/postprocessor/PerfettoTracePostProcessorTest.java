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

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link PerfettoTracePostProcessor}. */
@RunWith(JUnit4.class)
public class PerfettoTracePostProcessorTest {

    private PerfettoTracePostProcessor mProcessor;
    @Mock private ITestInvocationListener mMockListener;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mProcessor = Mockito.spy(new PerfettoTracePostProcessor());
        mProcessor.init(mMockListener);
    }

    /** Create a map of test logs containing a perfetto trace file. */
    private Map<String, LogFile> createTestLogs() {
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                "perfetto_test.perfetto-trace",
                new LogFile("/path/to/perfetto_test.perfetto-trace", "", LogDataType.PERFETTO));
        return testLogs;
    }

    /** Create a map of test logs containing a compressed perfetto trace file. */
    private Map<String, LogFile> createTestLogsCompressed() {
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                "perfetto_test.perfetto-trace.gz",
                new LogFile("/path/to/perfetto_test.perfetto-trace.gz", "", LogDataType.GZIP));
        return testLogs;
    }

    /** Set up a command result to return the given status when runHostCommand() is called. */
    private void setUpHostCommandResult(CommandStatus status) {
        CommandResult cr = new CommandResult(status);
        Mockito.doReturn(cr)
                .when(mProcessor)
                .runHostCommand(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    /**
     * Create a temp file to be returned when getFileFromTestArtifacts() is called with the give
     * file name.
     */
    private File createTestArtifactsFile(String fileName) throws Exception {
        File tempFile = File.createTempFile(fileName, "");
        Mockito.doReturn(tempFile).when(mProcessor).getFileFromTestArtifacts(fileName);
        return tempFile;
    }

    /** Capture and return the args passed to runHostCommand(). */
    private List<String> captureHostCommandArgs() {
        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        Mockito.verify(mProcessor)
                .runHostCommand(Mockito.anyLong(), captor.capture(), Mockito.any(), Mockito.any());
        return Arrays.asList(captor.getValue());
    }

    @Test
    public void testNoProcessingFlow() throws Exception {
        // Run the post processor with empty test logs.
        Map<String, Metric.Builder> newMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), new HashMap<>());

        // Check no metrics were produced.
        Assert.assertTrue(newMetrics.size() == 0);
    }

    /**
     * Test the defaults args for trace processor shell.
     *
     * @throws Exception
     */
    @Test
    public void testTraceProcessorArgsDefaults() throws Exception {
        setUpHostCommandResult(CommandStatus.SUCCESS);

        File traceProcessorTempFile = createTestArtifactsFile("trace_processor_shell");

        // Run the post processor.
        mProcessor.processRunMetricsAndLogs(new HashMap<>(), createTestLogs());

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        Mockito.verify(mProcessor)
                .runHostCommand(Mockito.anyLong(), captor.capture(), Mockito.any(), Mockito.any());
        List<String> args = Arrays.asList(captor.getValue());
        Assert.assertTrue(args.contains(traceProcessorTempFile.getAbsolutePath()));
        Assert.assertTrue(args.contains("--run-metrics"));
        Assert.assertTrue(args.contains("--metrics-output=text"));
        Assert.assertFalse(args.contains("--summary"));
        Assert.assertFalse(args.contains("--summary-metrics-v2"));
        Assert.assertFalse(args.contains("--summary-spec"));

        traceProcessorTempFile.delete();
    }

    /**
     * Test that trace processor run metrics are used when running the trace processor shell
     * command.
     *
     * @throws Exception
     */
    @Test
    public void testTraceProcessorAdditionalArgs() throws Exception {
        // Set the trace processor binary and metrics to non-default values.
        File traceProcessorBinaryTempFile = File.createTempFile("trace_processor_binary", "");
        OptionSetter setter = new OptionSetter(mProcessor);
        setter.setOptionValue("trace-processor-name", "trace_processor_shell");
        setter.setOptionValue("trace-processor-run-metrics", "android_cpu,android_mem");
        setter.setOptionValue(
                "trace-processor-binary", traceProcessorBinaryTempFile.getAbsolutePath());

        setUpHostCommandResult(CommandStatus.SUCCESS);

        // Run the post processor.
        mProcessor.processRunMetricsAndLogs(new HashMap<>(), createTestLogs());

        // Verifies all the overridden values are taken into account and trace processor
        // binary is given preference over trace processor shell name.
        List<String> args = captureHostCommandArgs();
        Assert.assertTrue(args.contains(traceProcessorBinaryTempFile.getAbsolutePath()));
        Assert.assertTrue(args.contains("android_cpu,android_mem"));
        Assert.assertTrue(args.contains("--metrics-output=text"));

        traceProcessorBinaryTempFile.delete();
    }

    /**
     * Test that trace processor run metrics V2 only are used when running the trace processor shell
     * command.
     *
     * @throws Exception
     */
    @Test
    public void testTraceProcessorMetricV2Args() throws Exception {
        OptionSetter setter = new OptionSetter(mProcessor);
        setter.setOptionValue("perfetto-v1-metrics", "false");
        setter.setOptionValue("perfetto-v2-metrics", "true");

        setUpHostCommandResult(CommandStatus.SUCCESS);

        File traceProcessorTempFile = createTestArtifactsFile("trace_processor_shell");
        File summarySpecTempFile =
                createTestArtifactsFile("memory_per_process-avg_rss_and_swap.textproto");

        // Run the post processor.
        mProcessor.processRunMetricsAndLogs(new HashMap<>(), createTestLogs());

        List<String> args = captureHostCommandArgs();
        Assert.assertTrue(args.contains(traceProcessorTempFile.getAbsolutePath()));
        Assert.assertTrue(args.contains("--summary"));
        Assert.assertTrue(args.contains("--summary-metrics-v2"));
        Assert.assertTrue(args.contains("memory_per_process-avg_rss_and_swap"));
        Assert.assertTrue(args.contains("--summary-spec"));
        Assert.assertTrue(args.contains(traceProcessorTempFile.getAbsolutePath()));
        Assert.assertFalse(args.contains("--run-metrics"));

        traceProcessorTempFile.delete();
        summarySpecTempFile.delete();
    }

    /**
     * Test that trace processor run metrics V1 and V2 are used when running the trace processor
     * shell command.
     *
     * @throws Exception
     */
    @Test
    public void testTraceProcessorMetricV1andV2Args() throws Exception {
        OptionSetter setter = new OptionSetter(mProcessor);
        setter.setOptionValue("perfetto-v1-metrics", "true");
        setter.setOptionValue("perfetto-v2-metrics", "true");

        setUpHostCommandResult(CommandStatus.SUCCESS);

        File traceProcessorTempFile = createTestArtifactsFile("trace_processor_shell");
        File summarySpecTempFile =
                createTestArtifactsFile("memory_per_process-avg_rss_and_swap.textproto");

        // Run the post processor.
        mProcessor.processRunMetricsAndLogs(new HashMap<>(), createTestLogs());

        // Capture args from both times the trace processor is run.
        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        Mockito.verify(mProcessor, Mockito.times(2))
                .runHostCommand(Mockito.anyLong(), captor.capture(), Mockito.any(), Mockito.any());
        List<String[]> args = captor.getAllValues();

        // Verify metric v1 args.
        Assert.assertEquals(args.get(0)[0], traceProcessorTempFile.getAbsolutePath());
        Assert.assertEquals(args.get(0)[1], "--run-metrics");
        Assert.assertEquals(args.get(0)[2], "android_mem");
        Assert.assertEquals(args.get(0)[3], "--metrics-output=text");

        // Verify metric v2 args.
        Assert.assertEquals(args.get(1)[0], traceProcessorTempFile.getAbsolutePath());
        Assert.assertEquals(args.get(1)[1], "--summary");
        Assert.assertEquals(args.get(1)[2], "--summary-metrics-v2");
        Assert.assertEquals(args.get(1)[3], "memory_per_process-avg_rss_and_swap");
        Assert.assertEquals(args.get(1)[4], "--summary-spec");
        Assert.assertEquals(args.get(1)[5], summarySpecTempFile.getAbsolutePath());

        traceProcessorTempFile.delete();
        summarySpecTempFile.delete();
    }

    /** Test that the trace processor shell outputs run time and status. */
    @Test
    public void testTraceProcessorRunTimeAndStatus_success() throws Exception {
        // Trace processor will return success.
        setUpHostCommandResult(CommandStatus.SUCCESS);

        File traceProcessorTempFile = createTestArtifactsFile("trace_processor_shell");

        // Run the post processor.
        Map<String, Metric.Builder> newMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), createTestLogs());

        // Check runtime and status metrics are present.
        Assert.assertTrue(newMetrics.containsKey("perfetto_trace_processor_runtime"));
        Assert.assertTrue(
                newMetrics
                                .get("perfetto_trace_processor_runtime")
                                .getMeasurements()
                                .getSingleDouble()
                        >= 0);
        Assert.assertTrue(newMetrics.containsKey("perfetto_trace_processor_status"));
        Assert.assertEquals(
                "1",
                newMetrics
                        .get("perfetto_trace_processor_status")
                        .getMeasurements()
                        .getSingleString());

        traceProcessorTempFile.delete();
    }

    /** Test that the trace processor shell outputs run time and status even if processing fails. */
    @Test
    public void testTraceProcessorRunTimeAndStatus_failure() throws Exception {
        // Trace processor will return failure.
        setUpHostCommandResult(CommandStatus.FAILED);

        File traceProcessorTempFile = createTestArtifactsFile("trace_processor_shell");

        // Run the post processor.
        Map<String, Metric.Builder> newMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), createTestLogs());

        // Check runtime and status metrics are present.
        Assert.assertTrue(newMetrics.containsKey("perfetto_trace_processor_runtime"));
        Assert.assertTrue(
                newMetrics
                                .get("perfetto_trace_processor_runtime")
                                .getMeasurements()
                                .getSingleDouble()
                        >= 0);
        Assert.assertTrue(newMetrics.containsKey("perfetto_trace_processor_status"));
        Assert.assertEquals(
                "0",
                newMetrics
                        .get("perfetto_trace_processor_status")
                        .getMeasurements()
                        .getSingleString());

        traceProcessorTempFile.delete();
    }

    /**
     * Test that the metric collector reports a negative runtime and failure if the trace processor
     * is never invoked.
     */
    @Test
    public void testTraceProcessorRunTimeAndStatus_notInvoked() throws Exception {
        // No trace processor shell means that the trace proessor won't be invoked.
        Mockito.doReturn(null).when(mProcessor).getFileFromTestArtifacts("trace_processor_shell");

        // Run the post processor.
        Map<String, Metric.Builder> newMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), createTestLogs());

        // Check runtime and status metrics are present.
        Assert.assertTrue(newMetrics.containsKey("perfetto_trace_processor_runtime"));
        Assert.assertTrue(
                newMetrics
                                .get("perfetto_trace_processor_runtime")
                                .getMeasurements()
                                .getSingleDouble()
                        < 0);
        Assert.assertTrue(newMetrics.containsKey("perfetto_trace_processor_status"));
        Assert.assertEquals(
                "0",
                newMetrics
                        .get("perfetto_trace_processor_status")
                        .getMeasurements()
                        .getSingleString());
    }

    @Test
    public void testTraceProcessorFileSizeMetric() throws Exception {
        // Enable the option to collect the file size metric.
        OptionSetter setter = new OptionSetter(mProcessor);
        setter.setOptionValue("collect-perfetto-file-size", "true");

        setUpHostCommandResult(CommandStatus.SUCCESS);

        File traceProcessorTempFile = createTestArtifactsFile("trace_processor_shell");

        // Run the post processor.
        Map<String, Metric.Builder> newMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), createTestLogs());

        // Check the file size metric is correct.
        Assert.assertTrue(newMetrics.containsKey("perfetto_trace_file_size_bytes"));
        Assert.assertTrue(
                newMetrics.get("perfetto_trace_file_size_bytes").getMeasurements().getSingleDouble()
                        >= 0);

        traceProcessorTempFile.delete();
    }

    @Test
    public void testTraceProcessorCompressedTrace() throws Exception {
        setUpHostCommandResult(CommandStatus.SUCCESS);

        File traceProcessorTempFile = createTestArtifactsFile("trace_processor_shell");

        // Run the post processor with logs containing a compressed trace file.
        mProcessor.processRunMetricsAndLogs(new HashMap<>(), createTestLogsCompressed());

        // Check the trace file was decompressed before processing.
        Mockito.verify(mProcessor).decompressFile(Mockito.any());

        traceProcessorTempFile.delete();
    }

    /** Test the default metric type is set to RAW. */
    @Test
    public void testMetricTypeIsRaw() {
        Assert.assertTrue(mProcessor.getMetricType().equals(DataType.RAW));
    }
}
