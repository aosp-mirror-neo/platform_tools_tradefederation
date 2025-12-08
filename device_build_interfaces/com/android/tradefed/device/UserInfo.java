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
package com.android.tradefed.device;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Similar to UserInfo class from platform.
 *
 * <p>This is intended to be similar to android.content.pm.UserInfo.
 *
 * <p>Stores data and basic logic around the information for one user.
 */
public final class UserInfo {
    // From {@link android.os.UserHandle}
    public static final int USER_SYSTEM = 0;
    public static final int USER_NULL = -10000;
    public static final int USER_ALL = -1;
    public static final int USER_CURRENT = -2;
    public static final int USER_CURRENT_OR_SELF = -3;

    // From {@link android.content.pm.UserInfo}
    public static final int FLAG_PRIMARY = 0x00000001;
    public static final int FLAG_ADMIN = 0x00000002;
    public static final int FLAG_GUEST = 0x00000004;
    public static final int FLAG_RESTRICTED = 0x00000008;
    public static final int FLAG_EPHEMERAL = 0x00000100;
    public static final int FLAG_MANAGED_PROFILE = 0x00000020;
    public static final int FLAG_PROFILE = 0x00001000;
    public static final int FLAG_FULL = 0x00000400;
    public static final int FLAG_MAIN = 0x00004000;

    public static final int FLAG_FOR_TESTING = 0x00008000;

    public static final int FLAGS_NOT_SECONDARY =
            FLAG_PRIMARY | FLAG_MANAGED_PROFILE | FLAG_GUEST | FLAG_RESTRICTED;
    public static final String CLONE_PROFILE_TYPE = "profile.CLONE";

    public static final String COMMUNAL_PROFILE_TYPE = "profile.COMMUNAL";

    public static final String PRIVATE_PROFILE_TYPE = "profile.PRIVATE";

    private final int mUserId;
    private final String mUserName;
    private final int mFlag;
    private final boolean mIsRunning;
    private String mUserType;

    /** Supported variants of a user's type in external APIs. */
    public enum UserType {
        /** current foreground user of the device */
        CURRENT,
        /**
         * guest user. Only one can exist at a time, may be ephemeral and have more restrictions.
         */
        GUEST,
        /** user flagged as primary on the device; most often primary = system user = user 0 */
        PRIMARY,
        /** system user = user 0 */
        SYSTEM,
        /** undefined user id */
        NULL,
        /** Indicates a "full" user, which is a non-profile, human user. */
        FULL,
        /**
         * Indicates the "main" user, which is the designated primary user on the device, as defined
         * by {@link UserManager#getMainUser()}.
         *
         * @deprecated Use FULL instead.
         */
        @Deprecated
        MAIN,
        /** secondary user, i.e. non-primary and non-system. */
        SECONDARY,
        /** managed profile user, e.g. work profile. */
        MANAGED_PROFILE,
        /** clone profile user */
        CLONE_PROFILE,
        /** communal profile user */
        COMMUNAL_PROFILE,
        /** private profile user */
        PRIVATE_PROFILE;

        public boolean isCurrent() {
            return this == CURRENT;
        }

        public boolean isGuest() {
            return this == GUEST;
        }

        public boolean isPrimary() {
            return this == PRIMARY;
        }

        public boolean isSystem() {
            return this == SYSTEM;
        }

        public boolean isFull() {
            return this == FULL;
        }

        /**
         * @deprecated Use {@link #isFull()} instead.
         */
        @Deprecated
        public boolean isMain() {
            return this == MAIN;
        }

        public boolean isSecondary() {
            return this == SECONDARY;
        }

        public boolean isManagedProfile() {
            return this == MANAGED_PROFILE;
        }

        public boolean isCloneProfile() {
            return this == CLONE_PROFILE;
        }

        public boolean isPrivateProfile() {
            return this == PRIVATE_PROFILE;
        }

        /** Return whether this instance is of profile type. */
        public boolean isProfile() {
            // Other types are not supported
            return isManagedProfile() || isCloneProfile() || isPrivateProfile();
        }
    }

    public UserInfo(int userId, String userName, int flag, boolean isRunning) {
        mUserId = userId;
        mUserName = userName;
        mFlag = flag;
        mIsRunning = isRunning;
    }

    public UserInfo(int userId, String userName, int flag, boolean isRunning, String userType) {
        this(userId, userName, flag, isRunning);
        mUserType = userType;
    }

    public int userId() {
        return mUserId;
    }

    public String userName() {
        return mUserName;
    }

    public int flag() {
        return mFlag;
    }

    public boolean isRunning() {
        return mIsRunning;
    }

