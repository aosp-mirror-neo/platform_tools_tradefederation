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

package com.android.tradefed.targetprep;

import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.RunUtil;

/** Reboots to base system test environment */
@OptionClass(alias = "base-system-test-env-preparer")
public class BaseSystemTestEnvPreparer extends BaseTargetPreparer {

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {

        ITestDevice device = testInfo.getDevice();

        if (!TestDeviceState.RECOVERY.equals(device.getDeviceState())) {
            CLog.i(
                    "Device is not in RECOVERY, attempting to reboot into recovery.");
            device.rebootIntoRecovery();
            if (!TestDeviceState.RECOVERY.equals(device.getDeviceState())) {
                throw new TargetSetupError(
                        "Device did not reach RECOVERY state.",
                        device.getDeviceDescriptor());
            }
            CLog.i("Device is now in RECOVERY.");
        } else {
            CLog.i("Device is already in RECOVERY.");
        }

        // We can't use device.enableAdbRoot() because it expects the device to come
        // back online but it comes back as recovery (since we are in recovery).
        // Also, adb root in recovery can time out causing an error to be returned
        // by executeAdbCommand. (see b/444742481)
        CLog.i("Switch to root.");
        String res = device.executeAdbCommand("root");
        CLog.i("Switch to root res=%s", res);

        CLog.i("Sleep 15 seconds for root. (b/444742481)");
        RunUtil.getDefault().sleep(15 * 1000);

        CLog.i("Stop recovery");
        res = device.executeAdbCommand("shell", "stop", "recovery");
        CLog.i("Stop recovery res=%s", res);
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        device.reboot();
    }
}

