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
package com.google.android.tradefed.account;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.SyncAdapterType;
import android.os.Bundle;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.Arrays;

/** Unit tests for {@link GoogleAccountSync}. */
@RunWith(JUnit4.class)
public class GoogleAccountSyncTest {
    private static final String ACCOUNT_EMAIL = "test@gmail.com";
    private static final long SYNC_TIMEOUT = 1500;
    private static final long SYNC_POLL_INTERVAL = 100;
    // A long-ish leeway is used here as the clean up steps when the utility times out have a lot of
    // overhead, and the test itself takes additional time with the verify() calls, etc. However a
    // disproportionately short sync polling interval is still used to keep the test reasonably
    // short. In actual use when the timeout is much longer, the leeway is more negligible.
    private static final long TIMEOUT_LEEWAY = 300;
    private static final String AUTHORITY_SLOW = "slow";
    private static final String AUTHORITY_SLOWER = "slower";
    private static final String AUTHORITY_SLOWEST = "slowest";
    private static final String AUTHORITY_OTHER = "other";
    private static final ImmutableList<SyncAdapterType> SYNC_ADAPTERS =
            ImmutableList.of(
                    SyncAdapterType.newKey(AUTHORITY_SLOW, GoogleAccountSync.ACCOUNT_TYPE_GOOGLE),
                    SyncAdapterType.newKey(AUTHORITY_SLOWER, GoogleAccountSync.ACCOUNT_TYPE_GOOGLE),
                    SyncAdapterType.newKey(
                            AUTHORITY_SLOWEST, GoogleAccountSync.ACCOUNT_TYPE_GOOGLE),
                    SyncAdapterType.newKey(AUTHORITY_OTHER, "not.google"));
    private static final Account ACCOUNT =
            new Account(ACCOUNT_EMAIL, GoogleAccountSync.ACCOUNT_TYPE_GOOGLE);
    private static final GoogleAccountSync.SyncInfoHolder SYNC_INFO_SLOW =
            new GoogleAccountSync.SyncInfoHolder(ACCOUNT, AUTHORITY_SLOW);
    private static final GoogleAccountSync.SyncInfoHolder SYNC_INFO_SLOWER =
            new GoogleAccountSync.SyncInfoHolder(ACCOUNT, AUTHORITY_SLOWER);
    private static final GoogleAccountSync.SyncInfoHolder SYNC_INFO_SLOWEST =
            new GoogleAccountSync.SyncInfoHolder(ACCOUNT, AUTHORITY_SLOWEST);
    private static final ImmutableList<GoogleAccountSync.SyncInfoHolder> EMPTY_SYNC_INFO_LIST =
            ImmutableList.of();

    @Mock private AccountManager mAccountManager;

    private GoogleAccountSync mGoogleAccountSync;

    @Before
    public void setUp() {
        initMocks(this);
        doReturn(new Account[] {ACCOUNT})
                .when(mAccountManager)
                .getAccountsByType(eq(GoogleAccountSync.ACCOUNT_TYPE_GOOGLE));

        mGoogleAccountSync = spy(new GoogleAccountSync());
        // Stub to substitute the real AccountManager for the mocked one.
        doReturn(mAccountManager).when(mGoogleAccountSync).getAccountManager();
        // Stub out instrumentation-related calls.
        doNothing().when(mGoogleAccountSync).finish(anyInt(), any(Bundle.class));
        doNothing().when(mGoogleAccountSync).sendStatus(anyInt(), any(Bundle.class));
        // Stub out calls that are forwarded to the ContentResolver.
        doNothing()
                .when(mGoogleAccountSync)
                .requestSync(any(Account.class), any(String.class), any(Bundle.class));
        doNothing().when(mGoogleAccountSync).cancelSync(any(Account.class), any(String.class));
        doReturn(SYNC_ADAPTERS.toArray(new SyncAdapterType[SYNC_ADAPTERS.size()]))
                .when(mGoogleAccountSync)
                .getSyncAdapterTypes();
    }

    /*
     * Notes:
     *
     * 1. The following tests use Mockito's iterator-style stubbing where "the last stub wins"
     * according to the documentation. Therefore when the number of the stubbed method calls are
     * larger than the list of return values specified, the last stubbed return value is used.
     *
     * 2. The timeout is set individually for each test as this way the execution time of setUp(),
     * which is not negligible relative to the sync poll interval set here, is taken into account if
     * the JUnit Timeout rule is used instead, which would further confuse test timing as the first
     * execution of setUp() usually takes much longer than subsequent ones.
     */

