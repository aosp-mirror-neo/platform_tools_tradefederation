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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.device.UserInfo.UserType;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.ExecutionProperties;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.testmapping.TestInfo;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * A {@link ITargetPreparer} that switches to the specified user kind in setUp. By default it
 * remains in the current user, and no switching is performed.
 *
 * <p>Tries to restore device user state by switching back to the pre-execution current user.
 */
@OptionClass(alias = "switch-user-target-preparer")
public class SwitchUserTargetPreparer extends BaseTargetPreparer {

    public static final String OPTION_USER_TYPE = "user-type";

    /**
     * Name of the {@link TestInfo} {@link ExecutionProperties property} key that stores the id of
     * the current user of the device after the target preparation.
     *
     * <p>For example, if the current user before the preparer was triggered was {@code 42} and the
     * preparer switched to {@code 0}, then the value of the property will be {@code "0"}.
     *
     * <p><b>Note: </b>the property is not set if the user switch failed, and it's removed at the
     * end (after {@link #tearDown(TestInformation, Throwable)}).
     */
    public static final String PROPERTY_PREPARED_USER =
            "com.android.tradefed.targetprep.SwitchUserTargetPreparer.preparedUser";

    @Option(
            name = OPTION_USER_TYPE,
            description = "The type of user to switch to before the module run.")
    private UserType mUserType = UserType.CURRENT;

    private int mPreExecutionCurrentUser;
    private @Nullable Integer mPreparedUserId;

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, DeviceNotAvailableException {
        ITestDevice device = testInformation.getDevice();
        setUserType(device);

        mPreExecutionCurrentUser = device.getCurrentUser();
        CLog.d(
                "on setUp(): mPreExecutionCurrentUser=%d, mUserType=%s",
                mPreExecutionCurrentUser, mUserType);

        int userIdToSwitch = getUserIdOfType(device, mUserType);
        if (userIdToSwitch == UserInfo.USER_NULL) {
            throw new TargetSetupError(
                    String.format(
                            Locale.ENGLISH,
                            "Failed to switch to user type %s: No user of this type was found on"
                                    + " the device.",
                            mUserType),
                    device.getDeviceDescriptor());
        }

        if (userIdToSwitch == mPreExecutionCurrentUser) {
            CLog.i(
                    "Current user %d is already the target user; no switch required.",
                    mPreExecutionCurrentUser);
            setPreparedUser(testInformation, userIdToSwitch);
            return;
        }

        if (!device.switchUser(userIdToSwitch)) {
            throw new TargetSetupError(
                    String.format(
                            Locale.ENGLISH, "Device failed to switch to user %d.", userIdToSwitch),
                    device.getDeviceDescriptor());
        }
        setPreparedUser(testInformation, userIdToSwitch);
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
        setPreparedUser(testInformation, /* userId= */ null);
    }

    @Override
    public final void setUp(ITestDevice device, IBuildInfo buildInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        throw new UnsupportedOperationException(
                "setUp(ITestDevice, IBuildInfo) is deprecated. You should override the "
                        + "new setUp(TestInformation) instead.");
    }

    @Override
    public final void tearDown(ITestDevice device, IBuildInfo buildInfo, Throwable e)
            throws DeviceNotAvailableException {
        throw new UnsupportedOperationException(
                "tearDown(ITestDevice, IBuildInfo, Throwable) is deprecated. You should override "
                        + "new tearDown(TestInformation, Throwable) instead.");
    }

    /** Convenience method for subclasses to get the value of {@link #PROPERTY_PREPARED_USER}. */
    protected final @Nullable Integer getPreparedUserId() {
        return mPreparedUserId;
    }

    private void setPreparedUser(TestInformation testInformation, @Nullable Integer userId) {
        mPreparedUserId = userId;
        var props = testInformation.properties();
        if (userId != null) {
            CLog.d("Setting %s property to %d", PROPERTY_PREPARED_USER, userId);
            props.put(PROPERTY_PREPARED_USER, Integer.toString(userId));
        } else {
            CLog.d("Removing property %s", PROPERTY_PREPARED_USER);
            props.remove(PROPERTY_PREPARED_USER);
        }
    }

    /**
     * In some form factors running on headless system user mode, it is restricted to switch to the
     * {@link UserType#SYSTEM SYSTEM} user. In such cases, change the {@link #mUserType} to the
     * first {@link UserType#FULL FULL} user.
     */
    private void setUserType(ITestDevice device) throws DeviceNotAvailableException {
        try {
            if (UserType.SYSTEM.equals(mUserType)
                    && device.isHeadlessSystemUserMode()
                    && !device.canSwitchToHeadlessSystemUser()) {
                mUserType = UserType.FULL;
                CLog.i("SwitchUserTargetPreparer is configured to switch to a FULL user.");
            }
        } catch (HarnessRuntimeException e) {
            CLog.w("Unable to get the main user switch-ability. Error: ", e);
        }
    }

    /**
     * Returns the ID of the first user matching the specified {@link UserType}. Returns {@code
     * UserInfo.USER_NULL} if no such user is found.
     */
    private int getUserIdOfType(ITestDevice device, UserType userType)
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
