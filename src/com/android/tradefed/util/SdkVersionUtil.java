/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tradefed.util;

public class SdkVersionUtil {

    // Replicated from android.os.Build.VERSION_CODES_FULL
    // Check the latest value from the source.
    private static final int SDK_INT_MULTIPLIER = 100000;

    /**
     * Parses a full SDK version string into an integer representation.
     * This logic is replicated from android.os.Build.parseFullVersion
     * to avoid host-side dependencies on device-specific Android libraries.
     *
     * @param version The version string (e.g., "36.1", "35")
     * @return The combined SdkIntFull integer.
     * @throws IllegalArgumentException if the version string is invalid.
     */
    public static int parseFullVersion(String sdkFull) {
        int index = sdkFull.indexOf('.');
        int major;
        int minor = 0;
        try {
            if (index == -1) {
                major = Integer.parseInt(sdkFull);
            } else {
                major = Integer.parseInt(sdkFull.substring(0, index));
                minor = Integer.parseInt(sdkFull.substring(index + 1));
            }
            if (major < 0) {
                throw new NumberFormatException("negative major version");
            }
            if (major >= 21474) {  // The first 5 digits of 2147483647 (2^31 - 1)
                throw new NumberFormatException("major version too large, must be less than 21474");
            }
            if (minor < 0) {
                throw new NumberFormatException("negative minor version");
            }
            if (minor >= SDK_INT_MULTIPLIER) {
                throw new NumberFormatException(
                        "minor version too large, must be less than " + SDK_INT_MULTIPLIER);
            }
            return major * SDK_INT_MULTIPLIER + minor;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "failed to parse '" + sdkFull + "' as a major.minor version code", e);
        }
    }

    /**
     * Extracts the major version from an SdkIntFull.
     * Replicated from android.os.Build.getMajorSdkVersion
     */
    public static int getMajorSdkVersion(int sdkIntFull) {
        return sdkIntFull / SDK_INT_MULTIPLIER;
    }

    /**
     * Extracts the minor version from an SdkIntFull.
     * Replicated from android.os.Build.getMinorSdkVersion
     */
    public static int getMinorSdkVersion(int sdkIntFull) {
        return sdkIntFull % SDK_INT_MULTIPLIER;
    }

    private SdkVersionUtil() {}
}
