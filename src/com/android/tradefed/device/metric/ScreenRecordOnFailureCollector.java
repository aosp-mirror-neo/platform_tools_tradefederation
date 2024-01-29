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

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.CommandResult;

import com.google.common.annotations.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Collector that will capture and log a screen recording when a test case fails. While the test is
 * running, screenrecord until the test ends. In the case the that screen recording is interrupted,
 * such as the device rebooting or rooting/unrooting, retry when the device is available until the
 * test ends. As a result, multiple recording files can be generated for a single test.
 */
public class ScreenRecordOnFailureCollector extends BaseDeviceMetricCollector {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    @VisibleForTesting static final int THROTTLE_LIMIT_PER_RUN = 5;
    private int mFailureCount = 0;
    private boolean mFirstThrottle = true;
    private Optional<ScreenRecordRunnable> mScreenRecordRunnable = Optional.empty();
    private Thread mScreenRecordThread;
    private static final String SCREEN_RECORD_LOG_NAME_BASE_FORMAT =
            "%s#%s-%s-screen-record-on-failure_%d_%s";

    // We use the raw H.264 video stream format instead of an MP4 container.
    // This prevents file corruption when the screenrecord process is killed
    // abruptly (e.g., by `adb root`), which can prevent the MP4's MOOV
    // atom (essential metadata) from being written at the end of the stream.
    // The trade-off is that raw H.264 lacks frame timestamps, which can
    // result in jerky or uneven playback, but is usually sufficient for debugging.
    private static class PathGroup {
        String h264;
        String stdout;
        String stderr;
        int numRecordings;

        public PathGroup(String randomId, int numRecordings) {
            String filename = String.format("tradefed-screenrecord-%s-%d", randomId, numRecordings);
            String path = String.format("/data/local/tmp/%s", filename);
            this.h264 = path + ".h264";
            this.stdout = path + "-stdout.txt";
            this.stderr = path + "-stderr.txt";
            this.numRecordings = numRecordings;
        }

        public String[] getAllPaths() {
            return new String[] {h264, stdout, stderr};
        }
    }

    private static class ScreenRecordPathsResource implements AutoCloseable {
        private final List<PathGroup> mPaths;
        private final ITestDevice mDevice;

        public ScreenRecordPathsResource(ITestDevice device, List<PathGroup> paths) {
            this.mDevice = device;
            this.mPaths = paths;
        }

        public List<PathGroup> getPathGroups() {
            return mPaths;
        }

        @Override
        public void close() throws Exception {
            for (PathGroup pathGroup : mPaths) {
                for (String devicePath : pathGroup.getAllPaths()) {
                    try {
                        if (mDevice.doesFileExist(devicePath)) {
                            mDevice.deleteFile(devicePath);
                        }
                    } catch (DeviceNotAvailableException e) {
                        CLog.w(e);
                    }
                }
            }
        }
    }

    public class LineCapturingOutputStream extends OutputStream {
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        private final Consumer<String> onLineCaptured;

