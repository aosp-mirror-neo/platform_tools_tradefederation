/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.device.CollectingByteOutputReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ILogcatReceiver;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.LogcatReceiver;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Collector that will capture and log a logcat when a test case or test run fails. If default
 * collection is enabled, it will collect host logs at the specified levels.
 */
public class LogcatOnFailureCollector extends BaseDeviceMetricCollector {

    private static final int MAX_LOGCAT_SIZE_BYTES = 4 * 1024 * 1024;
    private static final int MODULE_MAX_LOGCAT_SIZE_BYTES = 40 * 1024 * 1024;

    /** Always include a bit of prior data to capture what happened before */
    private static final int OFFSET_CORRECTION = 10000;

    private static final String NAME_FORMAT = "%s-%s-logcat-on-failure";

    private static final String LOGCAT_COLLECT_CMD = "logcat -b all -T 150";
    // -t implies -d (dump) so it's a one time collection
    private static final String LOGCAT_COLLECT_CMD_LEGACY = "logcat -b all -t 5000";
    private static final int API_LIMIT = 20;

    private static final int THROTTLE_LIMIT_PER_RUN = 10;

    private Map<ITestDevice, ILogcatReceiver> mLogcatReceivers = new HashMap<>();
    private Map<ITestDevice, LogOffsets> mOffset = new HashMap<>();
    private boolean runInitDone = false;
    private int mCurrentCount = 0;
    private boolean mFirstThrottle = true;
    private boolean mCurrentTestFailed = false;
    private boolean mCurrentTestRunFailed = false;

