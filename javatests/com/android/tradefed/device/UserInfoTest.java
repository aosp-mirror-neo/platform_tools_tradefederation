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

import static com.android.tradefed.device.UserInfo.FLAG_ADMIN;
import static com.android.tradefed.device.UserInfo.FLAG_EPHEMERAL;
import static com.android.tradefed.device.UserInfo.FLAG_FOR_TESTING;
import static com.android.tradefed.device.UserInfo.FLAG_FULL;
import static com.android.tradefed.device.UserInfo.FLAG_GUEST;
import static com.android.tradefed.device.UserInfo.FLAG_MAIN;
import static com.android.tradefed.device.UserInfo.FLAG_MANAGED_PROFILE;
import static com.android.tradefed.device.UserInfo.FLAG_PRIMARY;
import static com.android.tradefed.device.UserInfo.FLAG_PROFILE;
import static com.android.tradefed.device.UserInfo.FLAG_RESTRICTED;
import static com.android.tradefed.device.UserInfo.USER_TYPE_FULL_SECONDARY;
import static com.android.tradefed.device.UserInfo.USER_TYPE_SHORT_SECONDARY;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

// NOTE: this test class was created 6 years after UserInfo itself was created, hence it only has
// tests for recent features
@RunWith(JUnit4.class)
public final class UserInfoTest {

    private static final int NO_FLAGS = 0;
    private static final boolean RUNNING = true;
    private static final boolean STOPPED = false;

    @Rule public final Expect expect = Expect.create();

    @Test
    public void testToString() {
        var userWithoutFlags = new UserInfo(007, "Bond, James Bond", NO_FLAGS, STOPPED);
        expect.withMessage("userWithoutFlags")
                .that(userWithoutFlags.toString())
                .isEqualTo("UserInfo[id=7, name=Bond, James Bond, flags=0x0]");

        var userWithOneFlag = new UserInfo(007, "Bond, James Bond", FLAG_ADMIN, STOPPED);
        expect.withMessage("userWithOneFlag")
                .that(userWithOneFlag.toString())
                .isEqualTo("UserInfo[id=7, name=Bond, James Bond, flags=0x2 (ADMIN)]");

        var runningUser = new UserInfo(007, "Bond, James Bond", NO_FLAGS, RUNNING);
        expect.withMessage("runningUser")
                .that(runningUser.toString())
                .isEqualTo("UserInfo[id=7, name=Bond, James Bond, flags=0x0, running]");

        var typedUser = new UserInfo(007, "Bond, James Bond", NO_FLAGS, STOPPED, "Shaken");
        expect.withMessage("typedUser")
                .that(typedUser.toString())
                .isEqualTo("UserInfo[id=7, name=Bond, James Bond, flags=0x0, type=Shaken]");

        var runningTypedUserWithAllFlags =
                new UserInfo(
                        007,
                        "Bond, James Bond",
                        FLAG_FULL
                                | FLAG_GUEST
                                | FLAG_EPHEMERAL
                                | FLAG_FOR_TESTING
                                | FLAG_MAIN
                                | FLAG_MANAGED_PROFILE
                                | FLAG_PRIMARY
                                | FLAG_PROFILE
                                | FLAG_RESTRICTED
                                | FLAG_ADMIN,
                        RUNNING,
                        "Shaken");
        expect.withMessage("runningTypedUserWithAllFlags")
                .that(runningTypedUserWithAllFlags.toString())
                .isEqualTo(
                        "UserInfo[id=7, name=Bond, James Bond, flags=0xd52f"
                            + " (ADMIN|EPHEMERAL|FOR_TESTING|FULL|GUEST|MAIN|MANAGED_PROFILE|PRIMARY|PROFILE|RESTRICTED),"
                            + " type=Shaken, running]");
    }

    @Test
    public void testIsAdmin() {
        var nonAdmin = new UserInfo(007, "Bond, James Bond", NO_FLAGS, STOPPED);
        expect.withMessage("nonAdmin").that(nonAdmin.isAdmin()).isFalse();

        var admin = new UserInfo(007, "Bond, James Bond", FLAG_ADMIN, STOPPED);
        expect.withMessage("admin").that(admin.isAdmin()).isTrue();
    }

    @Test
    public void testIsSecondary() {
        var fullSecondary =
                new UserInfo(10, "Secondary", NO_FLAGS, STOPPED, USER_TYPE_FULL_SECONDARY);
        expect.withMessage("fullSecondary").that(fullSecondary.isSecondary()).isTrue();

        var shortSecondary =
                new UserInfo(11, "Secondary", NO_FLAGS, STOPPED, USER_TYPE_SHORT_SECONDARY);
        expect.withMessage("shortSecondary").that(shortSecondary.isSecondary()).isTrue();

        var systemUser = new UserInfo(0, "System", NO_FLAGS, STOPPED, "system.HEADLESS");
        expect.withMessage("systemUser").that(systemUser.isSecondary()).isFalse();

        var untypedUser = new UserInfo(12, "Untyped", NO_FLAGS, STOPPED);
        expect.withMessage("untypedUser").that(untypedUser.isSecondary()).isFalse();
    }
}
