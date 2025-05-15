/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.tradefed.util;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.ResourceUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.IOException;

/** A Utility that supports configuring google accounts on a device */
public class GoogleAccountUtil {
    private static final String ADD_ACCOUNT_INSTRUMENTATION_CLASS = ".AddAccount";
    private static final String HAS_ACCOUNT_INSTRUMENTATION_CLASS = ".HasAccount";
    private static final String REMOVE_ACCOUNTS_INSTRUMENTATION_CLASS = ".RemoveAccounts";
    private static final String REMOVE_ACCOUNT_INSTRUMENTATION_CLASS = ".RemoveAccount";
    private static final String GOOGLE_ACCOUNT_SYNC_INSTRUMENTATION_CLASS = ".GoogleAccountSync";

    private static final String GOOGLE_ACCOUNT_SYNC_COMMAND =
            "-w -e interval %d -e timeout %d -e force-account-sync %b %s/%s";

    public static final String ACCOUNT_PKG_NAME = "com.google.android.tradefed.account";
    public static final String CHECK_INSTRUMENTATION_CMD =
            String.format("pm list instrumentation %s", ACCOUNT_PKG_NAME);
    public static final String UTIL_APK_NAME = "GoogleAccountUtil";

    private static final String UTIL_APK_PATH = "/GoogleAccountUtil.apk";
    private static final String UTIL_APK_PATH_FALLBACK =
            "/com/google/android/tradefed/account/GoogleAccountUtil.apk";

    /**
     * Install the account utility apk contained in jar if necessary
     *
     * @param device on which to install the utility.
     * @throws DeviceNotAvailableException
     */
    public static boolean installUtil(ITestDevice device) throws DeviceNotAvailableException {
        if (isAccountUtilInstalled(device)) {
            return true;
        } else {
            // Attempt to install utility
            File apkTempFile = null;
            try {
                apkTempFile = FileUtil.createTempFile(UTIL_APK_NAME, ".apk");
                boolean res =
                        ResourceUtil.extractResourceWithAltAsFile(
                                UTIL_APK_PATH, UTIL_APK_PATH_FALLBACK, apkTempFile);
                if (!res) {
                    CLog.e("Failed to unpack AccountUtil utility");
                    return false;
                }
                CLog.i("Installing %s on %s", UTIL_APK_NAME, device.getSerialNumber());
                final String result = device.installPackage(apkTempFile, false);
                if (result != null) {
                    CLog.e("Unable to install AccountUtil utility: %s", result);
                    return false;
                }
            } catch (IOException e) {
                CLog.e("Failed to unpack AccountUtil utility: %s", e.getMessage());
                CLog.e(e);
                return false;
            } finally {
                FileUtil.deleteFile(apkTempFile);
            }
            return isAccountUtilInstalled(device);
        }
    }

