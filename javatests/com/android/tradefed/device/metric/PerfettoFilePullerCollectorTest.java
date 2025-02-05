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

package com.android.tradefed.device.metric;

import static org.mockito.Mockito.doReturn;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.HashMap;

/** Unit tests for {@link PerfettoFilePullerCollector}. */
@RunWith(JUnit4.class)
public class PerfettoFilePullerCollectorTest {

    private PerfettoFilePullerCollector mCollector;
    @Mock private ITestInvocationListener mMockListener;
    @Mock private ITestDevice mMockDevice;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        IInvocationContext context = new InvocationContext();
        doReturn(TestDeviceState.ONLINE).when(mMockDevice).getDeviceState();
        context.addAllocatedDevice("default", mMockDevice);

        mCollector = Mockito.spy(new PerfettoFilePullerCollector());
        mCollector.init(context, mMockListener);
    }

    private void triggerCollector(HashMap<String, Metric> currentMetrics) {
        TestDescription description = new TestDescription("xyz", "abc");

        mCollector.testRunStarted("runName", 1);
        mCollector.testStarted(description);
        mCollector.testEnded(description, currentMetrics);
        mCollector.testRunEnded(100L, new HashMap<String, Metric>());
    }

    @Test
    public void testPullDefault() throws Exception {
        String traceFileName = "trace.perfetto-trace";
        String traceDevicePath = "/data/" + traceFileName;

        OptionSetter setter = new OptionSetter(mCollector);
        setter.setOptionValue("pull-pattern-keys", "perfetto_file_path");

        HashMap<String, Metric> currentMetrics = new HashMap<>();
        currentMetrics.put("perfetto_file_path", TfMetricProtoUtil.stringToMetric(traceDevicePath));

        Mockito.when(mMockDevice.pullFile(Mockito.eq(traceDevicePath), Mockito.eq(0)))
                .thenReturn(new File(traceFileName));

        triggerCollector(currentMetrics);

        // Verify that pullFile() was called and the trace file was added to the test logs.
        Mockito.verify(mMockDevice).pullFile(Mockito.eq(traceDevicePath), Mockito.eq(0));
        Mockito.verify(mMockListener)
                .testLog(
                        Mockito.eq(traceFileName), Mockito.eq(LogDataType.PERFETTO), Mockito.any());
    }

    @Test
    public void testPullNoFiles() throws Exception {
        String traceFileName = "trace.perfetto-trace";
        String traceDevicePath = "/data/" + traceFileName;

        OptionSetter setter = new OptionSetter(mCollector);
        setter.setOptionValue("pull-pattern-keys", "perfetto_file_path");

        // No perfetto file path is specified in the metrics.
        HashMap<String, Metric> currentMetrics = new HashMap<>();

        Mockito.when(mMockDevice.pullFile(Mockito.eq(traceDevicePath), Mockito.eq(0)))
                .thenReturn(new File(traceFileName));

        triggerCollector(currentMetrics);

        // Verify that pullFile() and testLog() were not called.
        Mockito.verify(mMockDevice, Mockito.times(0)).pullFile(Mockito.any());
        Mockito.verify(mMockListener, Mockito.times(0))
                .testLog(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void testPullCompressed() throws Exception {
        String traceFileName = "trace.perfetto-trace";
        String traceDevicePath = "/data/" + traceFileName;

        OptionSetter setter = new OptionSetter(mCollector);
        setter.setOptionValue("pull-pattern-keys", "perfetto_file_path");
        setter.setOptionValue("compress-perfetto", "true");

        HashMap<String, Metric> currentMetrics = new HashMap<>();
        currentMetrics.put("perfetto_file_path", TfMetricProtoUtil.stringToMetric(traceDevicePath));

        Mockito.when(mMockDevice.pullFile(Mockito.eq(traceDevicePath), Mockito.eq(0)))
                .thenReturn(new File(traceFileName));

        triggerCollector(currentMetrics);

        // Verify that pullFile() was not called.
        Mockito.verify(mMockDevice, Mockito.times(0)).pullFile(Mockito.eq(traceDevicePath));

        // Verify that a compressed trace file was added to the test logs.
        Mockito.verify(mMockListener)
                .testLog(
                        Mockito.matches(String.format("%s.*.gz", traceFileName)),
                        Mockito.eq(LogDataType.GZIP),
                        Mockito.any());
    }
}
