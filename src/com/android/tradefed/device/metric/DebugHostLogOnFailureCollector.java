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
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.SnapshotInputStreamSource;
import com.android.tradefed.result.TestDescription;

import java.io.IOException;
import java.io.InputStream;

/** Collector that will gather and log the host-side logs when a test case failure occurs. */
public class DebugHostLogOnFailureCollector extends BaseDeviceMetricCollector {

    private static final String NAME_FORMAT = "%s-debug-hostlog-on-failure";
    private static final int THROTTLE_LIMIT_PER_RUN = 10;

    private int mCurrentCount = 0;
    private boolean mFirstThrottle = true;
    private LogOffsets mLogOffsets = new LogOffsets();

    @Override
    public void onTestRunStart(DeviceMetricData runData) {
        mCurrentCount = 0;
        mFirstThrottle = true;
        collectCurrentOffsets(TEST_RUN);
    }

    @Override
    public void onTestStart(DeviceMetricData testData) throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            collectCurrentOffsets(TEST_CASE);
        }
    }

    @Override
    public void onTestModuleStarted() throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            collectCurrentOffsets(MODULE);
        }
    }

    @Override
    public void onTestFail(DeviceMetricData testData, TestDescription test) {
        // do not collect during default collection. It will be manually collected.
        if (!isDefaultMetricCollectionEnabled()) {
            if (mCurrentCount > THROTTLE_LIMIT_PER_RUN) {
                if (mFirstThrottle) {
                    CLog.w("Throttle capture of host_log-on-failure due to too many failures.");
                    mFirstThrottle = false;
                }
                return;
            }
            collectHostLog(TEST_RUN, test.toString());
            mCurrentCount++;
        }
    }

    @VisibleForTesting
    ILeveledLogOutput getLogger() {
        LogRegistry registry = (LogRegistry) LogRegistry.getLogRegistry();
        return registry.getLogger();
    }

    private void collectHostLog(MetricCollectionLevel level, String name) {
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

    @Override
    public void collectMetrics(MetricCollectionLevel level, String name) {
        switch (level) {
            case TEST_CASE:
                if (mCurrentCount > THROTTLE_LIMIT_PER_RUN) {
                    if (mFirstThrottle) {
                        CLog.w(
                                "Throttle capture of host_log-on-failure due to too many"
                                        + " failures.");
                        mFirstThrottle = false;
                    }
                    return;
                }
                mCurrentCount++;
                name = "test-level-" + name;
                break;
            case TEST_RUN:
                if (mCurrentCount > THROTTLE_LIMIT_PER_RUN) {
                    if (mFirstThrottle) {
                        CLog.w(
                                "Throttle capture of host_log-on-failure due to too many"
                                        + " failures.");
                        mFirstThrottle = false;
                    }
                    return;
                }
                mCurrentCount++;
                name = "test-run-level-" + name;
                break;
            case MODULE:
                name = "module-level-" + name;
                break;
            default:
                // Should never happen
                break;
        }
        collectHostLog(level, name);
    }
}