    public boolean isGuest() {
        return (mFlag & FLAG_GUEST) == FLAG_GUEST;
    }

    public boolean isPrimary() {
        return (mFlag & FLAG_PRIMARY) == FLAG_PRIMARY;
    }

    public boolean isSecondary() {
        return !isSystem() && (mFlag & FLAGS_NOT_SECONDARY) == 0;
    }

    public boolean isSystem() {
        return mUserId == USER_SYSTEM;
    }

    public boolean isNull() {
        return mUserId == USER_NULL;
    }

    public boolean isFull() {
        return (mFlag & FLAG_FULL) == FLAG_FULL;
    }

    public boolean isAdmin() {
        return (mFlag & FLAG_ADMIN) == FLAG_ADMIN;
    }

    /**
     * @deprecated Use {@link #isFull()} instead.
     */
    @Deprecated
    public boolean isMain() {
        return (mFlag & FLAG_MAIN) == FLAG_MAIN;
    }

    public boolean isManagedProfile() {
        return (mFlag & FLAG_MANAGED_PROFILE) == FLAG_MANAGED_PROFILE;
    }

    public boolean isCloneProfile() {
        return CLONE_PROFILE_TYPE.equals(mUserType);
    }

    public boolean isPrivateProfile() {
        return PRIVATE_PROFILE_TYPE.equals(mUserType);
    }

    public boolean isCommunalProfile() {
        return COMMUNAL_PROFILE_TYPE.equals(mUserType);
    }

    public boolean isEphemeral() {
        return (mFlag & FLAG_EPHEMERAL) == FLAG_EPHEMERAL;
    }

    public boolean isFlagForTesting() {
        return (mFlag & FLAG_FOR_TESTING) == FLAG_FOR_TESTING;
    }

    /** Return whether this instance is of the specified type. */
    public boolean isUserType(UserType userType, int currentUserId) {
        switch (userType) {
            case CURRENT:
                return mUserId == currentUserId;
            case GUEST:
                return isGuest();
            case PRIMARY:
                return isPrimary();
            case SYSTEM:
                return isSystem();
            case NULL:
                return isNull();
            case FULL:
                return isFull();
            case MAIN:
                return isMain();
            case SECONDARY:
                return isSecondary();
            case MANAGED_PROFILE:
                return isManagedProfile();
            case CLONE_PROFILE:
                return isCloneProfile();
            case COMMUNAL_PROFILE:
                return isCommunalProfile();
            case PRIVATE_PROFILE:
                return isPrivateProfile();
            default:
                throw new RuntimeException("Variant not covered: " + userType);
        }
    }

    @Override
    public String toString() {
        StringBuilder string =
                new StringBuilder("UserInfo[id=")
                        .append(mUserId)
                        .append(", name=")
                        .append(mUserName)
                        .append(", flags=0x")
                        .append(Integer.toHexString(mFlag));
        if (mFlag != 0) {
            string.append(" (");
            AtomicBoolean isFirst = new AtomicBoolean(true);
            // NOTE: flags are check in alphabetical order
            appendFlag(string, isFirst, mFlag, FLAG_ADMIN, "ADMIN");
            appendFlag(string, isFirst, mFlag, FLAG_EPHEMERAL, "EPHEMERAL");
            appendFlag(string, isFirst, mFlag, FLAG_FOR_TESTING, "FOR_TESTING");
            appendFlag(string, isFirst, mFlag, FLAG_FULL, "FULL");
            appendFlag(string, isFirst, mFlag, FLAG_GUEST, "GUEST");
            appendFlag(string, isFirst, mFlag, FLAG_MAIN, "MAIN");
            appendFlag(string, isFirst, mFlag, FLAG_MANAGED_PROFILE, "MANAGED_PROFILE");
            appendFlag(string, isFirst, mFlag, FLAG_PRIMARY, "PRIMARY");
            appendFlag(string, isFirst, mFlag, FLAG_PROFILE, "PROFILE");
            appendFlag(string, isFirst, mFlag, FLAG_RESTRICTED, "RESTRICTED");
            string.append(')');
        }
        if (mUserType != null) {
            string.append(", type=").append(mUserType);
        }
        if (mIsRunning) {
            string.append(", running");
        }
        return string.append(']').toString();
    }

    private static void appendFlag(
            StringBuilder string, AtomicBoolean isFirst, int flags, int flag, String name) {
        if ((flags & flag) == 0) {
            return;
        }
        if (isFirst.get()) {
            isFirst.set(false);
        } else {
            string.append("|");
        }
        string.append(name);
    }
}