    /**
     * Uninstall the account utility apk contained in jar
     *
     * @param device to uninstall the account utility from.
     * @throws DeviceNotAvailableException
     */
    public static boolean uninstallUtil(ITestDevice device) throws DeviceNotAvailableException {
        if (device.uninstallPackage(ACCOUNT_PKG_NAME) != null) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * Check if the account utility is currently installed
     *
     * @param device on which to check if the account utility is installed
     * @throws DeviceNotAvailableException
     */
    public static boolean isAccountUtilInstalled(ITestDevice device)
            throws DeviceNotAvailableException {
        final String inst = device.executeShellCommand(CHECK_INSTRUMENTATION_CMD);
        if ((inst != null) && inst.contains(ACCOUNT_PKG_NAME)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Check if there is an account on the device.
     *
     * @param device the {@link ITestDevice} checked for an account
     * @param userId the user ID id to check
     * @throws DeviceNotAvailableException
     */
    public static boolean hasAccount(ITestDevice device, int userId)
            throws DeviceNotAvailableException {
        String hasAccountCmd =
                buildInstrumentationCmdForUser(
                        device,
                        userId,
                        String.format(
                                "-w %s/%s", ACCOUNT_PKG_NAME, HAS_ACCOUNT_INSTRUMENTATION_CLASS));
        final String inst = device.executeShellCommand(hasAccountCmd);
        // expected result format on success:
        //
        // INSTRUMENTATION_RESULT: result=SUCCESS
        // INSTRUMENTATION_CODE: -1
        if (inst.contains("result=SUCCESS")) {
            return true;
        } else {
            CLog.e(
                    "Failed to find registered Google account on %s. Command output: %s",
                    device.getSerialNumber(), inst);
            return false;
        }
    }

    /**
     * Builds the instrumentation command to add the account
     *
     * @param accountName name of the account to be targeted by the command
     * @param password associated with the account
     * @param sync flag to enable account sync after log in with the account
     * @param waitForCheckin true to enable wait for checking after adding the account
     * @param userId the user ID of the user profile to add the account to
     */
    private static String buildAddAccountCmd(
            String accountName,
            String password,
            boolean sync,
            boolean waitForCheckin,
            int userId,
            ITestDevice device)
            throws DeviceNotAvailableException {
        return buildInstrumentationCmdForUser(
                device,
                userId,
                String.format(
                        "-w -e account \"%s\" -e password \"%s\" -e sync %s "
                                + "-e wait-for-checkin %s %s/%s",
                        accountName,
                        password,
                        Boolean.toString(sync),
                        Boolean.toString(waitForCheckin),
                        ACCOUNT_PKG_NAME,
                        ADD_ACCOUNT_INSTRUMENTATION_CLASS));
    }

    /**
     * Attempt to configures device under test with given Google account.
     *
     * <p>Account must already exist on server.
     *
     * <p>Exposed for unit testing.
     *
     * @param device the {@link ITestDevice}
     * @param userId the ID of user to which the account will be added
     * @param accountName full name of Google account to add
     * @param password password of account
     * @param sync <code>true</code> if automatic sync should be enabled for this account
     * @param waitForCheckin <code>true</code> to wait for checkin after adding account
     * @return <code>true</code> if account was created successfully, <code>false</code> otherwise
     */
    public static boolean addAccountAttempt(
            ITestDevice device,
            int userId,
            String accountName,
            String password,
            boolean sync,
            boolean waitForCheckin)
            throws DeviceNotAvailableException {
        String instrCmd =
                buildAddAccountCmd(accountName, password, sync, waitForCheckin, userId, device);
        return runAddAccountInstrumentation(device, userId, instrCmd, accountName);
    }

    /** Utility function for running the AddAccount instrumentation */
    private static boolean runAddAccountInstrumentation(
            ITestDevice device, int userId, String instrCmd, String accountName)
            throws DeviceNotAvailableException {
        CLog.v(instrCmd);
        String result = device.executeShellCommand(instrCmd);
        // expected result format is (on error):
        //
        // INSTRUMENTATION_RESULT: result=SUCCESS
        // INSTRUMENTATION_CODE: -1
        //
        // where errorCode will be present only on failure
        if (result.contains("result=SUCCESS")) {
            return true;
        } else {
            reportFailure(device, userId, accountName, "Failed to add account: " + result);
            return false;
        }
    }

    /** Builds the instrumentation command to turn off sync */
    private static String buildSyncOffCmd(ITestDevice device, int userId, String accountName)
            throws DeviceNotAvailableException {
        return buildInstrumentationCmdForUser(
                device,
                userId,
                String.format(
                        "-w -e account \"%s\" -e sync false %s/%s",
                        accountName, ACCOUNT_PKG_NAME, ADD_ACCOUNT_INSTRUMENTATION_CLASS));
    }

    /** Builds the instrumentation command to turn off sync */
    private static String buildSyncOnCmd(ITestDevice device, int userId, String accountName)
            throws DeviceNotAvailableException {
        return buildInstrumentationCmdForUser(
                device,
                userId,
                String.format(
                        "-w -e account \"%s\" -e sync true %s/%s",
                        accountName, ACCOUNT_PKG_NAME, ADD_ACCOUNT_INSTRUMENTATION_CLASS));
    }

    private static void reportFailure(
            ITestDevice device, int userId, String accountName, String reason)
            throws DeviceNotAvailableException {
        CLog.w(
                "Failed to add account %s to user %d(%s) on device %s: %s",
                accountName,
                userId,
                device.getUserInfos().get(userId).userName(),
                device.getSerialNumber(),
                reason);
    }

    private static void reportRemoveFailure(ITestDevice device, int userId, String reason)
            throws DeviceNotAvailableException {
        CLog.w(
                "Failed to remove accounts from user %d(%s) on device %s: %s",
                userId,
                device.getUserInfos().get(userId).userName(),
                device.getSerialNumber(),
                reason);
    }

    /** Builds the instrumentation command for a user. */
    private static String buildInstrumentationCmdForUser(ITestDevice device, int userId, String cmd)
            throws DeviceNotAvailableException {
        // Only specify the user for the work profile.
        return String.format(
                "am instrument %s%s",
                device.getPrimaryUserId().equals(userId) ? "" : String.format("--user %d ", userId),
                cmd);
    }

    /**
     * Remove the single account specified
     *
     * @param device
     * @param userId
     * @return true if removal is a success, false otherwise.
     * @throws DeviceNotAvailableException
     */
    public static boolean removeAccountAttempt(ITestDevice device, int userId, String accountName)
            throws DeviceNotAvailableException {
        String removeCmd =
                buildInstrumentationCmdForUser(
                        device,
                        userId,
                        String.format(
                                "-w -e account \"%s\" %s/%s",
                                accountName,
                                ACCOUNT_PKG_NAME,
                                REMOVE_ACCOUNT_INSTRUMENTATION_CLASS));

        CLog.v(removeCmd);
        String result = device.executeShellCommand(removeCmd);
        // expected result format is (on success):
        //
        // INSTRUMENTATION_RESULT: result=SUCCESS
        // INSTRUMENTATION_CODE: -1
        //
        if (result.contains("result=SUCCESS")) {
            return true;
        } else {
            reportRemoveFailure(device, userId, result);
            return false;
        }
    }

    /**
     * Remove all accounts of the device
     *
     * @param device
     * @param userId
     * @return true if removal is a success, false otherwise.
     * @throws DeviceNotAvailableException
     */
    public static boolean removeAllAccountAttempt(ITestDevice device, int userId)
            throws DeviceNotAvailableException {
        String removeCmd =
                buildInstrumentationCmdForUser(
                        device,
                        userId,
                        String.format(
                                "-w %s/%s",
                                ACCOUNT_PKG_NAME, REMOVE_ACCOUNTS_INSTRUMENTATION_CLASS));

        CLog.v(removeCmd);
        String result = device.executeShellCommand(removeCmd);
        // expected result format is (on success):
        //
        // INSTRUMENTATION_RESULT: result=SUCCESS
        // INSTRUMENTATION_CODE: -1
        //
        if (result.contains("result=SUCCESS")) {
            return true;
        } else {
            reportRemoveFailure(device, userId, result);
            return false;
        }
    }

    /**
     * Turns off syncing with the given Google account. Assume syncing is On.
     *
     * @param device the {@link ITestDevice}
     * @param userId the ID of the user to run this operation on
     * @param accountName full name of Google account to turn sync off
     * @param syncThenOff seconds to allow sync to run before turning it off
     * @return <code>true</code> if the sync was turned off successfully
     * @throws DeviceNotAvailableException
     */
    public static boolean syncAndOff(
            ITestDevice device, int userId, String accountName, long syncThenOff)
            throws DeviceNotAvailableException {
        CLog.i("Sleeping for %d seconds to allow for syncing...", syncThenOff);
        getRunUtil().sleep(syncThenOff * 1000);
        CLog.i("Done sleeping. Turning sync off...");
        String instrCmd = buildSyncOffCmd(device, userId, accountName);
        return runAddAccountInstrumentation(device, userId, instrCmd, accountName);
    }

    /**
     * Turns on syncing with the given Google account. Assume syncing is false;
     *
     * @param device the {@link ITestDevice}
     * @param userId the ID of the user to run this operation on
     * @param accountName full name of Google account to turn sync on
     * @return <code>true</code> if the sync was turned off successfully
     * @throws DeviceNotAvailableException
     */
    public static boolean syncOn(ITestDevice device, int userId, String accountName)
            throws DeviceNotAvailableException {
        String instrCmd = buildSyncOnCmd(device, userId, accountName);
        return runAddAccountInstrumentation(device, userId, instrCmd, accountName);
    }

    /** Wait for the device to finish syncing. */
    public static boolean waitForAccountSync(
            ITestDevice device, int userId, long interval, long timeout, boolean forceSync)
            throws DeviceNotAvailableException {
        String instrCmd =
                String.format(
                        buildInstrumentationCmdForUser(device, userId, GOOGLE_ACCOUNT_SYNC_COMMAND),
                        interval,
                        timeout,
                        forceSync,
                        ACCOUNT_PKG_NAME,
                        GOOGLE_ACCOUNT_SYNC_INSTRUMENTATION_CLASS);
        CLog.v(instrCmd);
        String result = device.executeShellCommand(instrCmd);
        // Success result format:
        //
        // INSTRUMENTATION_RESULT: result=SUCCESS
        // INSTRUMENTATION_CODE: -1
        //
        if (result.contains("result=SUCCESS")) {
            return true;
        } else {
            reportRemoveFailure(device, userId, result);
            return false;
        }
    }

    /**
     * @return the {@link IRunUtil} to use
     */
    private static IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }
}
