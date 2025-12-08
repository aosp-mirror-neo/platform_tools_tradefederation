/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.common.base.Strings;

import java.util.Map;

// Not directly unit tested, but its clients are
public final class UserHelper {

    private static final String TF_CREATED_USER = "tf_created_user";

    @VisibleForTesting static final String USER_SETUP_COMPLETE = "user_setup_complete";
    @VisibleForTesting static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";
    @VisibleForTesting static final String CAR_TOS_ACCEPTED = "android.car.KEY_USER_TOS_ACCEPTED";

    /** System property used to indicate which Android user is running the test. */
    public static final String RUN_TESTS_AS_USER_KEY = "RUN_TESTS_AS_USER";

    /** Create a new secondary user, or reuse an existing one if applicable. */
    public static int createUser(ITestDevice device, boolean reuseTestUser)
            throws DeviceNotAvailableException, TargetSetupError {
        return createUser(device, reuseTestUser, null);
    }

    /** Create a new secondary user, or reuse an existing one if applicable. */
    public static int createUser(ITestDevice device, boolean reuseTestUser, String userName)
            throws DeviceNotAvailableException, TargetSetupError {
        if (reuseTestUser) {
            Integer existingTFUser = findExistingTradefedUser(device, userName);
            if (existingTFUser != null) {
                return existingTFUser;
            }
        }

        cleanupOldUsersIfLimitReached(device);

        try {
            int userId = device.createUser(userName != null ? userName : TF_CREATED_USER);
            CLog.d("Marking user %d as setup complete", userId);
            device.setSetting(userId, "secure", USER_SETUP_COMPLETE, "1");
            // Accept TOS for the new test user, to avoid showing the setup wizard.
            if (device.hasFeature(FEATURE_AUTOMOTIVE)) {
                device.setSetting(userId, "secure", CAR_TOS_ACCEPTED, "2");
            }

            return userId;
        } catch (IllegalStateException e) {
            throw new TargetSetupError("Failed to create user.", e, device.getDeviceDescriptor());
        }
    }

    /**
     * Gets the user id to run the tests as, from the {@link #RUN_TESTS_AS_USER_KEY} property.
     *
     * <p>If the property is not set or invalid, returns the current user.
     */
    public static int getRunTestsAsUser(TestInformation testInfo)
            throws DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        String val = testInfo.properties().get(RUN_TESTS_AS_USER_KEY);
        if (!Strings.isNullOrEmpty(val)) {
            try {
                return Integer.parseInt(val);
            } catch (Exception e) {
                CLog.e("Failed to parse the userId for " + RUN_TESTS_AS_USER_KEY + " due to " + e);
            }
        }

        // Fall back to the current user.
        return device.getCurrentUser();
    }

    /**
     * If we cannot add any more secondary users, deletes old users to try to make space for one.
     */
    private static void cleanupOldUsersIfLimitReached(ITestDevice device)
            throws DeviceNotAvailableException {

        if (canAddAnotherSecondaryUser(device)) {
            return;
        }

        // We cannot add another secondary user. Try to free up space by removing stale users.
        for (Map.Entry<Integer, UserInfo> entry : device.getUserInfos().entrySet()) {
            UserInfo userInfo = entry.getValue();
            String userName = userInfo.userName();
            if (userName != null && userName.equals(TF_CREATED_USER)) {
                int userId = entry.getKey();
                device.removeUser(userId);
            }
        }
    }

    private static boolean canAddAnotherSecondaryUser(ITestDevice device)
            throws DeviceNotAvailableException {
        if (device.getApiLevel() >= 37) {
            return device.getRemainingCreatableUserCount("android.os.usertype.full.SECONDARY") > 0;
        }

        // For older devices, use older historical heuristic.
        int existingUsersCount = 0;
        for (Map.Entry<Integer, UserInfo> entry : device.getUserInfos().entrySet()) {
            UserInfo userInfo = entry.getValue();
            if (!userInfo.isGuest()) {
                // Guest users don't fall under the quota.
                existingUsersCount++;
            }
        }
        return existingUsersCount < device.getMaxNumberOfUsersSupported();
    }

    private static Integer findExistingTradefedUser(ITestDevice device)
            throws DeviceNotAvailableException {
        return findExistingTradefedUser(device, null);
    }

    private static Integer findExistingTradefedUser(ITestDevice device, String userName)
            throws DeviceNotAvailableException {
        for (Map.Entry<Integer, UserInfo> entry : device.getUserInfos().entrySet()) {
            String existingUserName = entry.getValue().userName();
            String expectedUserName = userName != null ? userName : TF_CREATED_USER;

            if (existingUserName != null && existingUserName.equals(expectedUserName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private UserHelper() {
        throw new UnsupportedOperationException("provide only static methods");
    }
}
