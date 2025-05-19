/*
 * Copyright (C) 2010 The Android Open Source Project
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
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.DeviceFailedToBootError;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.targetprep.WaitForDeviceDatetimePreparer;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.SearchArtifactUtil;

import com.google.android.tradefed.util.GoogleAccountUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A {@link ITargetPreparer} that supports configuring google accounts. If no account is specified
 * and gaia account creation is enable, then a gaia account will be created and added to the device.
 */
@OptionClass(alias = "google-account")
public class GoogleAccountPreparer extends WaitForDeviceDatetimePreparer {
    @Option(
            name = "initial-wait-time",
            description =
                    "initial wait in ms between failed add account attempts. Wait time will"
                            + " increase quadratically for each attempt")
    private long mInitialWaitTimeMs = 2000;

    @Option(name = "max-attempts", description = "maximum number of add account attempts.")
    private int mMaxAttempts = 5;

    @SuppressWarnings("unused") // kept for backwards compatibility
    @Deprecated
    @Option(
            name = "wait-for-data",
            description =
                    "wait for data connectivity before adding account."
                            + " (deprecated, data connection no longer verified here)")
    private boolean mWaitForData = false;

    @Option(
            name = "account-name",
            description = "the full name of Google account to configure.",
            importance = Importance.IF_UNSET)
    private String mAccountName = null;

    @Option(
            name = "account-password",
            description =
                    "the password of the Google account "
                            + "(specified in account-name) to configure.",
            importance = Importance.IF_UNSET)
    private String mAccountPassword = null;

    @Option(name = "account-sync", description = "enable sync on Google account.")
    private boolean mAccountSync = false;

    @Option(
            name = "use-account-range",
            description =
                    "If set, format the provided account name "
                            + "with a random value from the provided account range.")
    private boolean mUseAccountRange = false;

    @Option(
            name = "account-range-start",
            description = "Lowest number in the range of accounts, inclusive.")
    private int mAccountRangeStart = 1;

    @Option(
            name = "account-range-end",
            description = "Highest number in the range of accounts, inclusive.")
    private int mAccountRangeEnd = 10;

    @Option(name = "account-blacklist", description = "Blacklisted numbers within range to avoid.")
    private Set<Integer> mAccountBlacklist = new HashSet<>();

    @Option(
            name = "wait-for-checkin",
            description = "wait for checkin to complete after adding Google account.")
    private boolean mWaitForCheckin = false;

    @Option(
            name = "sync-then-off",
            description = "after adding account, allow for sync then turn sync off, in secs")
    private long mSyncThenOff = 0;

    @Option(name = "remove-accounts", description = "Remove all accounts on test completion")
    private boolean mRemoveAccounts = false;

    @Option(
            name = "remove-account",
            description = "Remove account that was added on test completion")
    private boolean mRemoveAccount = false;

    @Option(name = "verify-only", description = "Only verify that an account exists on the device")
    private boolean mVerifyOnly = false;

    @Option(
            name = "wait-for-sync",
            description = "Wait for the account to finish all sync operations before proceeding.")
    private boolean mWaitForSync = false;

    @Option(
            name = "wait-for-sync-timeout",
            description = "The timeout associated with wait-for-sync in milliseconds.")
    private long mWaitForSyncTimeout = 5 * 60 * 1000;

    @Option(
            name = "wait-for-sync-poll-interval",
            description = "The polling interval associated with wait-for-sync in milliseconds.")
    private long mWaitForSyncInterval = 10 * 1000;

    // This is a best-effort option to force a sync on the device that is as complete as possible.
    // It does not ensure deterministic behavior with regards to ensuring completion of all syncs.
    @Option(
            name = "force-account-sync",
            description = "Force a complete sync of the account on all available sync adapters.")
    private boolean mForceAccountSync = false;