    @Override
    public void onTestRunStartBlocking() throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            // Do not init during default collection, allow regular test lifecycle events
            return;
        }
        mCurrentCount = 0;
        mFirstThrottle = true;
        for (ITestDevice device : getRealDevices()) {
            if (getApiLevelNoThrow(device) < API_LIMIT) {
                continue;
            }
            // In case of multiple runs for the same test runner, re-init the receiver.
            initReceiver(device);
        }
        // Get the current offset of the buffer to be able to query later
        collectCurrentOffsets(MetricCollectionLevel.TEST_RUN);
        runInitDone = true;
    }

    @Override
    public void onTestRunStart(DeviceMetricData runData) {
        if (runInitDone) {
            return;
        }
        mCurrentCount = 0;
        mFirstThrottle = true;
        mCurrentTestRunFailed = false;
        for (ITestDevice device : getRealDevices()) {
            if (getApiLevelNoThrow(device) < API_LIMIT) {
                continue;
            }
            // In case of multiple runs for the same test runner, re-init the receiver.
            initReceiver(device);
        }
        // Get the current offset of the buffer to be able to query later
        collectCurrentOffsets(MetricCollectionLevel.TEST_RUN);
    }

    @Override
    public void onTestStart(DeviceMetricData testData) {
        if (isDefaultMetricCollectionEnabled()) {
            collectCurrentOffsets(MetricCollectionLevel.TEST_CASE);
        }
        mCurrentTestFailed = false;
    }

    @Override
    public void onTestModuleStarted() throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            // re-init the receiver at module level during default collection
            // NOTE: during default collection, we want to init the receivers at test run level
            // and also at module level, since two separate instances of collector will be used.
            for (ITestDevice device : getRealDevices()) {
                if (getApiLevelNoThrow(device) < API_LIMIT) {
                    continue;
                }
                initReceiver(device);
            }
            // Get the current offset of the buffer to be able to query later
            collectCurrentOffsets(MetricCollectionLevel.MODULE);
        }
    }

    @Override
    public void onTestFail(DeviceMetricData testData, TestDescription test)
            throws DeviceNotAvailableException {
        mCurrentTestFailed = true;
        if (!isDefaultMetricCollectionEnabled()) {
            // During non-default collection, start collecting from test run offset
            collectAndLog(test.toString(), MAX_LOGCAT_SIZE_BYTES, MetricCollectionLevel.TEST_RUN);
        } else if (MetricCollectionLevel.TEST_CASE.equals(getOnFailureCollectionLevel())) {
            collectAndLog(
                    "test_level-" + test.toString(),
                    MAX_LOGCAT_SIZE_BYTES,
                    MetricCollectionLevel.TEST_CASE);
        }
    }

    @Override
    public void onTestRunFailed(DeviceMetricData testData, FailureDescription failure)
            throws DeviceNotAvailableException {
        mCurrentTestRunFailed = true;
        if (!isDefaultMetricCollectionEnabled()) {
            collectAndLog("run-failure", MAX_LOGCAT_SIZE_BYTES, MetricCollectionLevel.TEST_RUN);
        } else if (MetricCollectionLevel.TEST_RUN.equals(getOnFailureCollectionLevel())) {
            collectAndLog(
                    "test_run_level-" + getRunName(),
                    MAX_LOGCAT_SIZE_BYTES,
                    MetricCollectionLevel.TEST_RUN);
        }
    }

    @Override
    public void onTestEnd(DeviceMetricData testData, Map<String, Metric> currentTestCaseMetrics)
            throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.TEST_CASE.equals(getAlwaysOnCollectionLevel())) {
                if (!mCurrentTestFailed
                        || !MetricCollectionLevel.TEST_CASE.equals(getOnFailureCollectionLevel())) {
                    collectAndLog(
                            "test_level-" + getTestName(),
                            MAX_LOGCAT_SIZE_BYTES,
                            MetricCollectionLevel.TEST_CASE);
                }
            }
        }
    }

    @Override
    public void onTestRunEnd(DeviceMetricData runData, Map<String, Metric> currentRunMetrics)
            throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.TEST_RUN.equals(getAlwaysOnCollectionLevel())) {
                if (!mCurrentTestRunFailed
                        || !MetricCollectionLevel.TEST_RUN.equals(getOnFailureCollectionLevel())) {
                    collectAndLog(
                            "test_run_level-" + getRunName(),
                            MAX_LOGCAT_SIZE_BYTES,
                            MetricCollectionLevel.TEST_RUN);
                }
            }
        }
        runInitDone = false;
        clearReceivers();
    }

    @Override
    public void onTestModuleEnded() throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.MODULE.equals(getAlwaysOnCollectionLevel())
                    || (MetricCollectionLevel.MODULE.equals(getOnFailureCollectionLevel())
                            && doesModuleContainFailure())) {
                collectAndLog(
                        "module_level-" + getModuleName(),
                        MODULE_MAX_LOGCAT_SIZE_BYTES,
                        MetricCollectionLevel.MODULE);
            }
            clearReceivers();
        }
    }

    @VisibleForTesting
    ILogcatReceiver createLogcatReceiver(ITestDevice device) {
        // Use logcat -T 'count' to only print a few line before we start and not the full buffer
        return new LogcatReceiver(
                device, LOGCAT_COLLECT_CMD, device.getOptions().getMaxLogcatDataSize(), 0);
    }

    @VisibleForTesting
    CollectingByteOutputReceiver createLegacyCollectingReceiver() {
        return new CollectingByteOutputReceiver();
    }

    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    protected void collectAndLog(String testName, int size, MetricCollectionLevel level)
            throws DeviceNotAvailableException {
        if (mCurrentCount > THROTTLE_LIMIT_PER_RUN) {
            if (mFirstThrottle) {
                CLog.w("Throttle capture of logcat-on-failure due to too many failures.");
                mFirstThrottle = false;
            }
            return;
        }
        // Delay slightly for the error to get in the logcat
        getRunUtil().sleep(100);
        mCurrentCount++;
        for (ITestDevice device : getRealDevices()) {
            boolean isDeviceOnline = isDeviceOnline(device);
            ILogcatReceiver receiver = mLogcatReceivers.get(device);
            // Receiver is only initialized above API 19, if not supported, we use a legacy command
            if (receiver == null) {
                if (isDeviceOnline) {
                    legacyCollection(device, testName);
                } else {
                    CLog.w("Skip legacy LogcatOnFailureCollector device is offline.");
                }
                continue;
            }
            // If supported get the logcat buffer, even if device is offline to get the buffer
            saveLogcatSource(
                    testName,
                    receiver.getLogcatData(size, mOffset.get(device).getOffset(level)),
                    device.getSerialNumber());
        }
    }

    private void initReceiver(ITestDevice device) {
        if (mLogcatReceivers.get(device) == null) {
            ILogcatReceiver receiver = createLogcatReceiver(device);
            mLogcatReceivers.put(device, receiver);
            receiver.start();
        }
    }

    private void clearReceivers() {
        for (ILogcatReceiver receiver : mLogcatReceivers.values()) {
            receiver.stop();
            receiver.clear();
        }
        mLogcatReceivers.clear();
        mOffset.clear();
    }

    private int getApiLevelNoThrow(ITestDevice device) {
        try {
            return device.getApiLevel();
        } catch (DeviceNotAvailableException e) {
            return 1;
        }
    }

    private void legacyCollection(ITestDevice device, String testName)
            throws DeviceNotAvailableException {
        CollectingByteOutputReceiver outputReceiver = createLegacyCollectingReceiver();
        device.executeShellCommand(LOGCAT_COLLECT_CMD_LEGACY, outputReceiver);
        saveLogcatSource(
                testName,
                new ByteArrayInputStreamSource(outputReceiver.getOutput()),
                device.getSerialNumber());
        outputReceiver.cancel();
    }

    private void saveLogcatSource(String testName, InputStreamSource source, String serial) {
        if (source == null) {
            return;
        }
        try (InputStreamSource logcatSource = source) {
            // If the resulting logcat looks wrong or empty, discard it
            if (logcatSource.size() < 75L) {
                CLog.e(
                        "Discarding logcat on failure (size=%s): it failed to collect something"
                                + " relevant likely due to timings.",
                        logcatSource.size());
                return;
            }
            String name = String.format(NAME_FORMAT, testName, serial);
            super.testLog(name, LogDataType.LOGCAT, logcatSource);
        }
    }

    private boolean isDeviceOnline(ITestDevice device) {
        TestDeviceState state = device.getDeviceState();
        if (!TestDeviceState.ONLINE.equals(state)) {
            return false;
        }
        return true;
    }

    /** Get the current offset of the buffer to be able to query later. */
    private void collectCurrentOffsets(MetricCollectionLevel level) {
        // Do not collect offset if throttle limit has already reached.
        if (!mFirstThrottle) {
            return;
        }
        for (ITestDevice device : getRealDevices()) {
            try (InputStreamSource data = mLogcatReceivers.get(device).getLogcatData()) {
                int offset = (int) data.size();
                if (offset > OFFSET_CORRECTION) {
                    offset -= OFFSET_CORRECTION;
                }
                if (mOffset.get(device) == null) {
                    mOffset.put(device, new LogOffsets());
                }
                LogOffsets logOffset = mOffset.get(device);
                logOffset.setOffset(offset, level);
            }
        }
    }

    @Override
    public boolean captureModuleLevel() {
        return true;
    }
}
