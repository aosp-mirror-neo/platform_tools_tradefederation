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

package com.google.android.tradefed.targetprep;

import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;

import com.google.android.tradefed.device.SettingsUtil;

import java.io.File;

/**
 * A {@link ITargetPreparer} that configures a device's settings in preparation for running CTS
 * tests.
 *
 * <p>This class is temporarily in vendor/google, because it relies on a not-ready-for open source
 * mechanism to change secure settings. Once a open-source safe method for changing secure settings
 * is available, this class will be migrated to the CTS project
 *
 * <p>This preparer must run *after* the CTS accessibility apk is installed
 */
public class CtsSettingsSetup extends BaseTargetPreparer {
    @Option(
            name = "setting-cmd-apk-path",
            description = "file system path to the settings cmd apk.")
    private File mSettingCmdApk = null;

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        if (mSettingCmdApk != null && !mSettingCmdApk.exists()) {
            throw new TargetSetupError(
                    String.format(
                            "--setting-cmd-apk-path '%s' does not exist",
                            mSettingCmdApk.getAbsolutePath()),
                    device.getDeviceDescriptor());
        }
        device.waitForDeviceAvailable();
        SettingsUtil settingsUtil = new SettingsUtil(device, mSettingCmdApk);
        settingsUtil.installSettingCmdApk();
        CLog.i("Modifying device settings to prep for CTS tests", device.getSerialNumber());
        settingsUtil.changeSecureSetting("mock_location", "1");
        // turn off auto-rotate screen
        settingsUtil.changeSetting("accelerometer_rotation", "0");
        // set user rotation to natural orientation
        settingsUtil.changeSetting("user_rotation", "0");
        // set network_location_opt_in on legacy builds
        settingsUtil.changeSecureSetting("network_location_opt_in", "1");
        // for newish builds, network_location_opt_in is stored in google partner dB. update it
        // to prevent 'Location Consent' dialog from appearing
        settingsUtil.changeGooglePartnerSetting("network_location_opt_in", "1");
        settingsUtil.uninstallSettingCmd();
    }
}
