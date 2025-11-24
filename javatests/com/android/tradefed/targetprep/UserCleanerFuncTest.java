/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.tradefed.device.UserInfo.USER_SYSTEM;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Functional tests for {@link UserCleaner}. */
@RunWith(DeviceJUnit4ClassRunner.class)
public final class UserCleanerFuncTest extends BaseHostJUnit4Test {

    @Rule public final Expect expect = Expect.create();

    private final Set<Integer> mCreatedUserIds = new LinkedHashSet<>();

    private final UserCleaner mCleaner = new UserCleaner();

    @After
    public void removeCreatedUsers() throws Exception {
        for (int userId : mCreatedUserIds) {
            removeUser(userId);
        }
    }

    @Test
    public void testTeardown_nonHsum_currentUserIsSystem() throws Exception {
        assumeNotHsum();
        addUser();
        switchUser(USER_SYSTEM);

        mCleaner.tearDown(getTestInformation(), null);

        expectCurrentUser(USER_SYSTEM);
        expectUsers(USER_SYSTEM);
    }

    @Test
    public void testTeardown_nonHsum_currentUserIsNewUser() throws Exception {
        assumeNotHsum();
        int newUserId = addUser();
        switchUser(newUserId);

        mCleaner.tearDown(getTestInformation(), null);

        expectCurrentUser(USER_SYSTEM);
        expectUsers(USER_SYSTEM);
    }

    @Test
    public void testTeardown_hsum_currentUserIsSystem() throws Exception {
        assumeInteractiveHsum();
        int firstFullUserId = getFirstFullUserId();
        addUser();
        switchUser(USER_SYSTEM);

        mCleaner.tearDown(getTestInformation(), null);

        expectCurrentUser(firstFullUserId);
        expectUsers(USER_SYSTEM, firstFullUserId);
    }

    @Test
    public void testTeardown_hsum_currentUserIsFirstFullUser() throws Exception {
        assumeHsum();
        int firstFullUserId = getFirstFullUserId();
        addUser();
        switchUser(firstFullUserId);

        mCleaner.tearDown(getTestInformation(), null);

        expectCurrentUser(firstFullUserId);
        expectUsers(USER_SYSTEM, firstFullUserId);
    }

    @Test
    public void testTeardown_hsum_currentUserIsNewUser() throws Exception {
        assumeHsum();
        int firstFullUserId = getFirstFullUserId();
        int newUserId = addUser();
        switchUser(newUserId);

        mCleaner.tearDown(getTestInformation(), null);

        expectCurrentUser(firstFullUserId);
        expectUsers(USER_SYSTEM, firstFullUserId);
    }

    private int getFirstFullUserId() throws DeviceNotAvailableException {
        var device = getDevice();
        var allUsers = new TreeMap<>(device.getUserInfos());

        for (var user : allUsers.values()) {
            if (user.isFull()) {
                int userId = user.userId();
                CLog.d("getFirstFullUser(): %d", userId);
                return userId;
            }
        }
        throw new IllegalStateException("No full user in " + allUsers.keySet());
    }

    private int addUser() throws DeviceNotAvailableException {
        String name = UUID.randomUUID().toString();
        int userId = getDevice().createUser(name);
        mCreatedUserIds.add(userId);
        CLog.i("Created user %d with name %s (mCreatedUserIds=%s)", userId, name, mCreatedUserIds);
        return userId;
    }

    private void removeUser(int userId) throws DeviceNotAvailableException {
        CLog.d("removeUser(%d)", userId);
        if (getDevice().removeUser(userId)) {
            CLog.i("Removed user %d", userId);
        } else {
            CLog.d("Failed to remove user %d", userId);
        }
    }

    private void switchUser(int userId) throws DeviceNotAvailableException {
        if (getDevice().switchUser(userId)) {
            CLog.i("Switched to user %d", userId);
            return;
        }
        throw new IllegalStateException("Failed to switch to user " + userId);
    }

    private void assumeNotHsum() throws DeviceNotAvailableException {
        assumeFalse("device is HSUM", getDevice().isHeadlessSystemUserMode());
    }

    private void assumeHsum() throws DeviceNotAvailableException {
        assumeTrue("device is not HSUM", getDevice().isHeadlessSystemUserMode());
    }

    private void assumeInteractiveHsum() throws DeviceNotAvailableException {
        assumeHsum();
        assumeTrue("device is not interactive HSUM", getDevice().canSwitchToHeadlessSystemUser());
    }

    private void expectCurrentUser(int userId) throws DeviceNotAvailableException {
        expect.withMessage("getCurrentUser()").that(getDevice().getCurrentUser()).isEqualTo(userId);
    }

    private void expectUsers(Integer... userIds) throws DeviceNotAvailableException {
        var actualUsers = getDevice().listUsers();
        expect.withMessage("listUsers()").that(actualUsers).containsExactly((Object[]) userIds);
    }
}
