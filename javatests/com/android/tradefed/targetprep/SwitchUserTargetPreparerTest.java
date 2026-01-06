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

package com.android.tradefed.targetprep;

import static com.android.tradefed.device.UserInfo.USER_SYSTEM;
import static com.android.tradefed.targetprep.SwitchUserTargetPreparer.PROPERTY_PREPARED_USER;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Unit tests for {@link SwitchUserTargetPreparer}.
 *
 * @deprecated {@link SwitchUserTargetPreparer}'s main logic is handled by {@link UserSwitcher} and
 *     tested by {@link UserSwitcherTest}; hence, this class is obsolete and shouldn't get new
 *     tests, just fixes for changes of behavior (ideally it should be refactored to test the
 *     interaction with {@link UserSwitcher} itself, but such refactoring is not worth the effort).
 */
@RunWith(JUnit4.class)
@Deprecated
public final class SwitchUserTargetPreparerTest {

    @Rule public final Expect expect = Expect.create();
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private ITestDevice mMockDevice;

    private TestInformation mTestInformation;
    private SwitchUserTargetPreparer mSwitchUserTargetPreparer;
    private OptionSetter mOptionSetter;

    @Before
    public void setUp() throws ConfigurationException {
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, mMockDevice);
        mTestInformation = TestInformation.newBuilder().setInvocationContext(context).build();
        mSwitchUserTargetPreparer = new SwitchUserTargetPreparer();
        mOptionSetter = new OptionSetter(mSwitchUserTargetPreparer);
    }

    @Test
    public void testSetUpRunAsPrimary_ifAlreadyInPrimary_noSwitch() throws Exception {
        mOptionSetter.setOptionValue("user-type", "primary");

        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(11);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 11},
                /* flags= */ new Integer[] {0, UserInfo.FLAG_PRIMARY});

        // act
        mSwitchUserTargetPreparer.setUp(mTestInformation);

        expectPreparedUserId(11);
        verify(mMockDevice, never()).switchUser(anyInt());
    }

    @Test
    public void testSetUpRunAsSystem_ifAlreadyInSystem_noSwitch() throws Exception {
        mOptionSetter.setOptionValue("user-type", "system");

        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(USER_SYSTEM);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 11},
                /* flags= */ new Integer[] {0, UserInfo.FLAG_PRIMARY});

        // act
        mSwitchUserTargetPreparer.setUp(mTestInformation);

        // assert
        expectPreparedUserId(USER_SYSTEM);
        verify(mMockDevice, never()).switchUser(USER_SYSTEM);
    }

    @Test
    public void testSetUpRunAsSystem_ifSystemSwitchIsNotAllowed_switchToFull() throws Exception {
        mOptionSetter.setOptionValue("user-type", "system");

        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(11);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 10, 11},
                /* flags= */ new Integer[] {
                    UserInfo.FLAG_PRIMARY, UserInfo.FLAG_FULL, UserInfo.FLAG_GUEST
                });

        when(mMockDevice.isHeadlessSystemUserMode()).thenReturn(true);
        when(mMockDevice.canSwitchToHeadlessSystemUser()).thenReturn(false);
        when(mMockDevice.switchUser(10)).thenReturn(true);

        // act
        mSwitchUserTargetPreparer.setUp(mTestInformation);

        // assert
        expectPreparedUserId(10);
        verify(mMockDevice).switchUser(10);
    }

    @Test
    public void testSetUpRunAsSystem_ifSystemSwitchIsNotAllowed_noSwitchIfAlreadyOnFullUser()
            throws Exception {
        mOptionSetter.setOptionValue("user-type", "system");

        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(10);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 10, 11},
                /* flags= */ new Integer[] {
                    UserInfo.FLAG_PRIMARY, UserInfo.FLAG_FULL, UserInfo.FLAG_GUEST
                });

        when(mMockDevice.isHeadlessSystemUserMode()).thenReturn(true);
        when(mMockDevice.canSwitchToHeadlessSystemUser()).thenReturn(false);

        // act
        mSwitchUserTargetPreparer.setUp(mTestInformation);

        // assert
        expectPreparedUserId(10);
        verify(mMockDevice, never()).switchUser(10);
    }

    @Test
    public void testSetUpRunAsPrimary_ifNotInPrimary_switchToPrimary() throws Exception {
        mOptionSetter.setOptionValue("user-type", "primary");

        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(11);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 10, 11},
                /* flags= */ new Integer[] {0, UserInfo.FLAG_PRIMARY, 0});
        when(mMockDevice.switchUser(10)).thenReturn(true);

        // act
        mSwitchUserTargetPreparer.setUp(mTestInformation);

        // assert
        expectPreparedUserId(10);
        verify(mMockDevice).switchUser(10);
    }

    @Test
    public void testSetUpRunAsGuest_ifNotInGuest_switchToGuest() throws Exception {
        mOptionSetter.setOptionValue("user-type", "guest");

        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(11);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 10, 11},
                /* flags= */ new Integer[] {0, UserInfo.FLAG_GUEST, 0});
        when(mMockDevice.switchUser(10)).thenReturn(true);

        // act
        mSwitchUserTargetPreparer.setUp(mTestInformation);

        // assert
        expectPreparedUserId(10);
        verify(mMockDevice).switchUser(10);
    }

    @Test
    public void testSetUpRunAsSystem_ifNotInSystem_switchToSystem() throws Exception {
        mOptionSetter.setOptionValue("user-type", "system");

        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(10);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 10},
                /* flags= */ new Integer[] {0, 0});
        when(mMockDevice.switchUser(0)).thenReturn(true);

        // act
        mSwitchUserTargetPreparer.setUp(mTestInformation);

        // assert
        expectPreparedUserId(USER_SYSTEM);
        verify(mMockDevice).switchUser(USER_SYSTEM);
    }

    @Test
    public void testTearDown_ifStartedInSecondary_switchesBackToSecondary() throws Exception {
        mOptionSetter.setOptionValue("user-type", "system");

        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(10);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {0, 10},
                /* flags= */ new Integer[] {0, 0});
        when(mMockDevice.switchUser(0)).thenReturn(true);
        when(mMockDevice.switchUser(10)).thenReturn(true);

        // first switches to primary
        mSwitchUserTargetPreparer.setUp(mTestInformation);
        expectPreparedUserId(USER_SYSTEM);
        verify(mMockDevice).switchUser(USER_SYSTEM);

        // then switches back to secondary
        mSwitchUserTargetPreparer.tearDown(mTestInformation, null);
        expectPreparedUserId(/* userId= */ null);
        verify(mMockDevice).switchUser(10);
    }

    @Test
    public void testSetUp_ifNoSwitchToSpecified_noUserSwitch() throws Exception {
        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(10);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 10},
                /* flags= */ new Integer[] {0, 0});

        // act
        mSwitchUserTargetPreparer.setUp(mTestInformation);

        expectPreparedUserId(10);
        // assert
        verify(mMockDevice, never()).switchUser(anyInt());
    }

    @Test
    public void testSetUp_ifSwitchFails_throwsTargetSetupError() throws Exception {
        mOptionSetter.setOptionValue("user-type", "primary");

        // setup
        when(mMockDevice.getCurrentUser()).thenReturn(10);
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {0, 10},
                /* flags= */ new Integer[] {UserInfo.FLAG_PRIMARY, 0});
        when(mMockDevice.switchUser(0)).thenReturn(false);

        // act / assert
        assertThrows(
                TargetSetupError.class, () -> mSwitchUserTargetPreparer.setUp(mTestInformation));
        expectPreparedUserId(/* userId= */ null);
    }

    @Test
    public void testWorkflow_whenSetUpFailsAndCallerRetries_sameCurrentUserOn2ndCall()
            throws Exception {
        mOptionSetter.setOptionValue("user-type", "primary");
        when(mMockDevice.getCurrentUser()).thenReturn(10, 10);

        // 1st setup call
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 10},
                /* flags= */ new Integer[] {UserInfo.FLAG_PRIMARY, 0});
        when(mMockDevice.switchUser(USER_SYSTEM)).thenReturn(false);
        assertThrows(
                TargetSetupError.class, () -> mSwitchUserTargetPreparer.setUp(mTestInformation));

        // 2nd setup call
        when(mMockDevice.switchUser(USER_SYSTEM)).thenReturn(true);
        mSwitchUserTargetPreparer.setUp(mTestInformation);
        expectPreparedUserId(/* userId= */ USER_SYSTEM);

        // Teardown
        mSwitchUserTargetPreparer.tearDown(mTestInformation, null);
        expectPreparedUserId(/* userId= */ null);
        verify(mMockDevice).switchUser(10);
    }

    @Test
    public void testWorkflow_whenSetUpFailsAndCallerRetries_differentCurrentUserOn2ndCall()
            throws Exception {
        mOptionSetter.setOptionValue("user-type", "primary");
        when(mMockDevice.getCurrentUser()).thenReturn(10, 11);

        // 1st setup call
        mockListUsersInfo(
                mMockDevice,
                /* userIds= */ new Integer[] {USER_SYSTEM, 10, 11},
                /* flags= */ new Integer[] {UserInfo.FLAG_PRIMARY, 0, 0});
        when(mMockDevice.switchUser(USER_SYSTEM)).thenReturn(false);
        assertThrows(
                TargetSetupError.class, () -> mSwitchUserTargetPreparer.setUp(mTestInformation));

        // 2nd setup call
        when(mMockDevice.switchUser(USER_SYSTEM)).thenReturn(true);
        mSwitchUserTargetPreparer.setUp(mTestInformation);
        expectPreparedUserId(/* userId= */ USER_SYSTEM);

        // Teardown
        mSwitchUserTargetPreparer.tearDown(mTestInformation, null);
        expectPreparedUserId(/* userId= */ null);
        verify(mMockDevice).switchUser(11);
    }

    @Test
    public void testGetExpectedUserId_beforeSetup() throws Exception {
        expectPreparedUserId(/* userId= */ null);
    }

    private void expectPreparedUserId(@Nullable Integer userId) {
        expect.withMessage("getPreparedUserId()")
                .that(mSwitchUserTargetPreparer.getPreparedUserId())
                .isEqualTo(userId);
        var props = mTestInformation.properties();
        if (userId == null) {
            expect.withMessage("properties contains %s", PROPERTY_PREPARED_USER)
                    .that(props.containsKey(PROPERTY_PREPARED_USER))
                    .isFalse();
        } else {
            expect.withMessage("properties contains %s", PROPERTY_PREPARED_USER)
                    .that(props.containsKey(PROPERTY_PREPARED_USER))
                    .isTrue();
            expect.withMessage("value of property %s", PROPERTY_PREPARED_USER)
                    .that(props.get(PROPERTY_PREPARED_USER))
                    .isEqualTo(Integer.toString(userId));
        }
    }

    private void mockListUsersInfo(ITestDevice device, Integer[] userIds, Integer[] flags)
            throws DeviceNotAvailableException {
        Map<Integer, UserInfo> result = new HashMap<>();
        for (int i = 0; i < userIds.length; i++) {
            int userId = userIds[i];
            result.put(
                    userId,
                    new UserInfo(
                            /* userId= */ userId,
                            /* userName= */ "usr" + userId,
                            /* flag= */ flags[i],
                            /* isRunning= */ false));
        }
        when(device.getUserInfos()).thenReturn(result);
    }
}
