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

import static com.android.tradefed.device.NativeDevice.INVALID_USER_ID;
import static com.android.tradefed.targetprep.UserHelper.RUN_TESTS_AS_USER_KEY;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.Iterator;
import java.util.Set;

import javax.annotation.Nullable;

/** Target preparer for running tests in a user that is started in the visible in the background. */
@OptionClass(alias = "visible-background-user-preparer")
public class VisibleBackgroundUserPreparer extends BaseTargetPreparer {

    @VisibleForTesting public static final int INVALID_DISPLAY = -1; // same as android.view.Display
    @VisibleForTesting public static final int DEFAULT_DISPLAY = 0; // same as android.view.Display

    /**
     * Property used to indicate whether to install test apk for all users.
     * TODO: b/367468564 - Remove this property once we have fixed the tests so that
     * installation for the system user is no longer required
     */
    static final String INSTALL_TEST_APK_FOR_ALL_USERS = "INSTALL_TEST_APK_FOR_ALL_USERS";

    // As indicated by the name, this only applies to tradefed created test user. If there is
    // already a visible background user in the system (not created by tradefed) to run the tests,
    // they will naturally be reused regardless of the value of this param.
    @Option(
            name = "reuse-test-user",
            description =
                    "Whether or not to reuse already created tradefed test user, or remove them "
                            + " and re-create them between module runs.")
    private boolean mReuseTestUser;

    @Option(
            name = "display-id",
            description =
                    "Which display to start the user visible on. If unspecified, the first "
                            + "available secondary display will be used.")
    private int mDisplayId = INVALID_DISPLAY;

    private Integer mUserId;

    // Whether there is already an existing visible background user to run the tests on.
    private boolean mUserAlreadyVisible;

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        if (!device.isVisibleBackgroundUsersSupported()) {
            throw new TargetSetupError("feature not supported", device.getDeviceDescriptor());
        }
        CLog.i("setUp(): mReuseTestUser=%b, mDisplayId=%d", mReuseTestUser, mDisplayId);

        // If the display id is not specified, find the first available secondary display.
        if (mDisplayId == INVALID_DISPLAY) {
            mDisplayId = getFirstAvailableSecondaryDisplay(device);
            CLog.i(
                    "setUp(): Found first available secondary display to run tests mDisplayId=%d",
                    mDisplayId);
        }

        // If there is already a visible background user assigned to that display, just use that
        // user to run the test.
        // TODO(b/443148169): See if there is a corner case to handle that this visible background
        // user is a tradefed created user from the previous run.
        // Note: getUserVisibleOnDisplay returns a user visible on that display, which could either
        // be a
        // visible background user or the current foreground user. By ruling out the current
        // foreground user case, we get the visible background user.
        int visibleUserId = device.getUserVisibleOnDisplay(mDisplayId);
        if (visibleUserId == INVALID_USER_ID) {
            throw new TargetSetupError(
                    String.format("Failed to get visible user on display %d", mDisplayId),
                    device.getDeviceDescriptor());
        }
        int currentUserId = device.getCurrentUser();
        if (currentUserId == INVALID_USER_ID) {
            throw new TargetSetupError(
                    String.format("Failed to get the current user on display %d", mDisplayId),
                    device.getDeviceDescriptor());
        }
        if (visibleUserId != currentUserId) {
            mUserId = visibleUserId;
        }

        if (mUserId == null) {
            // Only when there is no user assigned to that display yet, create a new user
            // and assign the newly created user to that display.
            mUserId = UserHelper.createUser(device, mReuseTestUser);
            CLog.i("setUp(): Created user %d to run tests on display %d", mUserId, mDisplayId);
            if (!device.startVisibleBackgroundUser(mUserId, mDisplayId, /* waitFlag= */ true)) {
                throw new TargetSetupError(
                        String.format("Failed to start user %d on display %d", mUserId, mDisplayId),
                        device.getDeviceDescriptor());
            }
        } else {
            mUserAlreadyVisible = true;
            CLog.i(
                    "setUp(): Found an existing visible background user %d to run tests on "
                            + "display %d",
                    mUserId, mDisplayId);
        }

        CLog.i("Setting test property %s=%d", RUN_TESTS_AS_USER_KEY, mUserId);
        testInfo.properties().put(RUN_TESTS_AS_USER_KEY, Integer.toString(mUserId));
        // TODO(b/367468564): Remove this property once we have fixed the tests so that
        // installation for the system user is no longer required
        testInfo.properties().put(INSTALL_TEST_APK_FOR_ALL_USERS, "true");

        device.waitForDeviceAvailable();
        device.postBootSetup();
    }

    public void setDisplayId(int displayId) {
        if (displayId == INVALID_DISPLAY) {
            throw new IllegalArgumentException(
                    "Cannot set it as INVALID_DISPLAY (" + INVALID_DISPLAY + ")");
        }
        mDisplayId = displayId;
    }

    @VisibleForTesting
    public @Nullable Integer getDisplayId() {
        return mDisplayId;
    }

    private int getFirstAvailableSecondaryDisplay(ITestDevice device)
            throws TargetSetupError, DeviceNotAvailableException {
        int displayId = INVALID_DISPLAY;
        Set<Integer> displays = device.listDisplayIdsForStartingVisibleBackgroundUsers();
        CLog.d("Displays: %s", displays);
        if (displays.isEmpty()) {
            throw new TargetSetupError(
                    "No display available to start a testing user", device.getDeviceDescriptor());
        }
        Iterator<Integer> iterator = displays.iterator();
        displayId = iterator.next();
        if (displayId == DEFAULT_DISPLAY
                && device.isVisibleBackgroundUsersOnDefaultDisplaySupported()) {
            // Ignore default display - it's a special case where the display id should have
            // been passed directly
            CLog.d(
                    "Ignoring DEFAULT_DISPLAY because device supports background users on"
                            + " default display");
            if (!iterator.hasNext()) {
                throw new TargetSetupError(
                        "Only DEFAULT_DISPLAY available to start the testing user",
                        device.getDeviceDescriptor());
            }
            displayId = iterator.next();
        }

        return displayId;
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (mUserId == null) {
            CLog.d("Skipping teardown because no user was created or reused");
            return;
        }
        // Clean property at teardown
        testInfo.properties().remove(RUN_TESTS_AS_USER_KEY);
        if (e instanceof DeviceNotAvailableException) {
            CLog.d("Skipping teardown due to dnae: %s", e.getMessage());
            return;
        }
        ITestDevice device = testInfo.getDevice();

        if (mUserAlreadyVisible) {
            // Don't stop an existing visible background user.
            CLog.d("tearDown(): user %d was already visible on start", mUserId);
            return;
        }

        CLog.d("tearDown(): stopping user %d ", mUserId);
        if (!device.stopUser(mUserId, /* waitFlag= */ true, /* forceFlag= */ true)) {
            CLog.e("Failed to stop user '%d'", mUserId);
        }

        if (!mReuseTestUser) {
            device.removeUser(mUserId);
        }
    }
}
