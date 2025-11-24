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

import static com.android.tradefed.device.UserInfo.FLAG_FULL;
import static com.android.tradefed.device.UserInfo.USER_NULL;
import static com.android.tradefed.device.UserInfo.USER_SYSTEM;

import static org.mockito.Mockito.mock;

import com.android.ddmlib.IDevice;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IDeviceMonitor;
import com.android.tradefed.device.IDeviceStateMonitor;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Unit tests for {@link UserCleaner}. */
@RunWith(JUnit4.class)
public final class UserCleanerTest {

    @Rule public final Expect expect = Expect.create();

    private final UserCleaner mCleaner = new UserCleaner();

    @Test
    public void testTeardown_nonHsum_systemOnly() throws Exception {
        var device = FakeTestDevice.createNonHsumFakeDevice();

        mCleaner.tearDown(device.testInfo, null);

        expectUsers(device, USER_SYSTEM);
    }

    @Test
    public void testTeardown_nonHsum_multipleUsers_currentUserIsSystem() throws Exception {
        var device = FakeTestDevice.createNonHsumFakeDevice();
        device.addFullUsers(4, 8, 15);
        device.addNonFullUsers(16, 23, 42);
        device.switchUser(USER_SYSTEM);

        mCleaner.tearDown(device.testInfo, null);

        expectCurrentUser(device, USER_SYSTEM);
        expectUsers(device, USER_SYSTEM);
    }

    @Test
    public void testTeardown_nonHsum_multipleUsers_currentUserIsNotSystem() throws Exception {
        var device = FakeTestDevice.createNonHsumFakeDevice();
        device.addFullUsers(4, 8, 15);
        device.addNonFullUsers(16, 23, 42);
        device.switchUser(4);

        mCleaner.tearDown(device.testInfo, null);

        expectCurrentUser(device, USER_SYSTEM);
        expectUsers(device, USER_SYSTEM);
    }

    // Shouldn't happen in real life, but it doesn't heart to test
    @Test
    public void testTeardown_hsum_noFullUser() throws Exception {
        var device = FakeTestDevice.createHsumFakeDevice();
        device.addNonFullUsers(42);
        device.switchUser(42);

        mCleaner.tearDown(device.testInfo, null);

        expectCurrentUser(device, USER_SYSTEM);
        expectUsers(device, USER_SYSTEM);
    }

    @Test
    public void testTeardown_hsum_oneFullUserOnly_currentUserIsSystem() throws Exception {
        var device = FakeTestDevice.createHsumFakeDevice();
        device.addFullUsers(42);
        device.switchUser(USER_SYSTEM);

        mCleaner.tearDown(device.testInfo, null);

        expectCurrentUser(device, 42);
        expectUsers(device, USER_SYSTEM, 42);
    }

    @Test
    public void testTeardown_hsum_oneFullUserOnly_currentUserIsNotSystem() throws Exception {
        var device = FakeTestDevice.createHsumFakeDevice();
        device.addFullUsers(42);
        device.switchUser(42);

        mCleaner.tearDown(device.testInfo, null);

        expectCurrentUser(device, 42);
        expectUsers(device, USER_SYSTEM, 42);
    }

    @Test
    public void testTeardown_hsum_multipleUsers_currentUserIsSystem() throws Exception {
        var device = FakeTestDevice.createHsumFakeDevice();
        device.addFullUsers(4, 8, 15);
        device.addNonFullUsers(16, 23, 42);
        device.switchUser(USER_SYSTEM);

        mCleaner.tearDown(device.testInfo, null);

        expectCurrentUser(device, 4);
        expectUsers(device, USER_SYSTEM, 4);
    }

    @Test
    public void testTeardown_hsum_multipleUsers_currentUserIsNotSystem() throws Exception {
        var device = FakeTestDevice.createHsumFakeDevice();
        device.addFullUsers(4, 8, 15);
        device.addNonFullUsers(16, 23, 42);
        device.switchUser(4);

        mCleaner.tearDown(device.testInfo, null);

        expectCurrentUser(device, 4);
        expectUsers(device, USER_SYSTEM, 4);
    }

    private void expectUsers(ITestDevice device, Integer... userIds)
            throws DeviceNotAvailableException {
        var actualUsers = device.listUsers();
        expect.withMessage("listUsers()").that(actualUsers).containsExactly((Object[]) userIds);
    }

    private void expectCurrentUser(ITestDevice device, int userId)
            throws DeviceNotAvailableException {
        expect.withMessage("getCurrentUser()").that(device.getCurrentUser()).isEqualTo(userId);
    }

    private static final class FakeTestDevice extends NativeDevice {

        public final TestInformation testInfo;

        private final Map<Integer, UserInfo> mUsers = new HashMap<>();
        private int mCurrentUserId = USER_NULL;

        private static FakeTestDevice createNonHsumFakeDevice() {
            return new FakeTestDevice(/* isHsum= */ false);
        }

        private static FakeTestDevice createHsumFakeDevice() {
            return new FakeTestDevice(/* isHsum= */ true);
        }

        private FakeTestDevice(boolean isHsum) {
            super(mock(IDevice.class), mock(IDeviceStateMonitor.class), mock(IDeviceMonitor.class));

            IInvocationContext context = new InvocationContext();
            context.addAllocatedDevice("device", this);
            testInfo = TestInformation.newBuilder().setInvocationContext(context).build();

            if (isHsum) {
                addNonFullUsers(USER_SYSTEM);
            } else {
                addFullUsers(USER_SYSTEM);
            }
        }

        private void addFullUsers(int... userIds) {
            addUsers(FLAG_FULL, userIds);
        }

        private void addNonFullUsers(int... userIds) {

            addUsers(/* flags= */ 0, userIds);
        }

        private void addUsers(int flags, int... userIds) {
            for (int userId : userIds) {
                var user = createUser(userId, flags);
                CLog.i("Adding user %d->%s", userId, user);
                mUsers.put(userId, user);
            }
        }

        @Override
        public ArrayList<Integer> listUsers() {
            var ids = new ArrayList<>(mUsers.keySet());
            CLog.i("listUsers(): returning %s", ids);
            return ids;
        }

        @Override
        public Map<Integer, UserInfo> getUserInfos() throws DeviceNotAvailableException {
            var clone = new LinkedHashMap<>(mUsers);
            CLog.i("getUserInfos(): returning %s", clone);
            return clone;
        }

        @Override
        public boolean removeUser(int userId) {
            if (mCurrentUserId == userId) {
                CLog.w("remove(%d): failing as it's the current user", userId);
                return false;
            }
            boolean removed = mUsers.remove(userId) != null;
            CLog.i("remove(%d): returning %b", userId, removed);
            return removed;
        }

        @Override
        public int getCurrentUser() {
            CLog.i("getCurrentUser(): returning %d", mCurrentUserId);
            return mCurrentUserId;
        }

        @Override
        public boolean switchUser(int userId) throws DeviceNotAvailableException {
            if (!mUsers.containsKey(userId)) {
                CLog.w("switchUser(%d): no such user (%s)", userId, mUsers.keySet());
                return false;
            }
            CLog.i("switchUser(): from %d to %d", mCurrentUserId, userId);
            mCurrentUserId = userId;
            return true;
        }

        private static UserInfo createUser(int userId, int flags) {
            return new UserInfo(userId, "User-" + userId, flags, /* isRunning= */ true);
        }
    }
}
