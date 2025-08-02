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
import com.android.tradefed.result.FailureDescription;

/** Collect a bugreportz when a test case fails. */
public class BugreportzOnFailureCollector extends BaseDeviceMetricCollector {

    private static final String NAME_FORMAT = "%s-%s-bugreportz-on-failure";
    private static final int THROTTLE_LIMIT_PER_RUN = 10;
    private int mCurrentCount = 0;
    private boolean mFirstThrottle = true;

    @Override
    public void onTestRunStart(DeviceMetricData runData) throws DeviceNotAvailableException {
        mCurrentCount = 0;
        mFirstThrottle = true;
    }

    @Override
    public void onTestRunFailed(DeviceMetricData testData, FailureDescription failure) {
        // if default collection enabled, it will be collected manually.
        if (!isDefaultMetricCollectionEnabled()) {
            if (mCurrentCount > THROTTLE_LIMIT_PER_RUN) {
                if (mFirstThrottle) {
                    CLog.w("Throttle capture of bugreportz-on-failure due to too many failures.");
                    mFirstThrottle = false;
                }
                return;
            }
            collectBugReport(getRunName());
            mCurrentCount++;
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
                                "Throttle capture of bugreportz-on-failure due to too many"
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
                                "Throttle capture of bugreportz-on-failure due to too many"
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
        collectBugReport(name);
    }

    private void collectBugReport(String name) {
        for (ITestDevice device : getDevices()) {
            String logName = String.format(NAME_FORMAT, name, device.getSerialNumber());
            if (!device.logBugreport(logName, getInvocationListener())) {
                CLog.e("Failed to capture bugreportz on '%s'", device.getSerialNumber());
            }
        }
    }
}
