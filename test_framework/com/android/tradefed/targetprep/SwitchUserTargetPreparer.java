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
import com.android.tradefed.device.UserInfo.UserType;
import com.android.tradefed.device.UserSwitcher;
import com.android.tradefed.invoker.ExecutionProperties;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.testmapping.TestInfo;

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

    private @Nullable UserSwitcher mUserSwitcher; // instantiate on setUp

    private @Nullable Integer mPreparedUserId;

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, DeviceNotAvailableException {
        mUserSwitcher = new UserSwitcher(testInformation.getDevice(), mUserType);
        int switchedUser = mUserSwitcher.switchUser();
        setPreparedUser(testInformation, switchedUser);
    }

    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        mUserSwitcher.switchBack();
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
}
