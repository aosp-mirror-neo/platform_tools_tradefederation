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

import static com.android.tradefed.device.UserInfo.USER_NULL;
import static com.android.tradefed.device.UserInfo.USER_SYSTEM;

import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.TreeMap;

/** A {@link ITargetPreparer} that removes secondary users on teardown. */
@OptionClass(alias = "user-cleaner")
public class UserCleaner extends BaseTargetPreparer {

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        // no-op
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        var device = testInfo.getDevice();
        int currentUserId = device.getCurrentUser();
        // Uses a TreeMap to make sure user 0 is the first entry
        var allUsers = new TreeMap<>(device.getUserInfos());

        // First need to find the first full user
        int firstFullUserId = USER_NULL;
        for (var user : allUsers.values()) {
            if (user.isFull() && firstFullUserId == USER_NULL) {
                firstFullUserId = user.userId();
                break;
            }
        }
        if (firstFullUserId == USER_NULL) {
            // Shouldn't happen, but it doesn't hurt to check..
            CLog.e("Didn't find a full user, using system user (%d) instead", USER_SYSTEM);
            firstFullUserId = USER_SYSTEM;
        }
        CLog.d(
                "tearDown(): currentUserId=%d, firstFullUserId=%d, allUsers=%s",
                currentUserId, firstFullUserId, allUsers.keySet());

        // Then switch to it if necessary
        if (currentUserId != firstFullUserId) {
            CLog.i("Switching to first full user (%d)", firstFullUserId);
            device.switchUser(firstFullUserId);
        }

        // Finally, remove all users (except system and first full)
        for (var userId : allUsers.keySet()) {
            if (userId == USER_SYSTEM) {
                CLog.d("Not removing system user (%d)", userId);
                continue;
            }
            if (userId == firstFullUserId) {
                CLog.d("Not removing first full user (%d)", userId);
                continue;
            }
            CLog.i("Removing user %d", userId);
            if (!device.removeUser(userId)) {
                CLog.w("Failed to remove user %d", userId);
            }
        }
    }
}
