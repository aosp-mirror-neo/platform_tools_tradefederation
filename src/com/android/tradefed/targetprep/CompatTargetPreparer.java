/*
 * Copyright (C) 2026 The Android Open Source Project
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
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** A {@link ITargetPreparer} that prepares the device for running compat tests. */
@OptionClass(alias = "compat-target-preparer")
public class CompatTargetPreparer extends TestAppInstallSetup {
    private static final float COMPAT_DENSITY_SCALE = 0.7f;
    private final Map<String, Float> mOriginalDensities = new HashMap<>();

    @Override
    protected void installer(TestInformation testInfo, Map<File, String> appFilesAndPackages)
            throws TargetSetupError, DeviceNotAvailableException {
        ITestDevice testDevice = testInfo.getDevice();
        if (testDevice.hasFeature("android.hardware.type.automotive")) {
            setupAutomotiveCompat(testDevice, appFilesAndPackages);
        }
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        ITestDevice testDevice = testInfo.getDevice();
        if (testDevice.hasFeature("android.hardware.type.automotive")) {
            restoreAutomotiveCompat(testDevice);
        }

        super.tearDown(testInfo, e);
    }

    private void setupAutomotiveCompat(
            ITestDevice testDevice, Map<File, String> appFilesAndPackages)
            throws DeviceNotAvailableException {
        for (String packageName : appFilesAndPackages.values()) {
            if (packageName == null) {
                continue;
            }
            if (mOriginalDensities.containsKey(packageName)) {
                // Package already processed, skip
                continue;
            }
            Float originalDensity = getDensityScaleFactor(testDevice, packageName);
            if (originalDensity == null) {
                CLog.e(
                        "Failed to get original value for %s, skipping density scaling.",
                        packageName);
                continue;
            }

            boolean success = setDensityScaleFactor(testDevice, packageName, COMPAT_DENSITY_SCALE);
            if (!success) {
                CLog.e(
                        "Failed to set density scale factor for %s to %f",
                        packageName, COMPAT_DENSITY_SCALE);
                continue;
            }
            mOriginalDensities.put(packageName, originalDensity);
            CLog.d("Set density scale factor for %s to %f", packageName, COMPAT_DENSITY_SCALE);
        }
    }

    private void restoreAutomotiveCompat(ITestDevice testDevice)
            throws DeviceNotAvailableException {
        for (Map.Entry<String, Float> entry : mOriginalDensities.entrySet()) {
            boolean success = setDensityScaleFactor(testDevice, entry.getKey(), entry.getValue());
            if (!success) {
                CLog.e(
                        "Failed to reset density scale factor for %s to %f",
                        entry.getKey(), entry.getValue());
                continue;
            }
            CLog.d("Reset the density scale factor for %s to %s", entry.getKey(), entry.getValue());
        }
        mOriginalDensities.clear();
    }

    private Float getDensityScaleFactor(ITestDevice device, String packageName)
            throws DeviceNotAvailableException {
        CommandResult result =
                device.executeShellV2Command(
                        String.format("cmd car_service get-density-scale-factor %s", packageName));
        if (result.getStatus() != CommandStatus.SUCCESS
                || result.getExitCode() != 0
                || result.getStdout() == null
                || result.getStdout().trim().isEmpty()) {
            return null;
        }
        try {
            return Float.parseFloat(result.getStdout().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean setDensityScaleFactor(ITestDevice device, String packageName, Float scale)
            throws DeviceNotAvailableException {
        CommandResult result =
                device.executeShellV2Command(
                        String.format(
                                "cmd car_service set-density-scale-factor %s %s",
                                packageName, scale));
        if (result.getStatus() != CommandStatus.SUCCESS || result.getExitCode() != 0) {
            return false;
        }
        return true;
    }
}