    /** Test that the utility terminates correctly when no sync activity is present. */
    @Test(timeout = SYNC_TIMEOUT + TIMEOUT_LEEWAY)
    public void testNoForcedSync_noSyncActivity() {
        // No pending syncs.
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), any(String.class));
        // No active syncs.
        doReturn(EMPTY_SYNC_INFO_LIST).when(mGoogleAccountSync).getCurrentSyncs();

        mGoogleAccountSync.parseArguments(getNoForcedSyncArgs());
        mGoogleAccountSync.runSyncCheck();

        // We should only check once and terminate.
        verify(mGoogleAccountSync, times(1)).getCurrentSyncs();
        // Each of the sync authorities should be checked once for whether they are pending prior
        // to polling.
        verify(mGoogleAccountSync, times(1)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOW));
        verify(mGoogleAccountSync, times(1)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOWER));
        verify(mGoogleAccountSync, times(1)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOWEST));

        // Utility should report success.
        verify(mGoogleAccountSync, times(1))
                .finishInstrumentation(eq(Activity.RESULT_OK), any(String.class));
    }

    /** Test that the utility terminates correctly when the initial state only has active syncs. */
    @Test(timeout = SYNC_TIMEOUT + TIMEOUT_LEEWAY)
    public void testNoForcedSync_startingWithActiveSyncsOnly() {
        // No pending syncs.
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), any(String.class));
        // Schedule two poll cycles where there are active syncs and no active syncs after.
        doReturn(
                        Arrays.asList(SYNC_INFO_SLOWER, SYNC_INFO_SLOWEST),
                        Arrays.asList(SYNC_INFO_SLOWEST),
                        EMPTY_SYNC_INFO_LIST)
                .when(mGoogleAccountSync)
                .getCurrentSyncs();

        mGoogleAccountSync.parseArguments(getNoForcedSyncArgs());
        mGoogleAccountSync.runSyncCheck();

        verify(mGoogleAccountSync, times(3)).getCurrentSyncs();
        // Each of the sync authorities should be checked once for whether they are pending prior
        // to polling.
        verify(mGoogleAccountSync, times(1)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOW));
        verify(mGoogleAccountSync, times(1)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOWER));
        verify(mGoogleAccountSync, times(1)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOWEST));

        // Utility should report success.
        verify(mGoogleAccountSync, times(1))
                .finishInstrumentation(eq(Activity.RESULT_OK), any(String.class));
    }

    /** Test that the utility terminates correctly when the initial state only has pending syncs. */
    @Test(timeout = SYNC_TIMEOUT + TIMEOUT_LEEWAY)
    public void testNoForcedSync_startingWithPendingSyncsOnly() {
        // No pending or active syncs for the "slow" sync.
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOW));
        // Pending syncs for one cycle on the "slower" sync.
        doReturn(true, false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWER));
        // Pending syncs for two cycles on the "slowest" sync.
        doReturn(true, true, false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWEST));
        // No active syncs in the beginning, but the slower and slowest sync comes online at the
        // second and third cycle, respectively, and lasts for two and one cycles respectively.
        doReturn(
                        EMPTY_SYNC_INFO_LIST,
                        Arrays.asList(SYNC_INFO_SLOWEST),
                        Arrays.asList(SYNC_INFO_SLOWER, SYNC_INFO_SLOWEST),
                        EMPTY_SYNC_INFO_LIST)
                .when(mGoogleAccountSync)
                .getCurrentSyncs();

        mGoogleAccountSync.parseArguments(getNoForcedSyncArgs());
        mGoogleAccountSync.runSyncCheck();

        verify(mGoogleAccountSync, times(4)).getCurrentSyncs();
        // "Slow" sync is only checked for pending once.
        verify(mGoogleAccountSync, times(1)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOW));
        // "Slower" and "slowest" syncs are checked each poll until they are no longer pending,
        // resulting in 2 and 3 cycles respectively.
        verify(mGoogleAccountSync, times(2)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOWER));
        verify(mGoogleAccountSync, times(3)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOWEST));

        // Utility should report success.
        verify(mGoogleAccountSync, times(1))
                .finishInstrumentation(eq(Activity.RESULT_OK), any(String.class));
    }

    /**
     * Test that the utility terminates correctly when the initial state has both active and pending
     * syncs.
     */
    @Test(timeout = SYNC_TIMEOUT + TIMEOUT_LEEWAY)
    public void testNoForcedSync_startingWithPendingAndActiveSyncs() {
        // "Slow" sync starts out active and not pending.
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOW));
        // "Slower" and "slowest" sync starts out pending for one and two cycles, respectively.
        doReturn(true, false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWER));
        doReturn(true, true, false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWEST));
        // Assume once started, "slow", "slower" and "slowest" last 2 cycles each.
        doReturn(
                        Arrays.asList(SYNC_INFO_SLOW),
                        Arrays.asList(SYNC_INFO_SLOW, SYNC_INFO_SLOWER),
                        Arrays.asList(SYNC_INFO_SLOWER, SYNC_INFO_SLOWEST),
                        Arrays.asList(SYNC_INFO_SLOWEST),
                        EMPTY_SYNC_INFO_LIST)
                .when(mGoogleAccountSync)
                .getCurrentSyncs();

        mGoogleAccountSync.parseArguments(getNoForcedSyncArgs());
        mGoogleAccountSync.runSyncCheck();

        verify(mGoogleAccountSync, times(5)).getCurrentSyncs();
        // Each sync is checked each poll until it is no longer pending resulting in 1, 2 and 3
        // cycles for "slow", "slower" and "sloweest" respectively.
        verify(mGoogleAccountSync, times(1)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOW));
        verify(mGoogleAccountSync, times(2)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOWER));
        verify(mGoogleAccountSync, times(3)).isSyncPending(eq(ACCOUNT), eq(AUTHORITY_SLOWEST));

        // Utility should report success.
        verify(mGoogleAccountSync, times(1))
                .finishInstrumentation(eq(Activity.RESULT_OK), any(String.class));
    }

    /** Test that syncing is terminated once the sync activity dies down. */
    @Test(timeout = SYNC_TIMEOUT + TIMEOUT_LEEWAY)
    public void testNoForcedSync_terminatesOnceSyncsAreDoneAndNoneActive() {
        // No pending syncs.
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), any(String.class));
        doReturn(
                        Arrays.asList(SYNC_INFO_SLOW),
                        EMPTY_SYNC_INFO_LIST,
                        // Any activity after this should no longer matter.
                        EMPTY_SYNC_INFO_LIST,
                        Arrays.asList(SYNC_INFO_SLOWER),
                        EMPTY_SYNC_INFO_LIST)
                .when(mGoogleAccountSync)
                .getCurrentSyncs();

        mGoogleAccountSync.parseArguments(getNoForcedSyncArgs());
        mGoogleAccountSync.runSyncCheck();

        // The syncs should terminate on the second try.
        verify(mGoogleAccountSync, times(2)).getCurrentSyncs();

        // Utility should report success.
        verify(mGoogleAccountSync, times(1))
                .finishInstrumentation(eq(Activity.RESULT_OK), any(String.class));
    }

    /** Test that the utility terminates correctly when it times out with syncs still active. */
    @Test(timeout = SYNC_TIMEOUT + TIMEOUT_LEEWAY)
    public void testNoForcedSync_timesOutWithUnfinishedActiveSyncs() {
        // No pending syncs (does not matter in this test).
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), any(String.class));
        // "Slowest" sync is always active during the polling period.
        doReturn(Arrays.asList(SYNC_INFO_SLOWEST)).when(mGoogleAccountSync).getCurrentSyncs();

        mGoogleAccountSync.parseArguments(getNoForcedSyncArgs());
        mGoogleAccountSync.runSyncCheck();

        // Because of the timeout, the test only passes if the utility times out as expected.

        // Utility should report failure.
        verify(mGoogleAccountSync, times(1))
                .finishInstrumentation(eq(Activity.RESULT_CANCELED), any(String.class));
    }

    /** Test that the utility terminates correctly when it times out with syncs still pending. */
    @Test(timeout = SYNC_TIMEOUT + TIMEOUT_LEEWAY)
    public void testNoForcedSync_timesOutWithUnfinishedPendingSyncs() {
        // Make the "slowest" sync always pending.
        doReturn(true)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWEST));
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOW));
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWER));
        // No active syncs (does not matter in this test).
        doReturn(EMPTY_SYNC_INFO_LIST).when(mGoogleAccountSync).getCurrentSyncs();

        mGoogleAccountSync.parseArguments(getNoForcedSyncArgs());
        mGoogleAccountSync.runSyncCheck();

        // Because of the timeout, the test only passes if the utility times out as expected.

        // Utility should report failure.
        verify(mGoogleAccountSync, times(1))
                .finishInstrumentation(eq(Activity.RESULT_CANCELED), any(String.class));
    }

    /**
     * Test that when a forced sync is requested, it does not request duplicate syncs for syncs that
     * are already pending and only request the non-pending ones.
     */
    @Test(timeout = SYNC_TIMEOUT + TIMEOUT_LEEWAY)
    public void testForcedSync_requestsSyncsForNonPendingSyncsOnly() {
        // Make the "slow" sync pending in the beginning.
        doReturn(true, false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOW));
        // Make the "slower" and "slowest" syncs not pending in the beginning. They should thus
        // be requested. However we can always return "false" here and pretend that they are
        // completed between poll cycles.
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWER));
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWEST));
        // No active syncs (does not matter in this test; assume that all syncs are finished
        // between polling cycles).
        doReturn(EMPTY_SYNC_INFO_LIST).when(mGoogleAccountSync).getCurrentSyncs();

        mGoogleAccountSync.parseArguments(getForceSyncArgs());
        mGoogleAccountSync.runSyncCheck();

        // No sync should be requested for the "slow" sync.
        verify(mGoogleAccountSync, never())
                .requestSync(eq(ACCOUNT), eq(AUTHORITY_SLOW), any(Bundle.class));
        // The "slow" and "slower" syncs should each get a sync request.
        verify(mGoogleAccountSync, times(1))
                .requestSync(eq(ACCOUNT), eq(AUTHORITY_SLOWER), any(Bundle.class));
        verify(mGoogleAccountSync, times(1))
                .requestSync(eq(ACCOUNT), eq(AUTHORITY_SLOWEST), any(Bundle.class));

        // Utility should report success.
        verify(mGoogleAccountSync, times(1))
                .finishInstrumentation(eq(Activity.RESULT_OK), any(String.class));
    }

    /**
     * Test that when a forced sync is requested and some forced syncs are not complete by the time
     * the utility times out, the utility will cancel these forced syncs.
     */
    @Test(timeout = SYNC_TIMEOUT + TIMEOUT_LEEWAY)
    public void testForcedSync_cancelsUnfinishedForcedSyncWhenTimesOut() {
        // Make the "slow" sync pending in the beginning.
        doReturn(true, false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOW));
        // Make the "slower" and "slowest" syncs not pending in the beginning. They should thus
        // be requested. We keep the "slower" sync at false and pretend that it finished during
        // the polling cycle, but change the "slowest" to be always pending afterwards.
        doReturn(false)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWER));
        doReturn(false, true)
                .when(mGoogleAccountSync)
                .isSyncPending(any(Account.class), eq(AUTHORITY_SLOWEST));
        // No active syncs (does not matter in this test; assume that all triggered syncs are
        // finished between polling cycles).
        doReturn(EMPTY_SYNC_INFO_LIST).when(mGoogleAccountSync).getCurrentSyncs();

        mGoogleAccountSync.parseArguments(getForceSyncArgs());
        mGoogleAccountSync.runSyncCheck();

        verify(mGoogleAccountSync, never()).cancelSync(eq(ACCOUNT), eq(AUTHORITY_SLOW));
        verify(mGoogleAccountSync, never()).cancelSync(eq(ACCOUNT), eq(AUTHORITY_SLOWER));
        verify(mGoogleAccountSync, times(1)).cancelSync(eq(ACCOUNT), eq(AUTHORITY_SLOWEST));

        // Utility should report failure.
        verify(mGoogleAccountSync, times(1))
                .finishInstrumentation(eq(Activity.RESULT_CANCELED), any(String.class));
    }

    private Bundle getNoForcedSyncArgs() {
        Bundle args = new Bundle();
        args.putString(GoogleAccountSync.PARAM_INTERVAL, String.valueOf(SYNC_POLL_INTERVAL));
        args.putString(GoogleAccountSync.PARAM_TIMEOUT, String.valueOf(SYNC_TIMEOUT));
        return args;
    }

    private Bundle getForceSyncArgs() {
        Bundle args = getNoForcedSyncArgs();
        args.putString(GoogleAccountSync.PARAM_FORCE_SYNC, String.valueOf(true));
        return args;
    }
}
