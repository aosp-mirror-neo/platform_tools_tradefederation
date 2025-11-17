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
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestLoggerReceiver;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.util.BinaryState;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.common.base.VerifyException;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Enable Ramdump. Need unlocked bootloader and userdebug build (root access). */
@OptionClass(alias = "ramdump-preparer")
public class RamdumpPreparer extends BaseTargetPreparer implements ITestLoggerReceiver {

    private static final int RAMDUMP_WAIT_TIME_SECS = 10;
    private static final int RETRY_COUNT = 6;
    private static final int RETRY_WAIT_TIME_MS = 10 * 1000;

    private static final int ONE_SECOND_IN_MILLIS = 1000;
    private static final int ONE_MINUTE_IN_SECONDS = 60;
    private static final int RAMDUMP_PULL_TIMEOUT_IN_MINUTES = 25;
    private static final int RAMDUMP_PULL_TIMEOUT_IN_MS =
            RAMDUMP_PULL_TIMEOUT_IN_MINUTES * ONE_MINUTE_IN_SECONDS * ONE_SECOND_IN_MILLIS;

    private static final String RAMDUMP_STATUS_PATH = "/data/vendor/ramdump/RAMDUMP_STATUS";
    private static final String RAMDUMP_RESERVED_PATH = "/data/vendor/ramdump/RAMDUMP_RESERVED";
    private static final String RAMDUMP_FOLDER_PATH = "/mnt/vendor/ramdump/";
    private static final String RAMDUMP_OUTPUT_PATH = "/mnt/vendor/ramdump/RAMDUMP_OUTPUT";
    private static final String PROPERTY_HARDWARE_PLATFORM = "ro.boot.hardware.platform";
    private static final String PROPERTY_RAMDUMP_STATUS = "vendor.debug.ramdump.status";
    private static final String RAMDUMP_STATUS_NONE = "none";
    private static final String RAMDUMP_STATUS_DONE = "done";
    private static final String RAMDUMP_STATUS_FAIL = "fail";

    private ITestLogger mTestLogger;
    private String mDeviceSerialNumber;

    @Option(name = "set-ramdump", description = "enable or disable ramdump")
    private BinaryState mSetRamdump = BinaryState.IGNORE;

    // ON:  Ramdump enable
    // OFF: Ramdump disable

    @Option(
            name = "RAMDUMP_RESERVED-timeout",
            description =
                    "default time in minute "
                            + "to wait for the RAMDUMP_RESERVED file is generated")
    private long mRamdumpReservedTimeoutMins = 4;

    @Option(
            name = "ramdump-mode-wait-time",
            description =
                    "default time in minute to wait for device to become available "
                            + "after entering ramdump mode and reboot")
    private long mRamdumpModeWaitTimeMins = 5;

    @Option(
            name = "capture-bugreport",
            description = "Capture the bugreport before disabling ramdump and rebooting the device")
    private boolean mCaptureBugreport = false;

