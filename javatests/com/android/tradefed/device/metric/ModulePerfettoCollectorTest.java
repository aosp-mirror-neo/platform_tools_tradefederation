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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.PerfettoTraceRecorder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

/** Unit tests for {@link PerfettoTraceRecorder}. */
@RunWith(JUnit4.class)
public class ModulePerfettoCollectorTest {

    private ModulePerfettoCollector mCollector;
    @Mock private ITestInvocationListener mMockListener;
    @Mock private ITestDevice mMockDevice;
    @Mock private PerfettoTraceRecorder mMockPerfettoTraceRecorder;
    @Mock private IInvocationContext mMockContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mCollector = new ModulePerfettoCollector();
        // Inject the mock recorder.
        mCollector.mPerfettoTraceRecorder = mMockPerfettoTraceRecorder;

        when(mMockDevice.getSerialNumber()).thenReturn("SERIAL");
        when(mMockContext.getDevices()).thenReturn(Collections.singletonList(mMockDevice));

        MultiMap<String, String> attributes = new MultiMap<>();
        attributes.put(ModuleDefinition.MODULE_NAME, "test_module");
        doReturn(attributes).when(mMockContext).getAttributes();

        mCollector.init(mMockContext, mMockListener);
    }

    @Test
    public void testCaptureModuleLevel() {
        assertTrue(mCollector.captureModuleLevel());
    }

    @Test
    public void testCollectOnModule() throws Exception {
        File fakeTrace = File.createTempFile("fake_trace", ".perfetto-trace");
        try {
            // Write some data to the file so it's not empty and is logged.
            try (FileOutputStream out = new FileOutputStream(fakeTrace)) {
                out.write("some trace data".getBytes());
            }

            when(mMockPerfettoTraceRecorder.stopTrace(mMockDevice)).thenReturn(fakeTrace);

            mCollector.onTestModuleStarted();
            verify(mMockPerfettoTraceRecorder).startTrace(eq(mMockDevice), anyString(), any());

            mCollector.onTestModuleEnded();
            verify(mMockPerfettoTraceRecorder).stopTrace(mMockDevice);

            ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
            verify(mMockListener).testLog(nameCaptor.capture(), eq(LogDataType.PERFETTO), any());

            assertEquals(
                    "perfetto-debug-trace_test_module_onTestModuleEnded_1", nameCaptor.getValue());
        } finally {
            fakeTrace.delete();
        }
    }

    @Test
    public void testCollect_noTraceFile() throws DeviceNotAvailableException {
        when(mMockPerfettoTraceRecorder.stopTrace(mMockDevice)).thenReturn(null);

        mCollector.onTestModuleStarted();
        mCollector.onTestModuleEnded();

        // Verify testLog is not called when trace file is null
        verify(mMockListener, times(0)).testLog(anyString(), any(), any());
    }

    @Test
    public void testRebootStarted() throws Exception {
        File fakeTrace = File.createTempFile("fake_trace", ".perfetto-trace");
        try {
            // Write some data to the file so it's not empty and is logged.
            try (FileOutputStream out = new FileOutputStream(fakeTrace)) {
                out.write("some trace data".getBytes());
            }
            when(mMockPerfettoTraceRecorder.stopTrace(mMockDevice)).thenReturn(fakeTrace);

            mCollector.onTestModuleStarted();
            verify(mMockPerfettoTraceRecorder).startTrace(eq(mMockDevice), anyString(), any());

            mCollector.rebootStarted(mMockDevice);
            verify(mMockPerfettoTraceRecorder).stopTrace(mMockDevice);

            ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
            verify(mMockListener).testLog(nameCaptor.capture(), eq(LogDataType.PERFETTO), any());

            assertEquals("perfetto-debug-trace_test_module_rebootStarted_1", nameCaptor.getValue());
        } finally {
            fakeTrace.delete();
        }
    }
}