        public LineCapturingOutputStream(Consumer<String> onLineCaptured) {
            this.onLineCaptured = onLineCaptured;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                String line = lineBuffer.toString(StandardCharsets.UTF_8);
                lineBuffer.reset();
                this.onLineCaptured.accept(line);
            } else {
                lineBuffer.write(b);
            }
        }
    }

    @VisibleForTesting
    Optional<ScreenRecordRunnable> getScreenRecordRunnable() {
        return mScreenRecordRunnable;
    }

    @VisibleForTesting
    class ScreenRecordRunnable implements Runnable {
        private boolean mTestFinished = false;
        private ITestDevice mDevice = null;
        private String mRandomId = null;
        private List<PathGroup> mPaths = new ArrayList();
        private CountDownLatch mPidCapturedLatch = new CountDownLatch(1);
        private final CountDownLatch mCompletionLatch = new CountDownLatch(1);

        private Optional<Long> mPid = Optional.empty();

        public ScreenRecordRunnable(ITestDevice device, String randomId) {
            this.mDevice = device;
            this.mRandomId = randomId;
        }

        public CountDownLatch getPidCapturedLatch() {
            return mPidCapturedLatch;
        }

        public synchronized void setTestFinished() {
            this.mTestFinished = true;
        }

        public synchronized boolean getTestFinished() {
            return this.mTestFinished;
        }

        public synchronized void setPid(Optional<Long> pid) {
            this.mPid = pid;
        }

        public synchronized Optional<Long> getPid() {
            return this.mPid;
        }

        public ScreenRecordPathsResource stopRecording() throws Exception {
            this.setTestFinished();
            Optional<Long> pid = this.getPid();
            if (pid.isPresent()) {
                // Attempting to stop screenrecord process.
                CommandResult result =
                        this.mDevice.executeShellV2Command(
                                String.format("kill -s INT %d", pid.get()));
                if (result.getExitCode() != 0) {
                    // Check if pid is still alive
                    String command = String.format("kill -0 %d", pid.get());
                    CommandResult pidCheckResult = mDevice.executeShellV2Command(command);
                    if (pidCheckResult.getExitCode() == 0) {
                        throw new RuntimeException(
                                String.format(
                                        "Failed to kill screenrecord process with"
                                                + " PID %d. Exit code: %d, stderr: '%s'",
                                        pid.get(),
                                        result.getExitCode(),
                                        result.getStderr().trim()));
                    }
                }
            }
            this.setPid(Optional.empty());

            // Wait for the run() method to fully complete and exit
            if (!mCompletionLatch.await(5, TimeUnit.SECONDS)) {
                CLog.w("Screen record runnable did not complete in time after being stopped.");
            }

            // Pass a copy of mPaths. This isolates the handle's
            // lifecycle and allows mPaths to be properly reset later.
            return new ScreenRecordPathsResource(mDevice, new ArrayList<>(mPaths));
        }

        @Override
        public void run() {
            try {
                while (!this.getTestFinished()) {
                    try {
                        this.mDevice.waitForDeviceAvailable();
                    } catch (DeviceNotAvailableException e) {
                        CLog.w(
                                "Device %s became unavailable while waiting to start screen record:"
                                        + " %s",
                                mDevice.getSerialNumber(), e.getMessage());
                        continue;
                    }
                    PathGroup paths = new PathGroup(this.mRandomId, this.mPaths.size());
                    this.mPaths.add(paths);
                    String command =
                            String.format(
                                    "screenrecord --output-format h264 --time-limit=0 %s > %s 2> %s"
                                            + " & echo $!; wait",
                                    paths.h264, paths.stdout, paths.stderr);

                    OutputStream outputStream =
                            new LineCapturingOutputStream(
                                    (line) -> {
                                        this.setPid(Optional.of(Long.parseLong(line)));
                                        mPidCapturedLatch.countDown();
                                    });
                    try {
                        // If ADB is running as root, start screenrecord as a shell user.
                        if (mDevice.isAdbRoot()) {
                            command = String.format("su shell %s", command);
                        }
                        CommandResult result =
                                this.mDevice.executeShellV2Command(command, outputStream);
                        if (result.getExitCode() != 0) {
                            CLog.e(
                                    "Screenrecord command for ID %s on device %s exited with"
                                            + " non-zero code. Exit code: %d. Recording might be"
                                            + " incomplete or failed to start.",
                                    mRandomId, mDevice.getSerialNumber(), result.getExitCode());
                        }
                    } catch (DeviceNotAvailableException e) {
                        CLog.e(
                                "Device became unavailable during screenrecord command execution"
                                        + " for random ID: %s. Recording aborted.",
                                mRandomId, e);
                    }
                }
            } finally {
                // Signal completion to thread waiting in stopRecording().
                mCompletionLatch.countDown();
            }
        }
    }

    @Override
    public void onTestRunStart(DeviceMetricData runData) {

        mFailureCount = 0;
        mFirstThrottle = true;
    }

    @Override
    public void onTestStart(DeviceMetricData runData) throws DeviceNotAvailableException {
        if (mScreenRecordRunnable.isPresent()) {
            throw new IllegalStateException("Concurrent recordings");
        }

        Optional<ITestDevice> deviceOpt = getDevice();
        if (!deviceOpt.isPresent()) {
            CLog.w(new IllegalStateException("No device connected or device object is missing"));
            return;
        }
        ITestDevice device = deviceOpt.get();
        CLog.w("device: available " + device.getSerialNumber());
        String mRandomId = Long.toUnsignedString(SECURE_RANDOM.nextLong());
        ScreenRecordRunnable runnable = new ScreenRecordRunnable(device, mRandomId);
        this.mScreenRecordRunnable = Optional.of(runnable);
        mScreenRecordThread =
                new Thread(
                        this.mScreenRecordRunnable.get(),
                        "ScreenRecorder-" + device.getSerialNumber());
        mScreenRecordThread.setDaemon(true);
        mScreenRecordThread.start();

        // Wait for the screen record PID to be captured on the device.
        try {
            if (!runnable.getPidCapturedLatch().await(15, TimeUnit.SECONDS)) {
                CLog.w(
                        "Timeout waiting for screen record PID on device %s.",
                        device.getSerialNumber());
            }
        } catch (InterruptedException e) {
            CLog.w(
                    "Interrupted while waiting for screen record PID on device %s.",
                    device.getSerialNumber());
            this.mScreenRecordRunnable = Optional.empty();
            Thread.currentThread().interrupt(); // Restore the interrupted status
            throw new IllegalStateException(e); // Signal exception to RunUtil#run()
        }
    }

    @Override
    public void onTestFail(DeviceMetricData testData, TestDescription test)
            throws DeviceNotAvailableException {
        // Increment count for throttling
        mFailureCount++;
        if (mFailureCount > THROTTLE_LIMIT_PER_RUN) {
            if (mFirstThrottle) {
                CLog.w(
                        "Throttle capture of screen-record-on-failure due to too many failures"
                                + " (limit: %d).",
                        THROTTLE_LIMIT_PER_RUN);
                mFirstThrottle = false;
            }
            return;
        }

        if (!this.mScreenRecordRunnable.isPresent()) {
            CLog.w(
                    "ScreenRecordRunnable was not present when onTestFail was called for test"
                            + " %s.",
                    test);
            return;
        }

        Optional<ITestDevice> deviceOpt = getDevice();
        if (!deviceOpt.isPresent()) {
            CLog.e(
                    "No device connected or device object is missing. Cannot pull screen record"
                            + " files for failed test %s.",
                    test.toString());
            return;
        }

        // Retrieve screen recordings from device
        ITestDevice device = deviceOpt.get();
        RecoveryMode oldRecoveryMode = null;
        try {
            // Set recovery mode to NONE to avoid Tradefed reboots during file pulling,
            // ensuring complete screen record artifact collection.
            oldRecoveryMode = device.getRecoveryMode();
            device.setRecoveryMode(RecoveryMode.NONE);
            ScreenRecordRunnable screenRecordRunnable = this.mScreenRecordRunnable.get();

            // Stop recording, and delete device files.
            try (ScreenRecordPathsResource handle = screenRecordRunnable.stopRecording()) {

                // Copy captured screen recording files to metrics
                for (PathGroup pathGroup : handle.getPathGroups()) {
                    int index = pathGroup.numRecordings;
                    try {
                        // Log H264, stdout and stderr file
                        logFile(device, test, index, pathGroup.h264, LogDataType.H264, "H264");
                        logFile(device, test, index, pathGroup.stdout, LogDataType.TEXT, "stdout");
                        logFile(device, test, index, pathGroup.stderr, LogDataType.TEXT, "stderr");
                    } catch (IOException e) {
                        CLog.w(e);
                        continue;
                    }
                }
                this.mScreenRecordRunnable = Optional.empty();
            } catch (DeviceNotAvailableException e) {
                CLog.e(e);
            }

        } catch (Exception e) {
            CLog.e(e);
        } finally {
            device.setRecoveryMode(oldRecoveryMode);
        }
    }

    private void logFile(
            ITestDevice device,
            TestDescription test,
            int numRecording,
            String devicePath,
            LogDataType logType,
            String fileTypeForLog)
            throws IOException, DeviceNotAvailableException, Exception {
        String tempFilePrefix = "screenrecord_fail_";

        // Create a unique temporary file on the host machine
        File hostFile =
                File.createTempFile(
                        String.format(
                                tempFilePrefix + fileTypeForLog + "%s_%s_",
                                test.getTestName(),
                                device.getSerialNumber()),
                        fileTypeForLog);
        try (AutoCloseable hostFileCleaner =
                () -> {
                    if (!hostFile.delete()) {
                        CLog.w(
                                "Failed to delete temporary host file: %s",
                                hostFile.getAbsolutePath());
                    }
                }) {

            // Attempt to pull the file from the device to the temporary host file
            if (device.pullFile(devicePath, hostFile)) {
                try (InputStreamSource source = new FileInputStreamSource(hostFile, false)) {
                    String finalLogFileName =
                            String.format(
                                    SCREEN_RECORD_LOG_NAME_BASE_FORMAT,
                                    test.getClassName(),
                                    test.getTestName(),
                                    device.getSerialNumber(),
                                    numRecording,
                                    fileTypeForLog);
                    super.testLog(finalLogFileName, logType, source);
                }
            } else {
                CLog.e(
                        "Failed to pull screen recording %s from device path: %s. Host"
                                + " temp file: %s",
                        fileTypeForLog, devicePath, hostFile.getAbsolutePath());
            }
        }
    }

    @Override
    public void onTestEnd(
            DeviceMetricData testData, final Map<String, Metric> currentTestCaseMetrics)
            throws DeviceNotAvailableException {
        // onTestEnd() fires after onTestFail()
        if (mScreenRecordRunnable.isPresent()) {
            ScreenRecordRunnable screenRecordRunnable = this.mScreenRecordRunnable.get();
            try {
                screenRecordRunnable.stopRecording().close();
            } catch (Exception e) {
                CLog.w(e);
            } finally {
                this.mScreenRecordRunnable = Optional.empty();
            }
        }

        // Ensure the host-side thread is fully stopped.
        if (mScreenRecordThread != null && mScreenRecordThread.isAlive()) {
            mScreenRecordThread.interrupt();
            try {
                mScreenRecordThread.join(5000);
            } catch (InterruptedException e) {
                CLog.w("Interrupted while waiting for screen record daemon thread to join.");
                Thread.currentThread().interrupt();
            }
        }
        mScreenRecordThread = null;
    }

    private Optional<ITestDevice> getDevice() {
        List<ITestDevice> devices = getRealDevices();
        if (devices.size() != 1) {
            throw new IllegalStateException("Expected only one device for metric collection");
        }
        ITestDevice device = devices.get(0);

        TestDeviceState state = device.getDeviceState();
        if (!TestDeviceState.ONLINE.equals(state)) {
            return Optional.empty();
        }
        return Optional.of(device);
    }
}
