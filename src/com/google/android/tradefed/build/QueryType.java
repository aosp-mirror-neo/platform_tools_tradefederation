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

package com.google.android.tradefed.build;

/** Launch control queries types. */
public enum QueryType {
    /**
     * Get the latest successful build that has not been tested. Note: might be older than the last
     * build tested!
     */
    @Deprecated
    LATEST_GREEN_BUILD("LATEST-GREEN-CL"),

    /** Gets the latest build, regardless of what has been previously tested. */
    @Deprecated
    QUERY_LATEST_BUILD("QUERY-LATEST-CL"),

    /**
     * Gets build information for the provided build, and tells launch-control to mark it as tested.
     */
    NOTIFY_TEST_BUILD("NOTIFY-TEST-CL"),

    /** Requests launch control to remove given build from 'tested builds' set. */
    @Deprecated
    RESET_TEST_BUILD("RESET-TEST-CL"),

    /** Get build information for the provided build id. */
    @Deprecated
    GET_BUILD_DETAILS("GET-BUILD-DETAILS"),

    /**
     * Get the latest white-listed build that has not been tested. Only supported by the Launch
     * Control Proxy V2.
     */
    @Deprecated
    LATEST_WHITELISTED_CL("LATEST-WHITELISTED-CL"),

    /**
     * Gets the latest white-listed build, regardless of what has been previously tested. Only
     * supported by the Launch Control Proxy V2.
     */
    @Deprecated
    QUERY_LATEST_WHITELISTED_CL("QUERY-LATEST-WHITELISTED-CL"),

    /**
     * Requests launch control to decrement a given build's iteration count. Only supported by the
     * Launch Control Proxy V2.
     */
    @Deprecated
    DECREMENT_TEST_COUNT("DECREMENT-TEST-CL");

    private String mRemoteValue;

    /**
     * Create a {@link QueryType}.
     *
     * @param remoteValue the {@link String} value that represents the actual value passed to launch
     *     control.
     */
    QueryType(String remoteValue) {
        mRemoteValue = remoteValue;
    }

    /** Gets the on-the-wire protocol value for the {@link QueryType}. */
    String getRemoteValue() {
        return mRemoteValue;
    }
}
