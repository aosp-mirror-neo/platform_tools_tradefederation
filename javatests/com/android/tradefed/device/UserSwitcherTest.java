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
package com.android.tradefed.device;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.UserInfo.UserType;
import com.android.tradefed.targetprep.TargetSetupError;

import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;

public final class UserSwitcherTest {

    private static final UserInfo SYSTEM_USER =
            newUser(UserInfo.USER_SYSTEM, UserInfo.FLAG_PRIMARY);
    private static final UserInfo FULL_USER = newUser(/* id= */ 42, UserInfo.FLAG_FULL);
    private static final UserInfo GUEST_USER = newUser(/* id= */ 108, UserInfo.FLAG_GUEST);

    @Rule public final Expect expect = Expect.create();
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private ITestDevice mMockDevice;

    @Test
    public void testConstructor_null() throws Exception {
        assertThrows(
                NullPointerException.class,
                () -> new UserSwitcher(/* device= */ null, UserType.FULL));
        assertThrows(
                NullPointerException.class,
                () -> new UserSwitcher(mMockDevice, /* userType= */ null));
    }

    @Test
    public void testSwitchUser_typePrimary_ifAlreadyInPrimary_noSwitch() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.PRIMARY);

        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(SYSTEM_USER);

        int switchedUserId = userSwitcher.switchUser();

        expect.withMessage("result").that(switchedUserId).isEqualTo(SYSTEM_USER.userId());
        verifyUserNeverSwitched();
    }

    @Test
    public void testSwitchUser_typeSystem_ifAlreadyInPrimary_noSwitch() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.SYSTEM);

        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(SYSTEM_USER);

        int switchedUserId = userSwitcher.switchUser();

        expect.withMessage("result").that(switchedUserId).isEqualTo(SYSTEM_USER.userId());
        verifyUserNeverSwitched();
    }

    @Test
    public void testSwitchUser_typeSystem_ifSystemSwitchIsNotAllowed_switchToFull()
            throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.SYSTEM);

        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(GUEST_USER);
        mockSuccessfulUserSwitch(FULL_USER);
        mockHeadlessSystemUserMode(true);
        mockCanSwitchToHeadlessSystemUser(false);

        int switchedUserId = userSwitcher.switchUser();

        expect.withMessage("result").that(switchedUserId).isEqualTo(FULL_USER.userId());
        verifySwitchUser(FULL_USER);
    }

    @Test
    public void testSwitchUser_typeSystem_ifSystemSwitchIsNotAllowed_noSwitchIfAlreadyOnFullUser()
            throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.SYSTEM);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);
        mockHeadlessSystemUserMode(true);
        mockCanSwitchToHeadlessSystemUser(false);

        int switchedUserId = userSwitcher.switchUser();

        expect.withMessage("result").that(switchedUserId).isEqualTo(FULL_USER.userId());
        verifyUserNeverSwitched();
    }

    @Test
    public void testSwitchUser_typePrimary_ifNotInPrimary_switchToPrimary() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.PRIMARY);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);
        mockSuccessfulUserSwitch(SYSTEM_USER);

        int switchedUserId = userSwitcher.switchUser();

        expect.withMessage("result").that(switchedUserId).isEqualTo(SYSTEM_USER.userId());
        verifySwitchUser(SYSTEM_USER);
    }

    @Test
    public void testSwitchUser_typeGuest_ifNotInGuest_switchToGuest() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.GUEST);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);
        mockSuccessfulUserSwitch(GUEST_USER);

        int switchedUserId = userSwitcher.switchUser();

        expect.withMessage("result").that(switchedUserId).isEqualTo(GUEST_USER.userId());
        verifySwitchUser(GUEST_USER);
    }

    @Test
    public void testSwitchUser_typeSystem_ifNotInSystem_switchToSystem() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.SYSTEM);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);
        mockSuccessfulUserSwitch(SYSTEM_USER);

        int switchedUserId = userSwitcher.switchUser();

        expect.withMessage("result").that(switchedUserId).isEqualTo(SYSTEM_USER.userId());
        verifySwitchUser(SYSTEM_USER);
    }

    @Test
    public void testSwitchUser_typeCurrent_dontSwitch() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.CURRENT);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);

        int switchedUserId = userSwitcher.switchUser();

        expect.withMessage("result").that(switchedUserId).isEqualTo(FULL_USER.userId());
        verifyUserNeverSwitched();
    }

    @Test
    public void testSwitchUser_ifSwitchFails_throwsTargetSetupError() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.PRIMARY);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);
        mockFailedUserSwitch(SYSTEM_USER);

        assertThrows(TargetSetupError.class, () -> userSwitcher.switchUser());
    }

    @Test
    public void testSwitchUser_throwsIfCalledTwice() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.CURRENT);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);

        // 1st call
        int switchedUserId = userSwitcher.switchUser();
        expect.withMessage("result").that(switchedUserId).isEqualTo(FULL_USER.userId());
        verifyUserNeverSwitched();

        // 2nd call
        assertThrows(IllegalStateException.class, () -> userSwitcher.switchUser());
    }

    @Test
    public void testSwitchBack_ifStartedInFull_switchesBackToFull_succeded() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.SYSTEM);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);
        mockSuccessfulUserSwitch(SYSTEM_USER);
        mockSuccessfulUserSwitch(FULL_USER);

        // first switches to system
        int switchedUserId = userSwitcher.switchUser();
        expect.withMessage("result").that(switchedUserId).isEqualTo(SYSTEM_USER.userId());
        verifySwitchUser(SYSTEM_USER);

        // then switches back to full
        boolean switchedBack = userSwitcher.switchBack();
        expect.withMessage("switched back").that(switchedBack).isTrue();
        verifySwitchUser(FULL_USER);
    }

    @Test
    public void testSwitchBack_ifStartedInFull_switchesBackToFull_failed() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.SYSTEM);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);
        mockSuccessfulUserSwitch(SYSTEM_USER);
        mockFailedUserSwitch(FULL_USER);

        // first switches to system
        int switchedUserId = userSwitcher.switchUser();
        expect.withMessage("result").that(switchedUserId).isEqualTo(SYSTEM_USER.userId());
        verifySwitchUser(SYSTEM_USER);

        // then switches back to full
        boolean switchedBack = userSwitcher.switchBack();
        expect.withMessage("switched back").that(switchedBack).isFalse();
        verifySwitchUser(FULL_USER);
    }

    @Test
    public void testSwitchUser_whenSwitchFails() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.PRIMARY);
        mockUsers(SYSTEM_USER, FULL_USER, GUEST_USER);
        mockCurrentUser(FULL_USER);
        mockFailedUserSwitch(SYSTEM_USER);
        assertThrows(TargetSetupError.class, () -> userSwitcher.switchUser());

        userSwitcher.switchBack();

        verifySwitchUser(FULL_USER);
    }

    @Test
    public void testSwitchBack_throwsIfNotSwitchedFirst() throws Exception {
        var userSwitcher = new UserSwitcher(mMockDevice, UserType.PRIMARY);

        assertThrows(IllegalStateException.class, () -> userSwitcher.switchBack());
    }

    private void mockUsers(UserInfo... users) throws DeviceNotAvailableException {
        Map<Integer, UserInfo> map =
                Arrays.stream(users)
                        .collect(
                                ImmutableMap.toImmutableMap(UserInfo::userId, Function.identity()));
        when(mMockDevice.getUserInfos()).thenReturn(map);
    }

    private void mockCurrentUser(UserInfo user) throws DeviceNotAvailableException {
        when(mMockDevice.getCurrentUser()).thenReturn(user.userId());
    }

    private void mockSuccessfulUserSwitch(UserInfo user) throws DeviceNotAvailableException {
        when(mMockDevice.switchUser(user.userId())).thenReturn(true);
    }

    private void mockFailedUserSwitch(UserInfo user) throws DeviceNotAvailableException {
        when(mMockDevice.switchUser(user.userId())).thenReturn(false);
    }

    private void mockCanSwitchToHeadlessSystemUser(boolean value)
            throws DeviceNotAvailableException {
        when(mMockDevice.canSwitchToHeadlessSystemUser()).thenReturn(value);
    }

    private void mockHeadlessSystemUserMode(boolean value) throws DeviceNotAvailableException {
        when(mMockDevice.isHeadlessSystemUserMode()).thenReturn(value);
    }

    private void verifyUserNeverSwitched() throws DeviceNotAvailableException {
        verify(mMockDevice, never()).switchUser(anyInt());
    }

    private void verifySwitchUser(UserInfo fullUser) throws DeviceNotAvailableException {
        verify(mMockDevice).switchUser(fullUser.userId());
    }

    private static UserInfo newUser(int id, int flag) {
        return new UserInfo(id, /* userName= */ "User-" + id, flag, /* isRunning= */ false);
    }
}
