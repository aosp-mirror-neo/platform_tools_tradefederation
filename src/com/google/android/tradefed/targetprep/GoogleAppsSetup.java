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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.observatory.IDiscoverDependencies;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ILabPreparer;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.BinaryState;
import com.android.tradefed.util.BuildTestsZipUtils;
import com.android.tradefed.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link ITargetPreparer} that supports various setup options for Google apps
 *
 * <p>Note: a device must have an account configured first, see {@link GoogleAccountPreparer}
 */
@OptionClass(alias = "google-apps-setup")
public class GoogleAppsSetup extends BaseTargetPreparer
        implements IDiscoverDependencies, ILabPreparer {

    private static final String UTIL_PKG_NAME = "com.google.android.apps.platformutils";
    private static final String UTIL_APK_NAME = "GoogleAppsTestUtils.apk";
    private static final String VENDING_INSTR = ".FinskyInstrumentation";
    private static final String INSTR_SUCCESS = "INSTRUMENTATION_CODE: -1";
    private static final String CHECK_INSTRUMENTATION_CMD =
            "pm list instrumentation " + UTIL_PKG_NAME;
    private static final String DEV_KEYS = "dev-keys";

    /**
     * format string for the util instrumentation
     *
     * <p>Parameters in order: command name, extra options, instrumentation name
     */
    private static final String CMD_FMT =
            "am instrument -w -r -e command %s %s " + UTIL_PKG_NAME + "/%s";

    // constants for supported commands
    private static final String CMD_AUTO_UPDATE = "auto_update";

    @Option(name = "vending-auto-update", description = "Turn Play Store auto update on or off")
    private BinaryState mVendingAutoUpdate = BinaryState.IGNORE;

    @Option(
            name = "resource-fallback",
            description = "Fallback to searching the GoogleAppsTestUtils.apk in resources.")
    private boolean mFallbackToResources = true;

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        List<String> commands = new ArrayList<>();
        if (!BinaryState.IGNORE.equals(mVendingAutoUpdate)) {
            commands.add(
                    String.format(
                            CMD_FMT,
                            CMD_AUTO_UPDATE,
                            /* extra args */
                            String.format(
                                    "-e value %s",
                                    BinaryState.ON.equals(mVendingAutoUpdate) ? "true" : "false"),
                            VENDING_INSTR));
        }
        if (commands.isEmpty()) {
            CLog.d("No options configured, no actions taken");
            return;
        }
        ITestDevice device = testInfo.getDevice();
        String key = device.getBuildSigningKeys();
        if (!DEV_KEYS.equals(key)) {
            CLog.i(
                    "The build signing key for device is %s but this preparer will only work on "
                            + "Google internal builds signed with %s. Skipping.",
                    key, DEV_KEYS);
            return;
        }
        // install util package
        if (!installTestUtil(testInfo.getBuildInfo(), device)) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to install Google apps util on device %s",
                            device.getSerialNumber()),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.APK_INSTALLATION_FAILED);
        }
        // run commands
        try {
            CLog.i("Setting up Google apps options.");
            for (String command : commands) {
                String result = device.executeShellCommand(command);
                if (result == null || !result.contains(INSTR_SUCCESS)) {
                    String message =
                            String.format("Failed to run command: %s. output: %s", command, result);
                    CLog.w(message);
                    throw new TargetSetupError(
                            message,
                            device.getDeviceDescriptor(),
                            DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
                }
            }
        } finally {
            device.uninstallPackage(UTIL_PKG_NAME);
        }

        // reboot the device and wait for adb connection
        if (BinaryState.OFF.equals(mVendingAutoUpdate)) {
            CLog.d("Rebooting device after Google apps setup");
            device.reboot();
        }
    }

    /** Install the utility */
    boolean installTestUtil(IBuildInfo buildInfo, ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        // TODO: This is directly lifted from GoogleAccountPreparer, and similar code exists in
        // WiFiHelper, so refactoring a util class for installing embedded apks is needed
        final String inst = device.executeShellCommand(CHECK_INSTRUMENTATION_CMD);
        if ((inst != null) && inst.contains(UTIL_PKG_NAME)) {
            // Good to go
            return true;
        } else {
            // Attempt to install utility
            File apkTempFile = null;
            try {
                apkTempFile =
                        BuildTestsZipUtils.getApkFile(
                                buildInfo,
                                UTIL_APK_NAME,
                                null,
                                null, /* alt dir settings */
                                mFallbackToResources, /* also look up in test harness resource as fallback */
                                DEV_KEYS); // only dev-keys is supported and it's already checked
                // earlier
                if (apkTempFile == null) {
                    throw new TargetSetupError(
                            String.format(
                                    "Failed to find %s. Please make "
                                            + "sure you configured properly the "
                                            + "download of that apk.",
                                    UTIL_APK_NAME),
                            null,
                            null,
                            false,
                            InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
                }
                CLog.i(
                        "Installing %s on %s",
                        apkTempFile.getAbsolutePath(), device.getSerialNumber());
                final String result = device.installPackage(apkTempFile, false);
                if (result != null) {
                    CLog.e("Unable to install utility: %s", result);
                    return false;
                }
            } catch (IOException e) {
                CLog.e("Failed to unpack utility: %s", e.getMessage());
                CLog.e(e);
                return false;
            } finally {
                FileUtil.deleteFile(apkTempFile);
            }
            return true;
        }
    }

    @Override
    public Set<String> reportDependencies() {
        Set<String> deps = new HashSet<>();
        deps.add(UTIL_APK_NAME);
        return deps;
    }
}
