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
import com.android.tradefed.device.UserInfo.UserType;
import com.android.tradefed.device.UserSwitcher;
import com.android.tradefed.device.UserSwitcher.UserTypeMode;
import com.android.tradefed.invoker.TestInformation;

import javax.annotation.Nullable;

/**
 * Default implementation of {@link BaseSwitchUserTargetPreparer} - see that class' javadoc for more
 * info.
 */
@OptionClass(alias = "switch-user-target-preparer")
public class SwitchUserTargetPreparer extends BaseSwitchUserTargetPreparer {
    public static final String OPTION_USER_TYPE = "user-type";
    public static final String OPTION_USER_TYPE_MODE = "user-type-mode";

    @Option(
            name = OPTION_USER_TYPE,
            description = "The type of user to switch to before the module run.")
    private UserType mUserType = UserType.CURRENT;

    @Option(
            name = OPTION_USER_TYPE_MODE,
            description =
                    "Behavior when the requested user type is not switchable (e.g., SYSTEM user on"
                            + " non-interactive HSUM devices)")
    private UserTypeMode mUserTypeMode = UserTypeMode.LEGACY;

    private @Nullable UserSwitcher mUserSwitcher; // instantiated on setUp

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, DeviceNotAvailableException {
        mUserSwitcher = new UserSwitcher(testInformation.getDevice(), mUserType, mUserTypeMode);
        int switchedUser = mUserSwitcher.switchUser();
        setPreparedUser(testInformation, switchedUser);
    }

    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        mUserSwitcher.switchBack();
        setPreparedUser(testInformation, /* userId= */ null);
    }

    public void setUserType(UserType userType) {
        mUserType = userType;
    }
}
