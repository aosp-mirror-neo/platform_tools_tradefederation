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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.device.metric.ScreenRecordOnFailureCollector.ScreenRecordRunnable;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@RunWith(JUnit4.class)
public class ScreenRecordOnFailureCollectorTest {
    private static final String TEST_CLASS = "com.test.MyTestClass";
    private static final String TEST_NAME = "myTestMethod";
    private static final String SERIAL = "12345ABCDE";
    private static final String MOCK_PID = "12345";

    // Represents the number of metrics (h264, stdout and stderr).
    private static final Integer METRIC_COUNT = 3;

    @Mock private ITestDevice mMockDevice;
    @Mock private ITestInvocationListener mMockListener;

    private IInvocationContext mContext;
    private ScreenRecordOnFailureCollector mCollector;
    private volatile boolean mStopRecording;
    private TestDescription mTestDescription;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        mContext = new InvocationContext();
        mContext.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, mMockDevice);
        mCollector = new ScreenRecordOnFailureCollector();
        mCollector.init(mContext, mMockListener);
        mTestDescription = new TestDescription(TEST_CLASS, TEST_NAME);
        mStopRecording = false;

        // Configure the mocked device.
        when(mMockDevice.getSerialNumber()).thenReturn(SERIAL);
        when(mMockDevice.getDeviceState()).thenReturn(TestDeviceState.ONLINE);
        when(mMockDevice.getRecoveryMode()).thenReturn(RecoveryMode.AVAILABLE);
        when(mMockDevice.waitForDeviceAvailable()).thenReturn(true);
        when(mMockDevice.isAdbRoot()).thenReturn(true);
        when(mMockDevice.doesFileExist(anyString())).thenReturn(true);
        when(mMockDevice.pullFile(anyString(), any(File.class))).thenReturn(true);

        // Mock for the blocking screenrecord process
        doAnswer(
                        invocation -> {
                            OutputStream outputStream = invocation.getArgument(1);
                            outputStream.write((MOCK_PID + "\n").getBytes(StandardCharsets.UTF_8));

                            // This loop simulates the 'wait' command, blocking the thread
                            // until the test explicitly "kills" it.
                            while (!mStopRecording) {
                                Thread.sleep(50);
                            }

                            CommandResult result = new CommandResult(CommandStatus.SUCCESS);
                            result.setExitCode(0);
                            return result;
                        })
                .when(mMockDevice)
                .executeShellV2Command(contains("screenrecord"), any(OutputStream.class));

        // Mock for the 'kill' command to UNBLOCK the screenrecord process
        CommandResult killResult = new CommandResult(CommandStatus.SUCCESS);
        killResult.setExitCode(0);
        when(mMockDevice.executeShellV2Command(contains("kill -s INT")))
                .thenAnswer(
                        invocation -> {
                            mStopRecording = true;
                            return killResult;
                        });
    }

    @After
    public void tearDown() {
        Optional<ScreenRecordRunnable> runnable = mCollector.getScreenRecordRunnable();
        if (runnable.isPresent()) {
            try {
                runnable.get().stopRecording();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void testCollectOnSuccess() throws Exception {
        mCollector.onTestRunStart(new DeviceMetricData(mContext));
        mCollector.onTestStart(new DeviceMetricData(mContext));
        waitForRecordingReady(mCollector.getScreenRecordRunnable());
        mCollector.onTestEnd(new DeviceMetricData(mContext), new HashMap<String, Metric>());

        // Verify the start and stop commands were executed.
        // The collector should have started a screenrecord process and killed it.
        verify(mMockDevice)
                .executeShellV2Command(contains("screenrecord"), any(OutputStream.class));
        verify(mMockDevice).executeShellV2Command(contains("kill -s INT"));
        verify(mMockListener, never())
                .testLog(anyString(), any(LogDataType.class), any(InputStreamSource.class));
    }

    @Test
    public void testCollectOnFailure() throws Exception {
        mCollector.onTestStart(new DeviceMetricData(mContext));
        waitForRecordingReady(mCollector.getScreenRecordRunnable());

        mCollector.onTestFail(new DeviceMetricData(mContext), mTestDescription);
        mCollector.onTestEnd(new DeviceMetricData(mContext), new HashMap<String, Metric>());

        // Verify start and stop commands were executed
        verify(mMockDevice)
                .executeShellV2Command(contains("screenrecord"), any(OutputStream.class));
        verify(mMockDevice).executeShellV2Command(contains("kill -s INT"));

        // Verify the recovery mode was temporarily changed
        verify(mMockDevice).setRecoveryMode(RecoveryMode.NONE);
        verify(mMockDevice).setRecoveryMode(RecoveryMode.AVAILABLE);

        // Verify that the screen recording files were logged to the test metrics.
        ArgumentCaptor<LogDataType> dataTypeCaptor = ArgumentCaptor.forClass(LogDataType.class);
        verify(mMockListener, times(METRIC_COUNT))
                .testLog(anyString(), dataTypeCaptor.capture(), any());
        List<LogDataType> loggedTypes = dataTypeCaptor.getAllValues();
        assertTrue(loggedTypes.contains(LogDataType.H264));
        assertTrue(loggedTypes.stream().filter(dt -> dt.equals(LogDataType.TEXT)).count() == 2);
    }

    @Test
    public void testSkipWhenDeviceOffline() throws Exception {
        when(mMockDevice.getDeviceState()).thenReturn(TestDeviceState.NOT_AVAILABLE);
        mCollector.onTestStart(new DeviceMetricData(mContext));
        mCollector.onTestFail(new DeviceMetricData(mContext), mTestDescription);
        mCollector.onTestEnd(new DeviceMetricData(mContext), new HashMap<String, Metric>());
        verify(mMockDevice, never()).executeShellV2Command(anyString());
        verify(mMockListener, never()).testLog(anyString(), any(), any());
    }

    @Test
    public void testThrottleOnMultipleFailures() throws Exception {
        final int throttleLimit = mCollector.THROTTLE_LIMIT_PER_RUN;
        mCollector.onTestRunStart(new DeviceMetricData(mContext));

        for (int i = 0; i < throttleLimit; i++) {
            // Reset the "kill" signal for each simulated test lifecycle.
            mStopRecording = false;
            mCollector.onTestStart(new DeviceMetricData(mContext));
            waitForRecordingReady(mCollector.getScreenRecordRunnable());
            mCollector.onTestFail(
                    new DeviceMetricData(mContext), new TestDescription("class", "test" + i));
            mCollector.onTestEnd(new DeviceMetricData(mContext), new HashMap<String, Metric>());
        }

        // Verify that exactly 5 sets of logs (15 logs total) were generated.
        verify(mMockListener, times(throttleLimit * METRIC_COUNT))
                .testLog(anyString(), any(), any());

        // Reset the signal for the test that should be throttled.
        mStopRecording = false;
        mCollector.onTestStart(new DeviceMetricData(mContext));
        waitForRecordingReady(mCollector.getScreenRecordRunnable());
        mCollector.onTestFail(
                new DeviceMetricData(mContext), new TestDescription("class", "testThrottled"));
        mCollector.onTestEnd(new DeviceMetricData(mContext), new HashMap<String, Metric>());

        // Verify that the number of logs is still throttleLimit * METRIC_COUNT, because the
        // last failure should have been throttled and produced no new logs.
        verify(mMockListener, times(throttleLimit * METRIC_COUNT))
                .testLog(anyString(), any(), any());
    }

    @Test
    public void testFileNomenclature() throws Exception {
        String testClass = "android.security.sts.CVE_2024_12345";
        String testMethod = "testPocCVE_2024_12345";
        TestDescription testDescription = new TestDescription(testClass, testMethod);

        ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);
        mCollector.onTestStart(new DeviceMetricData(mContext));
        waitForRecordingReady(mCollector.getScreenRecordRunnable());
        mCollector.onTestFail(new DeviceMetricData(mContext), testDescription);
        mCollector.onTestEnd(new DeviceMetricData(mContext), new HashMap<String, Metric>());

        verify(mMockListener, times(METRIC_COUNT))
                .testLog(
                        fileNameCaptor.capture(),
                        any(LogDataType.class),
                        any(InputStreamSource.class));

        List<String> capturedFileNames = fileNameCaptor.getAllValues();
        String baseName =
                String.format(
                        "%s#%s-%s-screen-record-on-failure_0_", testClass, testMethod, SERIAL);

        assertTrue(capturedFileNames.get(0).startsWith(baseName + "H264"));
        assertTrue(capturedFileNames.get(1).startsWith(baseName + "stdout"));
        assertTrue(capturedFileNames.get(2).startsWith(baseName + "stderr"));
    }

    @Test
    public void testConcurrentRecordingsThrowsException() throws Exception {
        mCollector.onTestStart(new DeviceMetricData(mContext));
        assertThrows(
                IllegalStateException.class,
                () -> {
                    mCollector.onTestStart(new DeviceMetricData(mContext));
                });
    }

    @Test
    public void testDeviceRestartBetweenTests() throws Exception {
        // Simulate the first test.
        mCollector.onTestRunStart(new DeviceMetricData(mContext));
        mCollector.onTestStart(new DeviceMetricData(mContext));
        waitForRecordingReady(mCollector.getScreenRecordRunnable());

        // Simulate some events like the device restarting, which would be handled by the collector.
        when(mMockDevice.getDeviceState()).thenReturn(TestDeviceState.NOT_AVAILABLE);
        when(mMockDevice.waitForDeviceAvailable()).thenReturn(true);
        when(mMockDevice.getDeviceState()).thenReturn(TestDeviceState.ONLINE);
        when(mMockDevice.getSerialNumber()).thenReturn("NEW_SERIAL");

        // This step is critical: End the first test run, which stops and cleans up the first
        // recording.
        mCollector.onTestEnd(new DeviceMetricData(mContext), new HashMap<String, Metric>());

        // Simulate the second test.
        TestDescription secondTest = new TestDescription("com.test.SecondTest", "testMethod");
        mCollector.onTestStart(new DeviceMetricData(mContext)); // This call will now succeed.
        waitForRecordingReady(mCollector.getScreenRecordRunnable());
        mCollector.onTestFail(new DeviceMetricData(mContext), secondTest);
        mCollector.onTestEnd(new DeviceMetricData(mContext), new HashMap<String, Metric>());

        // Verify the correct number of recordings and file logs.
        verify(mMockDevice, times(2)).executeShellV2Command(contains("kill -s INT"));
    }

    @Test
    public void testDeviceCleanup() throws Exception {
        // Simulate a test starting and failing, which will create files on the device.
        mCollector.onTestStart(new DeviceMetricData(mContext));
        waitForRecordingReady(mCollector.getScreenRecordRunnable());
        mCollector.onTestFail(new DeviceMetricData(mContext), mTestDescription);
        mCollector.onTestEnd(new DeviceMetricData(mContext), Collections.emptyMap());

        // Verify that the test attempted to delete each of the three temporary files.
        // The try-with-resources block ensures that the close() method on
        // ScreenRecordPathsResource is called, which triggers the deletion.
        verify(mMockDevice, times(METRIC_COUNT)).doesFileExist(anyString());
        verify(mMockDevice, times(METRIC_COUNT)).deleteFile(anyString());
    }

    @Test
    public void testRecordingsOnRootStatusChange() throws Exception {
        // Arrange the mock to simulate a crash on the first call and success on the second.
        CommandResult crashResult = new CommandResult(CommandStatus.FAILED);
        crashResult.setExitCode(1);
        CommandResult successResult = new CommandResult(CommandStatus.SUCCESS);
        successResult.setExitCode(0);

        // Simulates a root/unroot cycle where the device temporarily goes offline.
        // When the device becomes unavailable, the screen record command crashes,
        // causing executeShellV2Command to return CommandStatus.FAILED.
        // The test verifies that the collector handles this failure gracefully.
        when(mMockDevice.executeShellV2Command(contains("screenrecord"), any(OutputStream.class)))
                .thenReturn(crashResult)
                .thenAnswer(
                        invocation -> {
                            OutputStream outputStream = invocation.getArgument(1);
                            outputStream.write((MOCK_PID + "\n").getBytes(StandardCharsets.UTF_8));

                            // Block the thread to simulate a long-running command. This
                            // prevents the "TooManyActualInvocations" error.
                            while (!mStopRecording) {
                                Thread.sleep(50);
                            }
                            return successResult;
                        });

        // Start the test. The background thread will attempt to record, see the
        // non-zero exit code, loop, and try again.
        mCollector.onTestStart(new DeviceMetricData(mContext));

        // Wait for the second attempt to succeed.
        waitForRecordingReady(mCollector.getScreenRecordRunnable());

        // Fail the test to trigger artifact collection.
        mCollector.onTestFail(new DeviceMetricData(mContext), mTestDescription);
        mCollector.onTestEnd(new DeviceMetricData(mContext), Collections.emptyMap());

        // Verify the command was attempted twice and files from the second run were logged.
        verify(mMockDevice, times(2))
                .executeShellV2Command(contains("screenrecord"), any(OutputStream.class));
        verify(mMockListener, times(METRIC_COUNT * 2))
                .testLog(anyString(), any(LogDataType.class), any());
    }

    private void waitForRecordingReady(Optional<ScreenRecordRunnable> runnable)
            throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long timeout = 5000;

        while (runnable.isEmpty() || runnable.get().getPid().isEmpty()) {
            if (System.currentTimeMillis() - startTime > timeout) {
                throw new IllegalStateException(
                        "Timed out waiting for ScreenRecordRunnable to be ready.");
            }
            Thread.sleep(100);
        }
    }
}
