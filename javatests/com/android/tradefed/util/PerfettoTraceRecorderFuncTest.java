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
package com.android.tradefed.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Unit tests for {@link PerfettoTraceRecorder}. */
@RunWith(JUnit4.class)
public class PerfettoTraceRecorderFuncTest {

    @Mock private ITestDevice mMockDevice;

    private PerfettoTraceRecorder mRecorder;
    private File mTempDir;

    private static final String FAKE_SERIAL = "fake-serial";
    private static final long FAKE_PID = 12345L;
    private static final String ON_DEVICE_CONFIG_PATH =
            "/data/misc/perfetto-traces/trace_config.textproto";

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mRecorder = new PerfettoTraceRecorder();

        when(mMockDevice.getSerialNumber()).thenReturn(FAKE_SERIAL);

        // Mock device shell command execution for starting trace
        CommandResult startResult = new CommandResult(CommandStatus.SUCCESS);
        startResult.setStdout(FAKE_PID + "\n");
        startResult.setExitCode(0);
        when(mMockDevice.executeShellV2Command(startsWith("nohup perfetto")))
                .thenReturn(startResult);

        // Mock device shell command execution for checking process
        CommandResult procExistsResult = new CommandResult(CommandStatus.SUCCESS);
        procExistsResult.setExitCode(0);
        when(mMockDevice.executeShellV2Command(startsWith("test -d /proc/")))
                .thenReturn(procExistsResult);

        when(mMockDevice.pushFile(any(File.class), anyString())).thenReturn(true);

        mTempDir = FileUtil.createTempDir("perfetto-test");
    }

    private void setOutputPath(PerfettoTraceRecorder recorder, String path) throws Exception {
        java.lang.reflect.Field field = PerfettoTraceRecorder.class.getDeclaredField("outputPath");
        field.setAccessible(true);
        field.set(recorder, path);
    }

    @Test
    public void testStartAndStopTrace() throws Exception {
        setOutputPath(mRecorder, mTempDir.getAbsolutePath());
        when(mMockDevice.pullFile(anyString(), any(File.class)))
                .thenAnswer(
                        invocation -> {
                            File hostFile = invocation.getArgument(1);
                            try (FileOutputStream fos = new FileOutputStream(hostFile)) {
                                fos.write("test trace data".getBytes());
                            }
                            return true;
                        });

        mRecorder.startTrace(mMockDevice, "trace_config.textproto", null);
        File traceFile = mRecorder.stopTrace(mMockDevice);

        verify(mMockDevice).pushFile(any(File.class), eq(ON_DEVICE_CONFIG_PATH));
        verify(mMockDevice).executeShellV2Command(startsWith("nohup perfetto"));
        verify(mMockDevice).executeShellCommand(eq("kill -INT " + FAKE_PID));
        verify(mMockDevice)
                .pullFile(startsWith("/data/misc/perfetto-traces/trace_"), any(File.class));

        // Verify on-device files are cleaned up using ArgumentCaptor
        ArgumentCaptor<String> rmCaptor = ArgumentCaptor.forClass(String.class);
        verify(mMockDevice, atLeastOnce()).executeShellCommand(rmCaptor.capture());

        List<String> rmArgs = rmCaptor.getAllValues();
        assertTrue(
                "Expected cleanup of trace file",
                rmArgs.stream()
                        .anyMatch(
                                arg ->
                                        arg.startsWith("rm -f /data/misc/perfetto-traces/trace_")
                                                && arg.endsWith(".pftrace")));
        assertTrue(
                "Expected cleanup of config file",
                rmArgs.contains("rm -f " + ON_DEVICE_CONFIG_PATH));

        assertNotNull(traceFile);
        assertTrue(traceFile.exists());
        assertTrue(traceFile.length() > 0);
        FileUtil.deleteFile(traceFile);
    }
}
