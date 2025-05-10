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

import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

/** Preparer to execute futility commands. */
public class FutilityCommandPreparer extends BaseTargetPreparer {

    @Option(
            name = "gbb-flag-value",
            description = "The value of the gbb flag to write to the device.")
    private String mGbbFlag = "0x80000018";

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInformation.getDevice();
        if (!device.enableAdbRoot()) {
            CLog.w("Root is required to write Gbb flags");
            return;
        }
        CommandResult originalStatus = device.executeShellV2Command("su root crossystem");
        CLog.d("exit code: %s", originalStatus.getExitCode());
        CLog.d("stdout: %s", originalStatus.getStdout());
        CLog.d("stderr: %s", originalStatus.getStderr());

        CommandResult writeGbb =
                device.executeShellV2Command(
                        "su root futility gbb --set --flash --flags +" + mGbbFlag);
        CLog.d("stdout: %s", writeGbb.getStdout());
        CLog.d("stderr: %s", writeGbb.getStderr());
        if (!CommandStatus.SUCCESS.equals(writeGbb.getStatus())) {
            throw new TargetSetupError("Failed to write gbb flag.", device.getDeviceDescriptor());
        }
        device.reboot();
    }
}
