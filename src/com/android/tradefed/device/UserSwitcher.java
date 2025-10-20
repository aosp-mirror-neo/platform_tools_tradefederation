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

import com.android.tradefed.device.UserInfo.UserType;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.TargetSetupError;

import com.google.common.base.Preconditions;

import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Helper object used to switch to a user of a given {@link UserType type}, and then switch back to
 * the previous user later.
 *
 * <p><b>Note: </b>it's meant to be used just once, typically by {@link
 * com.android.tradefed.targetprep.ISwitchUserTargetPreparer} implementations (which would call
 * {@link #switchUser(ITestDevice, UserType)} on {@code setUp()} and {@link #switchBack()} on {@code
 * tearDown()}. It could be used multiple times, in which case {@link #switchBack()} would always
 * switch to the latest {@link #switchUser(ITestDevice, UserType) user switched to}.
 */
public final class UserSwitcher {

    private final ITestDevice mDevice;
    private final UserType mUserType;
    private @Nullable Integer mPreviousUserId;

    /**
     * Default constructor.
     *
     * @param device guess what?
     * @param userType type of user to switch to
     */
    public UserSwitcher(ITestDevice device, UserType userType) {
        mDevice = Objects.requireNonNull(device, "device cannot be null");
        mUserType = Objects.requireNonNull(userType, "userType cannot be null");
    }

    /**
     * Switches to a user of the {@link UserType type} set on constructor, if necessary.
     *
     * @return id of the current user after the switch
     * @throws IllegalStateException if this method was already called.
     * @throws TargetSetupError if it could not find a user of the given type or if the user switch
     *     operation failed.
     */
    public int switchUser() throws TargetSetupError, DeviceNotAvailableException {
        Preconditions.checkState(mPreviousUserId == null, "switchUser() already called");
        var userType = getEffectiveUserType(mDevice, mUserType);
        mPreviousUserId = mDevice.getCurrentUser();
        int userIdToSwitch = getUserIdOfType(mDevice, userType);
        if (userIdToSwitch == UserInfo.USER_NULL) {
            throw new TargetSetupError(
                    String.format(
                            Locale.ENGLISH,
                            "Failed to switch to user type %s: No user of this type was found on"
                                    + " the device.",
                            userType),
                    mDevice.getDeviceDescriptor());
        }
        if (userIdToSwitch == mPreviousUserId) {
            CLog.i(
                    "Current user %d is already the target user; no switch required.",
                    mPreviousUserId);
            return userIdToSwitch;
        }
        if (!mDevice.switchUser(userIdToSwitch)) {
            throw new TargetSetupError(
                    String.format(
                            Locale.ENGLISH, "Device failed to switch to user %d.", userIdToSwitch),
                    mDevice.getDeviceDescriptor());
        }
        return userIdToSwitch;
    }

    /**
     * Switches back to the current user prior to the {@link #switchUser(ITestDevice, UserType)}
     * call.
     *
     * @return whether the switch succeeded
     * @throws IllegalStateException if this method is called before {@link #switchUser(ITestDevice,
     *     UserType)}
     */
    public boolean switchBack() throws DeviceNotAvailableException {
        Preconditions.checkState(mPreviousUserId != null, "must call switchUser() first");

        // Restore the previous user as the foreground.
        boolean result = mDevice.switchUser(mPreviousUserId);
        if (result) {
            CLog.d("Successfully switched back to user id: %d", mPreviousUserId);
        } else {
            CLog.w("Could not switch back to the user id: %d", mPreviousUserId);
        }
        return result;
    }

    /**
     * In some form factors running on headless system user mode, it is restricted to switch to the
     * {@link UserInfo.UserType#SYSTEM SYSTEM} user. In such cases, change the {@link
     * #mUserToSwitchTo} to the first {@link UserInfo.UserType#FULL FULL} user.
     */
    private UserType getEffectiveUserType(ITestDevice device, UserType type)
            throws DeviceNotAvailableException {
        UserType effectiveType = type;
        try {
            if (UserInfo.UserType.SYSTEM.equals(type)
                    && device.isHeadlessSystemUserMode()
                    && !device.canSwitchToHeadlessSystemUser()) {
                effectiveType = UserInfo.UserType.FULL;
                CLog.i("effective user type is %s (not %s)", effectiveType, type);
            }
        } catch (HarnessRuntimeException e) {
            CLog.w("Unable to get the main user switch-ability. Error: ", e);
        }
        return effectiveType;
    }

    /**
     * Returns the ID of the first user matching the specified {@link UserInfo.UserType}. Returns
     * {@code UserInfo.USER_NULL} if no such user is found.
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
