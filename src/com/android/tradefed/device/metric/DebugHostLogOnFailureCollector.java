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

import static com.android.tradefed.device.metric.IMetricCollector.MetricCollectionLevel.MODULE;
import static com.android.tradefed.device.metric.IMetricCollector.MetricCollectionLevel.TEST_CASE;
import static com.android.tradefed.device.metric.IMetricCollector.MetricCollectionLevel.TEST_RUN;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.ILeveledLogOutput;
import com.android.tradefed.log.LogRegistry;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.SnapshotInputStreamSource;
import com.android.tradefed.result.TestDescription;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Collector that will gather and log the host-side logs when a test case failure occurs. If default
 * collection is enabled, it will collect host logs at the specified levels.
 */
public class DebugHostLogOnFailureCollector extends BaseDeviceMetricCollector {

    private static final String NAME_FORMAT = "%s-debug-hostlog-on-failure";
    private static final int THROTTLE_LIMIT_PER_RUN = 10;

    private int mCurrentCount = 0;
    private boolean mFirstThrottle = true;
    private LogOffsets mLogOffsets = new LogOffsets();
    private boolean mCurrentTestFailed = false;
    private boolean mCurrentTestRunFailed = false;

    @Override
    public void onTestRunStart(DeviceMetricData runData) {
        mCurrentCount = 0;
        mFirstThrottle = true;
        mCurrentTestFailed = false;
        collectCurrentOffsets(TEST_RUN);
    }

    @Override
    public void onTestStart(DeviceMetricData testData) throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            collectCurrentOffsets(TEST_CASE);
        }
        mCurrentTestFailed = false;
    }

    @Override
    public void onTestModuleStarted() throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            collectCurrentOffsets(MODULE);
        }
    }

    @Override
    public void onTestFail(DeviceMetricData testData, TestDescription test) {
        if (!isDefaultMetricCollectionEnabled()) {
            collectHostLog(TEST_RUN, test.toString());
        } else {
            if (MetricCollectionLevel.TEST_CASE.equals(getOnFailureCollectionLevel())) {
                collectHostLog(TEST_CASE, "test_level-" + test.toString());
            }
        }
        mCurrentTestFailed = true;
    }

    @Override
    public void onTestRunFailed(DeviceMetricData testData, FailureDescription failure)
            throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.TEST_RUN.equals(getOnFailureCollectionLevel())) {
                collectHostLog(TEST_RUN, "test_run_level-" + getRunName());
            }
        }
        mCurrentTestRunFailed = true;
    }

    @Override
    public void onTestEnd(
            DeviceMetricData testData, Map<String, MetricMeasurement.Metric> currentTestCaseMetrics)
            throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.TEST_CASE.equals(getAlwaysOnCollectionLevel())) {
                if (!mCurrentTestFailed
                        || !MetricCollectionLevel.TEST_CASE.equals(getOnFailureCollectionLevel())) {
                    collectHostLog(TEST_CASE, "test_level-" + getTestName());
                }
            }
        }
    }

    @Override
    public void onTestRunEnd(
            DeviceMetricData runData, Map<String, MetricMeasurement.Metric> currentRunMetrics)
            throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.TEST_RUN.equals(getAlwaysOnCollectionLevel())) {
                if (!mCurrentTestRunFailed
                        || !MetricCollectionLevel.TEST_RUN.equals(getOnFailureCollectionLevel())) {
                    collectHostLog(TEST_RUN, "test_run_level-" + getRunName());
                }
            }
        }
    }

    @Override
    public void onTestModuleEnded() throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.MODULE.equals(getAlwaysOnCollectionLevel())
                    || (MetricCollectionLevel.MODULE.equals(getOnFailureCollectionLevel())
                            && doesModuleContainFailure())) {
                collectHostLog(MODULE, "module_level-" + getModuleName());
            }
        }
    }

    @VisibleForTesting
    ILeveledLogOutput getLogger() {
        LogRegistry registry = (LogRegistry) LogRegistry.getLogRegistry();
        return registry.getLogger();
    }

    private void collectHostLog(MetricCollectionLevel level, String name) {
        if (mCurrentCount > THROTTLE_LIMIT_PER_RUN) {
            if (mFirstThrottle) {
                CLog.w("Throttle capture of host_log-on-failure due to too many failures.");
                mFirstThrottle = false;
            }
            return;
        }
        mCurrentCount++;

        Integer offset = mLogOffsets.getOffset(level);
        if (offset == null) {
            return;
        }
        try (InputStreamSource source = getLogger().getLog()) {
            if (source == null) {
                return;
            }
            try (InputStream stream = source.createInputStream()) {
                stream.skip(offset);
                try (InputStreamSource logSource =
                        new SnapshotInputStreamSource("host-log-failure", stream)) {
                    super.testLog(
                            String.format(NAME_FORMAT, name), LogDataType.HOST_LOG, logSource);
                }
            }
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    /** Get the current offset of the buffer to be able to query later. */
    private void collectCurrentOffsets(MetricCollectionLevel level) {
        try (InputStreamSource source = getLogger().getLog()) {
            if (source == null) {
                CLog.e(
                        "Could not obtain the host logs for debugging. It won't be available "
                                + "in the event of test cases failures.");
                // if host log could not be obtained, reset the offset to null.
                mLogOffsets.setOffset(null, level);
                return;
            }
            mLogOffsets.setOffset((int) source.size(), level);
        }
    }

    @Override
    public boolean captureModuleLevel() {
        return true;
    }
}
