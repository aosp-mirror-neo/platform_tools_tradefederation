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

package com.google.android.tradefed.account;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.SyncAdapterType;
import android.content.SyncInfo;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A utility for Google account syncing
 */
public class GoogleAccountSync extends Instrumentation {
    private static final String LOG_TAG = GoogleAccountSync.class.getSimpleName();

    // Option: long to indicate the sync-checking interval. Visible for testing.
    static final String PARAM_INTERVAL = "interval";
    // Option: total timeout to quit sync-checking. Visible for testing.
    static final String PARAM_TIMEOUT = "timeout";
    // Option: force a sync on all sync authorities. Visible for testing.
    static final String PARAM_FORCE_SYNC = "force-account-sync";
    // TODO(b/130175486): Explore additional options such as whitelists for more deterministic
    // behavior.

    // Keys for Bundle messages
    private static final String BUNDLE_RESULT_KEY = "result";
    private static final String BUNDLE_MESSAGE_KEY = "message";
    // Values for Bundle messages
    private static final String BUNDLE_ERROR_MESSAGE = "ERROR";
    private static final String BUNDLE_SUCCESS_MESSAGE = "SUCCESS";

    // Small sleep value used to ensure that the system won't sleep for <=0 msecs when polling.
    private static final long SHORT_DELAY_MS = 10;

    // Account type for triggering Google account sync. Visible for testing.
    static final String ACCOUNT_TYPE_GOOGLE = "com.google";