    @Option(
            name = "credentials-file",
            description =
                    "Json file containing credentaial information that will be used to supply"
                        + " maching options including: account-name, account-password,"
                        + " use-account-range, account-range-start, account-range-end,"
                        + " account-blacklist. All fields in this file are optional. Provided value"
                        + " will override already set options.")
    private File mCredentialFile = null;

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException, BuildError {
        // don't run this preparer if it's disabled.
        if (isDisabled()) {
            return;
        }

        IBuildInfo buildInfo = testInfo.getBuildInfo();

        processCredentialFile(buildInfo);

        // Infer if we should update the account name to reflect monkey-style.
        if (mUseAccountRange) {
            if (mAccountRangeStart >= mAccountRangeEnd) {
                throw new TargetSetupError("Start of range must be less than end");
            }

            List<Integer> accountNumbers = new ArrayList<>();
            for (int i = mAccountRangeStart; i <= mAccountRangeEnd; i++) {
                if (!mAccountBlacklist.contains(i)) {
                    accountNumbers.add(i);
                }
            }

            if (accountNumbers.isEmpty()) {
                throw new TargetSetupError("No accounts in range to be added.");
            }

            int index = new Random().nextInt(accountNumbers.size());
            mAccountName = String.format(mAccountName, accountNumbers.get(index));
        }

        ITestDevice device = testInfo.getDevice();

        // Ignore pre-checks and pre-setup if in verify-only mode.
        if (!mVerifyOnly) {
            if (mAccountName == null && mAccountPassword == null) {
                // Keeping the previous behavior for backward compatibility
                CLog.i("No account name or password specified, skipping GooglePreparer.");
                return;
            }
            // throw for the error state where account name is defined and account password is not
            if (mAccountName != null && mAccountPassword == null) {
                throw new TargetSetupError(
                        "account-password is not defined", device.getDeviceDescriptor());
            }

            // Force setup errors for preparing Google accounts if the datetime is not properly set,
            // because a synced datetime is required to successfully add a Google account.
            setForceSetupError(true);

            buildInfo.addBuildAttribute("account", mAccountName);

            super.setUp(testInfo);
        }

        if (!GoogleAccountUtil.installUtil(device)) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to install account util on device %s",
                            device.getSerialNumber()),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.APK_INSTALLATION_FAILED);
        }

        try {
            if (mVerifyOnly) {
                if (GoogleAccountUtil.hasAccount(device, device.getPrimaryUserId())) {
                    return;
                } else {
                    throw new TargetSetupError(
                            "Unable to verify any accounts on device.",
                            device.getDeviceDescriptor(),
                            InfraErrorIdentifier.GOOGLE_ACCOUNT_SETUP_FAILED);
                }
            }

            // remove accounts first just in case
            if (mRemoveAccounts
                    && !GoogleAccountUtil.removeAccountAttempt(
                            device, device.getPrimaryUserId(), mAccountName)) {
                throw new TargetSetupError(
                        String.format(
                                "Failed to remove accounts on device %s", device.getSerialNumber()),
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.GOOGLE_ACCOUNT_SETUP_FAILED);
            }

            if (!addAccount(device)) {
                throw new TargetSetupError(
                        String.format(
                                "Failed to add account %s on device %s after %d attempts",
                                mAccountName, device.getSerialNumber(), mMaxAttempts),
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.GOOGLE_ACCOUNT_SETUP_FAILED);
            }

            if (mWaitForSync
                    && !GoogleAccountUtil.waitForAccountSync(
                            device,
                            device.getPrimaryUserId(),
                            mWaitForSyncInterval,
                            mWaitForSyncTimeout,
                            mForceAccountSync)) {
                CLog.w("Not all sync operations are completed before continuing.");
            }
        } finally {
            GoogleAccountUtil.uninstallUtil(device);
        }
    }

    /** Sets the Google account to create. */
    public void setAccount(String accountName, String accountPassword) {
        mAccountName = accountName;
        mAccountPassword = accountPassword;
    }

    /** Sets whether to enable sync on the account */
    public void setAccountSync(boolean value) {
        mAccountSync = value;
    }

    /** Sets whether to wait for checkin to complete after adding account */
    public void setWaitForCheckin(boolean value) {
        mWaitForCheckin = value;
    }

    /** Sets whether to enable sync on the account */
    public void setSyncThenOff(long syncThenOff) {
        mSyncThenOff = syncThenOff;
    }

    /**
     * Add account specified in options to device, attempting multiple times if necessary. Does
     * nothing if no account is specified.
     *
     * <p>Exposed for unit testing.
     *
     * @param device the {@link ITestDevice}
     * @return <code>true</code> if account was created successfully, <code>false</code> otherwise
     * @throws TargetSetupError
     */
    boolean addAccount(ITestDevice device) throws DeviceNotAvailableException, TargetSetupError {
        CLog.i("Adding account %s on device %s", mAccountName, device.getSerialNumber());
        for (int i = 0; i < mMaxAttempts; i++) {
            if (GoogleAccountUtil.addAccountAttempt(
                    device,
                    device.getPrimaryUserId(),
                    mAccountName,
                    mAccountPassword,
                    mAccountSync,
                    mWaitForCheckin)) {
                if (mSyncThenOff != 0 && mAccountSync) {
                    // if sync and off is set and the sync is On on the account
                    if (!GoogleAccountUtil.syncAndOff(
                            device, device.getPrimaryUserId(), mAccountName, mSyncThenOff)) {
                        CLog.w("Failed to turn off sync");
                    }
                }
                return true;
            }

            // pause a short, escalating time before retrying
            long increaseFactor = (long) Math.pow(2, i);
            getRunUtil().sleep(mInitialWaitTimeMs * increaseFactor);
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (isDisabled() || mVerifyOnly) {
            return;
        }
        if (e instanceof DeviceNotAvailableException || e instanceof DeviceFailedToBootError) {
            return;
        }
        if (mAccountName == null && mAccountPassword == null) {
            return;
        }
        removeAccount(testInfo.getDevice());
    }

    /**
     * Remove the account from the device provided. Remove all accounts from the device if
     * --remove-accounts flag is on.
     *
     * @param device target to remove the account(s)
     * @throws DeviceNotAvailableException
     */
    void removeAccount(ITestDevice device) throws DeviceNotAvailableException {
        if (mRemoveAccounts || mRemoveAccount) {
            if (!GoogleAccountUtil.installUtil(device)) {
                // cannot remove accounts! Bad things may happen, just remove device from queue for
                // now
                throw new DeviceNotAvailableException(
                        String.format(
                                "Failed to install account util on device %s",
                                device.getSerialNumber()),
                        device.getSerialNumber(),
                        DeviceErrorIdentifier.APK_INSTALLATION_FAILED);
            }
            try {
                if (mRemoveAccounts) {
                    if (!GoogleAccountUtil.removeAllAccountAttempt(
                            device, device.getPrimaryUserId())) {
                        // cannot remove accounts! Bad things may happen
                        // just remove device from queue for now
                        throw new DeviceNotAvailableException(
                                String.format(
                                        "Failed to remove accounts on device %s",
                                        device.getSerialNumber()),
                                device.getSerialNumber(),
                                InfraErrorIdentifier.GOOGLE_ACCOUNT_SETUP_FAILED);
                    }
                } else {
                    if (mRemoveAccount) {
                        if (!GoogleAccountUtil.removeAccountAttempt(
                                device, device.getPrimaryUserId(), mAccountName)) {
                            // cannot remove accounts! Bad things may happen
                            // just remove device from queue for now
                            throw new DeviceNotAvailableException(
                                    String.format(
                                            "Failed to remove accounts on device %s",
                                            device.getSerialNumber()),
                                    device.getSerialNumber(),
                                    InfraErrorIdentifier.GOOGLE_ACCOUNT_SETUP_FAILED);
                        }
                    }
                }
            } finally {
                GoogleAccountUtil.uninstallUtil(device);
            }
        }
    }

    void setRemoveAccount(boolean b) {
        mRemoveAccount = b;
    }

    protected String getAccountName() {
        return mAccountName;
    }

    protected String getAccountPassword() {
        return mAccountPassword;
    }

    protected File getCredentialFile() {
        return mCredentialFile;
    }

    protected void setCredentialFile(File file) {
        mCredentialFile = file;
    }

    protected boolean getUseAccountRange() {
        return mUseAccountRange;
    }

    protected int getAccountRangeStart() {
        return mAccountRangeStart;
    }

    protected int getAccountRangeEnd() {
        return mAccountRangeEnd;
    }

    protected Set<Integer> getAccountBlacklist() {
        return mAccountBlacklist;
    }

    protected void processCredentialFile(IBuildInfo buildInfo) throws TargetSetupError {
        if (mCredentialFile == null) {
            return; // Skip if no credential file was provided.
        }

        if (!mCredentialFile.isAbsolute()) {
            mCredentialFile = resolveRelativeFilePath(buildInfo, mCredentialFile.getPath());
        }

        if (!mCredentialFile.exists() || !mCredentialFile.canRead()) {
            throw new TargetSetupError("Credential file could not be accessed.");
        }

        // Override options specified in the credential file.
        JSONObject credentialJson = loadCredentialsFromFile(mCredentialFile);
        Iterator<String> credentialsIterator = credentialJson.keys();
        try {
            while (credentialsIterator.hasNext()) {
                String optionName = credentialsIterator.next();
                switch (optionName) {
                    case "account-name":
                        String accountName = credentialJson.getString(optionName);
                        mAccountName = accountName;
                        break;
                    case "account-password":
                        String accountPassword = credentialJson.getString(optionName);
                        mAccountPassword = accountPassword;
                        break;
                    case "use-account-range":
                        Boolean useAccountRange = credentialJson.getBoolean(optionName);
                        mUseAccountRange = useAccountRange;
                        break;
                    case "account-range-start":
                        int accountRangeStart = credentialJson.getInt(optionName);
                        mAccountRangeStart = accountRangeStart;
                        break;
                    case "account-range-end":
                        int accountRangeEnd = credentialJson.getInt(optionName);
                        mAccountRangeEnd = accountRangeEnd;
                        break;
                    case "account-blacklist":
                        Set<Integer> accountBlacklist = new HashSet<>();
                        JSONArray accountBlacklistArray = credentialJson.getJSONArray(optionName);
                        for (int i = 0; i < accountBlacklistArray.length(); i++) {
                            accountBlacklist.add(accountBlacklistArray.getInt(i));
                        }
                        mAccountBlacklist = accountBlacklist;
                        break;
                    default:
                        throw new TargetSetupError(
                                String.format("Unrecognized credential key: %s", optionName));
                }
            }
        } catch (JSONException e) {
            // Error meesage not included to avoid leaking password in the logs.
            throw new TargetSetupError("Invalid data format in JSON file.");
        }
    }

    protected JSONObject loadCredentialsFromFile(File file) throws TargetSetupError {
        JSONObject credentialJson = null;
        try {
            String data = FileUtil.readStringFromFile(file);
            credentialJson = new JSONObject(data);
        } catch (IOException e) {
            throw new TargetSetupError(
                    String.format("Failed to read JSON key file %s: %s", file.toString(), e));
        } catch (JSONException e) {
            throw new TargetSetupError(
                    String.format("Failed to parse JSON data from file %s", file.toString()), e);
        }
        return credentialJson;
    }

    protected File resolveRelativeFilePath(IBuildInfo buildInfo, String fileName)
            throws TargetSetupError {
        File fileAbsPaths;
        try {
            fileAbsPaths = SearchArtifactUtil.searchFile(fileName, false);
        } catch (Exception e) {
            throw new TargetSetupError(
                    String.format("Could not located the file: %s.", fileName), e);
        }
        return fileAbsPaths;
    }
}
