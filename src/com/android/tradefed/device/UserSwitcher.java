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
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.TargetSetupError;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

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
    private final UserTypeMode mUserTypeMode;
    private @Nullable Integer mPreviousUserId;

    /**
     * Default constructor.
     *
     * <p>It sets the user-type mode as {@link UserTypeMode#LEGACY}.
     *
     * @param userType type of user to switch to
     */
    public UserSwitcher(ITestDevice device, UserType userType) {
        this(device, userType, UserTypeMode.LEGACY);
    }

    /**
     * Full constructor.
     *
     * @param userType type of user to switch to
     * @param userTypeMode defines the preparer's behavior based on some properties of the user type
     */
    public UserSwitcher(ITestDevice device, UserType userType, UserTypeMode userTypeMode) {
        mDevice = Objects.requireNonNull(device, "device cannot be null");
        mUserType = Objects.requireNonNull(userType, "userType cannot be null");
        mUserTypeMode = Objects.requireNonNull(userTypeMode, "userTypeMode cannot be null");
    }

    @VisibleForTesting
    UserTypeMode getUserTypeMode() {
        return mUserTypeMode;
    }

    /**
     * Switches to a user of the {@link UserType type} set on constructor, if necessary.
     *
     * @return id of the current user after the switch
     * @throws TargetSetupError if it could not find a user of the given type or if the user switch
     *     operation failed or if this method was already called.
     */
    public int switchUser() throws TargetSetupError, DeviceNotAvailableException {
        try {
            return switchUserUnchecked();
        } catch (RuntimeException | Error e) {
            throw new TargetSetupError(
                    "exception switching users", e, DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
        }
    }

    private int switchUserUnchecked() throws TargetSetupError, DeviceNotAvailableException {
        if (mPreviousUserId != null) {
            throw new TargetSetupError(
                    "switchUser() already called",
                    new IllegalStateException(),
                    InfraErrorIdentifier.UNDETERMINED);
        }
        var userType = mUserType;
        mPreviousUserId = mDevice.getCurrentUser();
        // NOTE: currently it only checks if HSU can be switched to, but ideally it should check for
        // other cases like profile types.
        if (UserType.SYSTEM.equals(mUserType)
                && mDevice.isHeadlessSystemUserMode()
                && !mDevice.canSwitchToHeadlessSystemUser()) {
            switch (mUserTypeMode) {
                case LEGACY:
                    userType = UserInfo.UserType.FULL;
                    CLog.i(
                            "effective user type is %s (not %s) because user-type mode is %s",
                            userType, mUserType, mUserTypeMode);
                    break;
                case REQUIRED_ONLY:
                    CLog.i(
                            "Device doesn't allow switching to system user and user-type mode is"
                                    + " %s, so current user will stay the same (%d)",
                            mUserTypeMode, mPreviousUserId);
                    return mPreviousUserId;
                case REQUIRED_AND_SWITCHABLE:
                    throw new TargetSetupError(
                            String.format(
                                    "Device doesn't support switching to user of type %s.",
                                    mUserType),
                            mDevice.getDeviceDescriptor());
                default:
                    // New modes should be explicitly handled
                    throw new IllegalStateException("Unexpected mode: " + mUserType);
            }
        }

        int userIdToSwitch = getUserIdOfType(userType);
        if (userIdToSwitch == UserInfo.USER_NULL) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to switch to user of type %s: no user of this type was "
                                    + "found in the device.",
                            mUserType),
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
                    String.format("Device failed to switch to user %d.", userIdToSwitch),
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
     * Returns the ID of the first user matching the specified {@link UserInfo.UserType}. Returns
     * {@code UserInfo.USER_NULL} if no such user is found.
     */
    private int getUserIdOfType(UserType userType) throws DeviceNotAvailableException {
        int currentUser = mDevice.getCurrentUser();
        for (UserInfo userInfo : mDevice.getUserInfos().values()) {
            if (userInfo.isUserType(userType, currentUser)) {
                CLog.i(
                        "Found user %d (type: %s) as the target for switching.",
                        userInfo.userId(), userType);
                return userInfo.userId();
            }
        }
        CLog.w("No user found matching the type: %s.", userType);
        return UserInfo.USER_NULL;
    }

    /**
     * Defines the expected behavior depending on the {@link UserType} associated with it.
     *
     * <p><b>NOTE: </b> currently the switchability is only checked when the user type is {@link
     * UserType#SYSTEM}.
     */
    public enum UserTypeMode {
        /**
         * If the user type is {@code SYSTEM} and the device doesn't allow switching to the system
         * user, it will switch to the first full user instead.
         *
         * @deprecated TODO(b/419372865): remove once tests that can be ignored when the HSU cannot
         *     be switched to are changed to use mode REQUIRED_ONLY.
         */
        @Deprecated
        LEGACY,
        /** The user must exist and be switchable to, otherwise the preparer will fail. */
        REQUIRED_AND_SWITCHABLE,
        /** The user must exist, but the test won't switch users if it's not switchable to. */
        REQUIRED_ONLY
    }
}
