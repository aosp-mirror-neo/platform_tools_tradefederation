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

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.Locale;

/**
 * A {@link ITargetPreparer} that switches to the specified user kind in setUp. By default it
 * remains in the current user, and no switching is performed.
 *
 * <p>Tries to restore device user state by switching back to the pre-execution current user.
 */
@OptionClass(alias = "switch-user-target-preparer")
public class SwitchUserTargetPreparer extends BaseTargetPreparer {
    @Option(
        name = "user-type",
        description = "The type of user to switch to before the module run."
    )
    private UserInfo.UserType mUserToSwitchTo = UserInfo.UserType.CURRENT;

    private int mPreExecutionCurrentUser;

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, DeviceNotAvailableException {
        ITestDevice device = testInformation.getDevice();
        setUserToSwitchTo(device);

        mPreExecutionCurrentUser = device.getCurrentUser();

        int userIdToSwitch = getUserIdOfType(device, mUserToSwitchTo);
        if (userIdToSwitch == UserInfo.USER_NULL) {
            throw new TargetSetupError(
                    String.format(
                            Locale.ENGLISH,
                            "Failed to switch to user type %s: No user of this type was found on"
                                    + " the device.",
                            mUserToSwitchTo),
                    device.getDeviceDescriptor());
        }

        if (userIdToSwitch == mPreExecutionCurrentUser) {
            CLog.i(
                    "Current user %d is already the target user; no switch required.",
                    mPreExecutionCurrentUser);
            return;
        }

        if (!device.switchUser(userIdToSwitch)) {
            throw new TargetSetupError(
                    String.format(
                            Locale.ENGLISH, "Device failed to switch to user %d.", userIdToSwitch),
                    device.getDeviceDescriptor());
        }
    }

    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        // Restore the previous user as the foreground.
        if (testInformation.getDevice().switchUser(mPreExecutionCurrentUser)) {
            CLog.d("Successfully switched back to user id: %d", mPreExecutionCurrentUser);
        } else {
            CLog.w("Could not switch back to the user id: %d", mPreExecutionCurrentUser);
        }
    }

    /**
     * In some form factors running on headless system user mode, it is restricted to switch to the
     * {@link UserInfo.UserType#SYSTEM SYSTEM} user. In such cases, change the {@link
     * #mUserToSwitchTo} to the first {@link UserInfo.UserType#FULL FULL} user.
     */
    private void setUserToSwitchTo(ITestDevice device) throws DeviceNotAvailableException {
        try {
            if (UserInfo.UserType.SYSTEM.equals(mUserToSwitchTo)
                    && device.isHeadlessSystemUserMode()) {
                mUserToSwitchTo = UserInfo.UserType.FULL;
                CLog.i("SwitchUserTargetPreparer is configured to switch to a FULL user.");
            }
        } catch (HarnessRuntimeException e) {
            CLog.w("Unable to get the main user switch-ability. Error: ", e);
        }
    }

    /**
     * Returns the ID of the first user matching the specified {@link UserInfo.UserType}. Returns
     * {@code UserInfo.USER_NULL} if no such user is found.
     */
    private int getUserIdOfType(ITestDevice device, UserInfo.UserType userType)
            throws DeviceNotAvailableException {
        int currentUser = device.getCurrentUser();
        for (UserInfo userInfo : device.getUserInfos().values()) {
            if (userInfo.isUserType(userType, currentUser)) {
                CLog.i(
                        "Found user %d (type: %s) as the target for switching.",
                        userInfo.userId(), userType.toString());
                return userInfo.userId();
            }
        }
        CLog.w("No user found matching the type: %s.", userType.toString());
        return UserInfo.USER_NULL;
    }
}
