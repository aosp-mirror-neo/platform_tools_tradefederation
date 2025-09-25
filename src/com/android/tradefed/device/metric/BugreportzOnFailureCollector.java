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

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.TestDescription;

import java.util.Map;

/**
 * Collect a bugreportz when a test case fails. If default collection is enabled, it will collect
 * bugreport at the specified levels.
 */
public class BugreportzOnFailureCollector extends BaseDeviceMetricCollector {

    private static final String NAME_FORMAT = "%s-%s-bugreportz-on-failure";
    private static final int THROTTLE_LIMIT_PER_RUN = 10;
    private int mCurrentCount = 0;
    private boolean mFirstThrottle = true;
    private boolean mCurrentTestFailed = false;
    private boolean mCurrentTestRunFailed = false;

    @Override
    public void onTestRunStart(DeviceMetricData runData) throws DeviceNotAvailableException {
        mCurrentCount = 0;
        mFirstThrottle = true;
        mCurrentTestRunFailed = false;
    }

    @Override
    public void onTestStart(DeviceMetricData testData) throws DeviceNotAvailableException {
        mCurrentTestFailed = false;
    }

    @Override
    public void onTestFail(DeviceMetricData testData, TestDescription test)
            throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.TEST_CASE.equals(getOnFailureCollectionLevel())) {
                collectBugReport("test_level-" + test.toString());
            }
        }
        mCurrentTestFailed = true;
    }

    @Override
    public void onTestEnd(
            DeviceMetricData testData, Map<String, MetricMeasurement.Metric> currentTestCaseMetrics)
            throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.TEST_CASE.equals(getAlwaysOnCollectionLevel())) {
                if (!mCurrentTestFailed
                        || !MetricCollectionLevel.TEST_CASE.equals(getOnFailureCollectionLevel())) {
                    collectBugReport("test_level-" + getTestName());
                }
            }
        }
    }

    @Override
    public void onTestRunFailed(DeviceMetricData testData, FailureDescription failure) {
        if (!isDefaultMetricCollectionEnabled()) {
            collectBugReport(getRunName());
        } else {
            if (MetricCollectionLevel.TEST_RUN.equals(getOnFailureCollectionLevel())) {
                collectBugReport("test_run_level-" + getRunName());
            }
        }
        mCurrentTestRunFailed = true;
    }

    @Override
    public void onTestRunEnd(
            DeviceMetricData runData, Map<String, MetricMeasurement.Metric> currentRunMetrics)
            throws DeviceNotAvailableException {
        if (isDefaultMetricCollectionEnabled()) {
            if (MetricCollectionLevel.TEST_RUN.equals(getAlwaysOnCollectionLevel())) {
                if (!mCurrentTestRunFailed
                        || !MetricCollectionLevel.TEST_RUN.equals(getOnFailureCollectionLevel())) {
                    collectBugReport("test_run_level-" + getRunName());
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
                collectBugReport("module_level-" + getModuleName());
            }
        }
    }

    @Override
    public boolean captureModuleLevel() {
        return true;
    }

    private void collectBugReport(String name) {
        if (mCurrentCount > THROTTLE_LIMIT_PER_RUN) {
            if (mFirstThrottle) {
                CLog.w("Throttle capture of bugreportz-on-failure due to too many failures.");
                mFirstThrottle = false;
            }
            return;
        }
        mCurrentCount++;
        for (ITestDevice device : getDevices()) {
            String logName = String.format(NAME_FORMAT, name, device.getSerialNumber());
            if (!device.logBugreport(logName, getInvocationListener())) {
                CLog.e("Failed to capture bugreportz on '%s'", device.getSerialNumber());
            }
        }
    }
}