    private long mInterval;
    private long mTimeout;
    private boolean mForceSyncAll = false;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);

        parseArguments(arguments);
        start();
    }

    /**
     * Enable tests to initialize the utility without having to trigger the {@code start()} call,
     * which would run the utility in a separate thread.
     *
     * <p>Visible for testing.
     */
    void parseArguments(Bundle arguments) {
        String intervalString = arguments.getString(PARAM_INTERVAL);
        if (intervalString != null) {
            mInterval = Long.parseLong(intervalString);
        } else {
            finishInstrumentation(Activity.RESULT_CANCELED, "No polling interval specified.");
        }

        String timeoutString = arguments.getString(PARAM_TIMEOUT);
        if (timeoutString != null) {
            mTimeout = Long.parseLong(timeoutString);
        } else {
            finishInstrumentation(Activity.RESULT_CANCELED, "No polling timeout specified.");
        }

        String forceSyncString = arguments.getString(PARAM_FORCE_SYNC);
        if (forceSyncString != null) {
            mForceSyncAll = Boolean.valueOf(forceSyncString);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        runSyncCheck();
    }

    /**
     * Poll for the sync status periodically until all the pending and active syncs are completed or
     * the operation times out.
     *
     * <p>Visible for testing.
     */
    void runSyncCheck() {
        List<SyncInfoHolder> initialPendingSyncs = getPendingSyncs(ACCOUNT_TYPE_GOOGLE);
        sendStatusMessage(
                Activity.RESULT_OK,
                String.format("Existing pending syncs : %s.", initialPendingSyncs));
        List<SyncInfoHolder> additionalPendingSyncs = new ArrayList<>();
        // If configured, trigger all syncs on the Google account.
        if (mForceSyncAll) {
            List<SyncInfoHolder> allPossibleSyncs =
                    getAllPossibleSyncsForAccountType(ACCOUNT_TYPE_GOOGLE);
            for (SyncInfoHolder sync : allPossibleSyncs) {
                if (!initialPendingSyncs.contains(sync)) {
                    sendStatusMessage(
                            Activity.RESULT_OK,
                            String.format(
                                    "Requesting sync for authority %s in account %s.",
                                    sync.authority, sync.account.name));
                    requestSync(sync.account, sync.authority, new Bundle());
                    additionalPendingSyncs.add(sync);
                }
            }
        }
        long startTime = SystemClock.uptimeMillis();
        // Loop for the interval duration or until all syncs successful.
        while (SystemClock.uptimeMillis() - startTime < mTimeout) {
            removeNoLongerPendingSyncs(initialPendingSyncs);
            removeNoLongerPendingSyncs(additionalPendingSyncs);
            List<SyncInfoHolder> activeSyncs = getCurrentSyncs();
            // If the number of polls with no active or pending syncs meets the
            // threshold, declare success and quit.
            if (activeSyncs.isEmpty()
                    && initialPendingSyncs.isEmpty()
                    && additionalPendingSyncs.isEmpty()) {
                finishInstrumentation(Activity.RESULT_OK, "All syncs completed.");
                return;
            }
            // If syncs are still active or pending, list them for debugging.
            logCurrentSyncInfo(activeSyncs, initialPendingSyncs, additionalPendingSyncs);
            // Continue sleeping until the next polling event.
            long syncPollDelay =
                    Math.min(mInterval, mTimeout - (SystemClock.uptimeMillis() - startTime));
            SystemClock.sleep(Math.max(syncPollDelay, SHORT_DELAY_MS));
        }
        // If we reach here, there are pending syncs or active syncs. Log them for debugging.
        sendStatusMessage(Activity.RESULT_OK, "Sync utility timed out.");
        List<SyncInfoHolder> activeSyncs = getCurrentSyncs();
        logCurrentSyncInfo(activeSyncs, initialPendingSyncs, additionalPendingSyncs);
        finishInstrumentation(Activity.RESULT_CANCELED, "Sync utility timed out.");
        // Cancel all additionally triggered syncs so that the forced syncs do not leave the device
        // in a different state than if it were left to sync naturally.
        if (!additionalPendingSyncs.isEmpty()) {
            sendStatusMessage(
                    Activity.RESULT_OK,
                    String.format(
                            "Cancelling still-pending forced syncs: %s.",
                            additionalPendingSyncs.toString()));
            for (SyncInfoHolder sync : additionalPendingSyncs) {
                cancelSync(sync.account, sync.authority);
            }
        }
        return;
    }

    /**
     * Get the current syncs on the system from the {@link ContentResolver} and return them in the
     * custom {@link GoogleAccountSync.SyncInfoHolder} format.
     *
     * <p>Visible for testing.
     *
     * @hide
     */
    protected List<SyncInfoHolder> getCurrentSyncs() {
        List<SyncInfo> syncInfo = getContext().getContentResolver().getCurrentSyncs();
        List<SyncInfoHolder> convertedSyncInfo = new ArrayList<>();
        for (SyncInfo item : syncInfo) {
            convertedSyncInfo.add(new SyncInfoHolder(item.account, item.authority));
        }
        return convertedSyncInfo;
    }

    /**
     * Convenience data holder class for sync info.
     *
     * <p>{@link SyncInfo} is not used here as the API is intended to be hidden.
     *
     * <p>Visible for testing.
     */
    static class SyncInfoHolder {
        public final Account account;
        public final String authority;

        public SyncInfoHolder(Account account, String authority) {
            if (account == null || authority == null) {
                throw new IllegalArgumentException("Account or sync authority should not be null");
            }
            this.account = account;
            this.authority = authority;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 31 * hash + account.hashCode();
            hash = 31 * hash + authority.hashCode();
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) {
                return false;
            }
            if (other instanceof SyncInfoHolder) {
                SyncInfoHolder otherInfo = (SyncInfoHolder) other;
                return this.account.equals(otherInfo.account)
                        && this.authority.equals(otherInfo.authority);
            }
            return false;
        }

        @Override
        public String toString() {
            return String.format("{%s: %s}", account.name, authority);
        }
    }

    /**
     * Allows tests to provide a stubbed {@link AccountManager}.
     *
     * <p>Visible for testing.
     *
     * @hide
     */
    protected AccountManager getAccountManager() {
        return AccountManager.get(getContext());
    }

    /**
     * Forwarding logic for {@link ContentResolver}'s static methods for stubbing in tests.
     *
     * <p>Visible for testing.
     *
     * @hide
     */
    protected SyncAdapterType[] getSyncAdapterTypes() {
        return ContentResolver.getSyncAdapterTypes();
    }

    /**
     * Forwarding logic for {@link ContentResolver}'s static methods for stubbing in tests.
     *
     * <p>Visible for testing.
     *
     * @hide
     */
    protected boolean isSyncPending(Account account, String authority) {
        return ContentResolver.isSyncPending(account, authority);
    }

    /**
     * Forwarding logic for {@link ContentResolver}'s static methods for stubbing in tests.
     *
     * <p>Visible for testing.
     *
     * @hide
     */
    protected void cancelSync(Account account, String authority) {
        ContentResolver.cancelSync(account, authority);
    }

    /**
     * Forwarding logic for {@link ContentResolver}'s static methods for stubbing in tests.
     *
     * <p>Visible for testing.
     *
     * @hide
     */
    protected void requestSync(Account account, String authority, Bundle extras) {
        ContentResolver.requestSync(account, authority, extras);
    }

    /** Returns all currently pending syncs in a map of accounts to list of sync authorities. */
    List<SyncInfoHolder> getPendingSyncs(String accountType) {
        List<SyncInfoHolder> pendingSyncs = new ArrayList<>();
        List<SyncAdapterType> supportedSyncAdapterTypes = getSupportedSyncAdapterTypes(accountType);
        for (Account account : getAccountManager().getAccountsByType(accountType)) {
            for (SyncAdapterType adapterType : supportedSyncAdapterTypes) {
                if (isSyncPending(account, adapterType.authority)) {
                    pendingSyncs.add(new SyncInfoHolder(account, adapterType.authority));
                }
            }
        }
        return pendingSyncs;
    }

    /**
     * Trigger a sync on the request account type on all supported {@link SyncAdapterType}s.
     *
     * <p>Visible for testing.
     *
     * @return List of additional syncs triggered by this method.
     */
    List<SyncInfoHolder> getAllPossibleSyncsForAccountType(String accountType) {
        List<SyncInfoHolder> possibleSyncs = new ArrayList<>();
        List<SyncAdapterType> supportedSyncAdapterTypes = getSupportedSyncAdapterTypes(accountType);
        for (Account account : getAccountManager().getAccountsByType(accountType)) {
            for (SyncAdapterType adapterType : supportedSyncAdapterTypes) {
                possibleSyncs.add(new SyncInfoHolder(account, adapterType.authority));
            }
        }
        return possibleSyncs;
    }

    private List<SyncAdapterType> getSupportedSyncAdapterTypes(String accountType) {
        List<SyncAdapterType> supportedSyncAdapterTypes = new ArrayList<>();
        for (SyncAdapterType adapterType : getSyncAdapterTypes()) {
            if (accountType.equals(adapterType.accountType)) {
                supportedSyncAdapterTypes.add(adapterType);
            }
        }
        return supportedSyncAdapterTypes;
    }

    private void removeNoLongerPendingSyncs(List<SyncInfoHolder> currentInfo) {
        List<SyncInfoHolder> infoToRemove = new ArrayList<>();
        for (SyncInfoHolder sync : currentInfo) {
            if (!isSyncPending(sync.account, sync.authority)) {
                infoToRemove.add(sync);
            }
        }
        currentInfo.removeAll(infoToRemove);
    }

    public void sendStatusMessage (int result, String message) {
        Bundle bundle = new Bundle();
        bundle.putString(BUNDLE_MESSAGE_KEY, message);
        sendStatus(result, bundle);
    }

    private void logCurrentSyncInfo(
            List<SyncInfoHolder> activeSyncs,
            List<SyncInfoHolder> initialPendingSyncs,
            List<SyncInfoHolder> additionalPendingSyncs) {
        if (!activeSyncs.isEmpty()) {
            sendStatusMessage(
                    Activity.RESULT_OK,
                    String.format(
                            Locale.ENGLISH,
                            "There are active syncs (%d): %s.",
                            activeSyncs.size(),
                            activeSyncs));
        }
        if (!initialPendingSyncs.isEmpty()) {
            sendStatusMessage(
                    Activity.RESULT_OK,
                    String.format(
                            Locale.ENGLISH,
                            "There are pending syncs (%d): %s.",
                            initialPendingSyncs.size(),
                            initialPendingSyncs));
        }
        if (!additionalPendingSyncs.isEmpty()) {
            sendStatusMessage(
                    Activity.RESULT_OK,
                    String.format(
                            Locale.ENGLISH,
                            "There are pending forced syncs (%d): %s.",
                            additionalPendingSyncs.size(),
                            additionalPendingSyncs));
        }
    }

    public void finishInstrumentation (int result, String message) {
        Bundle bundle = new Bundle();
        bundle.putString(BUNDLE_MESSAGE_KEY, message);
        if (result == Activity.RESULT_OK) {
            bundle.putString(BUNDLE_RESULT_KEY, BUNDLE_SUCCESS_MESSAGE);
        } else {
            bundle.putString(BUNDLE_RESULT_KEY, BUNDLE_ERROR_MESSAGE);
        }
        finish(result, bundle);
    }
}
