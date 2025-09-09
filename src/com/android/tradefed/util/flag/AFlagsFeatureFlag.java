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
package com.android.tradefed.util.flag;

/** A feature flag description, as parsed from `aflags list`. */
public class AFlagsFeatureFlag {
    /** The state of a flag (i.e. whether it's enabled or not). */
    public enum State {
        /** Flag is disabled (or pending disabled). */
        DISABLED,

        /** Flag is enabled (or pending enabled). */
        ENABLED,

        /** Unknown state (generally only used for pending state to imply no pending state. */
        NONE
    }

    /** Where this flag was last set from. */
    public enum Setter {
        /** Flag is at its default value. */
        DEFAULT,

        /** Flag is set as a local override. */
        LOCAL,

        /** Flag override is from an unknown source. */
        UNKNOWN
    }

    /** Whether this flag is mutable or not. */
    public enum Mutability {
        /** Flag is read-only. */
        READ_ONLY,

        /** Flag is read-write. */
        READ_WRITE
    }

    private final String mFlagName;
    private final State mCurrentState;
    private final State mPendingState;
    private final Setter mSetter;
    private final Mutability mMutability;
    private final String mContainer;

    /**
     * Default constructor.
     *
     * @param flagString A single line from aflags list, e.g. {@code com.my.flag enabled
     *     (->disabled) local read-write system}.
     */
    public AFlagsFeatureFlag(String flagString) {
        // Parse a flag of the format:
        // com.my.flag          enabled  (->disabled) local   read-write system

        String[] parts = flagString.split("\\s+");
        if (parts.length < 6) {
            throw new IllegalArgumentException(
                    "Expected at least six space-delimited parts in the flag string");
        }

        mFlagName = parts[0];
        mCurrentState = parseCurrentState(parts[1]);
        mPendingState = parsePendingState(parts[2]);
        mSetter = parseSetter(parts[3]);
        mMutability = parseMutability(parts[4]);
        mContainer = parts[5];
    }

    /** Returns tne full name of the flag (e.g. com.mypackage.foo_flag). */
    public String getFlagName() {
        return mFlagName;
    }

    /**
     * Returns the current state of this flag (e.g. {@link State#ENABLED} or {@link State#DISABLED}.
     * Will not return {@link State#NONE}.
     */
    public State getCurrentState() {
        return mCurrentState;
    }

    /**
     * Returns the pending state of the flag (i.e. what it will be set to after a reboot). May
     * return {@link State#NONE} if there is no state pending.
     */
    public State getPendingState() {
        return mPendingState;
    }

    /** Returns what last caused this flag value to change. */
    public Setter getSetter() {
        return mSetter;
    }

    /** Returns whether this flag can be dynamically set. */
    public Mutability getMutability() {
        return mMutability;
    }

    /** Returns the container for this flag. */
    public String getContainer() {
        return mContainer;
    }

    private static State parseCurrentState(String state) {
        switch (state) {
            case "enabled":
                return State.ENABLED;
            case "disabled":
                return State.DISABLED;
            default:
                throw new IllegalArgumentException("Unknown current state " + state);
        }
    }

    private static State parsePendingState(String pendingState) {
        switch (pendingState) {
            case "->enabled":
                return State.ENABLED;
            case "->disabled":
                return State.DISABLED;
            case "-":
                return State.NONE;
            default:
                throw new IllegalArgumentException("Unknown pending state " + pendingState);
        }
    }

    private static Mutability parseMutability(String mutability) {
        switch (mutability) {
            case "read-only":
                return Mutability.READ_ONLY;
            case "read-write":
                return Mutability.READ_WRITE;
            default:
                throw new IllegalArgumentException("Unknown mutability " + mutability);
        }
    }

    private static Setter parseSetter(String setter) {
        switch (setter) {
            case "default":
                return Setter.DEFAULT;
            case "local":
                return Setter.LOCAL;
            default:
                return Setter.UNKNOWN;
        }
    }
}