    @Override
    public void setUp(TestInformation testInfo) throws DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        mDeviceSerialNumber = device.getSerialNumber();
        switch (mSetRamdump) {
            case ON:
                ramdumpEnable(device);
                break;
            case OFF:
                ramdumpDisable(device);
                break;
            case IGNORE:
                // No-op
                break;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setTestLogger(ITestLogger testLogger) {
        mTestLogger = testLogger;
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        if (!BinaryState.ON.equals(mSetRamdump)) {
            return;
        }

        if (isDeviceInRamdumpMode(device)) {
            CLog.i("The device is in Ramdump mode, waiting for the device to be available.");
            // Wait for device to become available after entering ramdump mode and reboot.
            device.waitForDeviceAvailable(mRamdumpModeWaitTimeMins * 60 * 1000);
        }

        if (e instanceof DeviceNotAvailableException) {
            CLog.i("Device is not available, skipping.");
            return;
        }

        if (!isRamdumpExist(device)) {
            CLog.i("Ramdump in %s does not exist, skipping.", RAMDUMP_FOLDER_PATH);
        } else {
            // Pull ramdump log
            File tempDir = null;
            try {
                tempDir = FileUtil.createTempDir("ramdump-pulled-dir");

                TestDeviceOptions deviceOptions = device.getOptions();
                long origAdbCommandTimeout = deviceOptions.getAdbCommandTimeout();
                deviceOptions.setAdbCommandTimeout(RAMDUMP_PULL_TIMEOUT_IN_MS);

                boolean pullStatus = false;
                try {
                    pullStatus = device.pullDir(RAMDUMP_FOLDER_PATH, tempDir);
                } finally {
                    deviceOptions.setAdbCommandTimeout(origAdbCommandTimeout);
                }

                if (!pullStatus) {
                    CLog.w(
                            "Failed to pull directory %s from device %s",
                            RAMDUMP_FOLDER_PATH, mDeviceSerialNumber);
                } else {
                    List<File> ramdumpLogs = Arrays.asList(tempDir.listFiles());
                    File zip = ZipUtil.createZip(ramdumpLogs);
                    try (InputStreamSource s = new FileInputStreamSource(zip, true)) {
                        mTestLogger.testLog(
                                "ramdump_teardown_" + mDeviceSerialNumber, LogDataType.ZIP, s);
                    }
                }
            } catch (IOException ioe) {
                throw new VerifyException("exception while saving device directory", ioe);
            } finally {
                FileUtil.recursiveDelete(tempDir);
            }
        }

        if (mCaptureBugreport) {
            InputStreamSource bugreport = device.getBugreportz();
            mTestLogger.testLog(
                    "bugreport_teardown_" + mDeviceSerialNumber, LogDataType.BUGREPORTZ, bugreport);
            bugreport.close();
        }

        // Disable ramdump after the test is complete.
        ramdumpDisable(device);
    }

    // Check the ramdump status every {@code RAMDUMP_WAIT_TIME_SECS} seconds
    private boolean ramdumpStatusCheck(long mRamdumpTimeoutMins, ITestDevice device)
            throws DeviceNotAvailableException {
        device.enableAdbRoot();
        // {@code TimeUnit.MINUTES.toSeconds(1) / RAMDUMP_WAIT_TIME_SECS} times per minute
        int mRamdumpCheckCycle =
                (int) TimeUnit.MINUTES.toSeconds(mRamdumpTimeoutMins) / RAMDUMP_WAIT_TIME_SECS;
        boolean keepWaiting = true;
        boolean isNormal = false;
        for (int i = 0; i < mRamdumpCheckCycle; i++) {
            String state = device.getProperty(PROPERTY_RAMDUMP_STATUS);

            if (RAMDUMP_STATUS_NONE.equals(state)) {
                keepWaiting = false;
                isNormal = true;
            } else if (RAMDUMP_STATUS_DONE.equals(state)) {
                keepWaiting = false;
                isNormal = true;
            } else if (RAMDUMP_STATUS_FAIL.equals(state)) {
                keepWaiting = false;
            }

            String nextAction = keepWaiting ? "keep waiting" : "finished";
            CLog.d("%s: %s, %s", PROPERTY_RAMDUMP_STATUS, state, nextAction);

            if (keepWaiting) {
                try {
                    TimeUnit.SECONDS.sleep(RAMDUMP_WAIT_TIME_SECS);
                } catch (InterruptedException e) {
                    CLog.e("Sleep Interrupted");
                }
            } else {
                break;
            }
        }
        return isNormal;
    }

    private void ramdumpEnable(ITestDevice device) throws DeviceNotAvailableException {
        String hardwarePlatform = device.getProperty(PROPERTY_HARDWARE_PLATFORM);
        CLog.i("Hardware Platform is: %s", hardwarePlatform);
        CLog.i("Enable Ramdump from HLOS, need to reboot device to take effect.");
        device.enableAdbRoot();
        device.executeShellV2Command("rm " + RAMDUMP_RESERVED_PATH);
        device.executeShellV2Command("echo enable > " + RAMDUMP_STATUS_PATH);
        CLog.i("Enable Ramdump from Bootloader.");
        device.rebootIntoBootloader();
        device.executeFastbootCommand(new String[] {"oem", "ramdump", "enable"});
        if (hardwarePlatform.startsWith("gs")) {
            CLog.i("Disable ramdump encryption and enable compression for WHI product");
            device.executeFastbootCommand(
                    new String[] {"oem", "ramdump", "encryption", "disable-with-wipe"});
            device.executeFastbootCommand(new String[] {"oem", "ramdump", "compression", "enable"});
        }
        device.executeFastbootCommand("reboot");
        device.waitForDeviceAvailable();

        // Wait for RAMDUMP_RESERVED file is generated
        boolean isRamdumpEnableNormal = ramdumpStatusCheck(mRamdumpReservedTimeoutMins, device);
        if (!isRamdumpEnableNormal) {
            CLog.i("%s file is not generated normally", RAMDUMP_RESERVED_PATH);
        }
    }

    private void ramdumpDisable(ITestDevice device) throws DeviceNotAvailableException {
        CLog.i("Disable Ramdump from HLOS, need to reboot device to take effect.");
        device.enableAdbRoot();
        device.executeShellV2Command("echo disable > " + RAMDUMP_STATUS_PATH);
        CLog.i("Disable Ramdump from Bootloader.");
        device.rebootIntoBootloader();
        device.executeFastbootCommand(new String[] {"oem", "ramdump", "disable"});
        device.executeFastbootCommand("reboot");
        device.waitForDeviceAvailable();
        device.enableAdbRoot();
    }

    private Boolean isRamdumpExist(ITestDevice device) throws DeviceNotAvailableException {
        device.enableAdbRoot();
        for (int i = 0; i < RETRY_COUNT; i++) {
            if (device.doesFileExist(RAMDUMP_OUTPUT_PATH)) {
                return true;
            }
            CLog.i("Wait 10 sec for RAMDUMP_OUTPUT file is generated.");
            RunUtil.getDefault().sleep(RETRY_WAIT_TIME_MS);
        }
        return false;
    }

    private Boolean isDeviceInRamdumpMode(ITestDevice device) throws DeviceNotAvailableException {
        String checkFastbootDevices = device.executeFastbootCommand("devices").getStdout();
        List<String> deviceList = Arrays.asList(checkFastbootDevices.split("\\r?\\n"));
        for (String deviceStatus : deviceList) {
            if (deviceStatus.contains(mDeviceSerialNumber) && deviceStatus.contains("ramdump")) {
                return true;
            }
        }
        return false;
    }
}
