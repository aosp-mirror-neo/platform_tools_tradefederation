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

import com.google.android.tradefed.config.remote.AndroidBuildRemoteFileResolver;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helper class to parse a Launch Control path into its constituent parts */
public class LCUtil {

    public static final String BRANCH = "branch";
    public static final String BUILD_ID = "bid";
    public static final String FILENAME = "filename";
    public static final String FLAVOR = "flavor";
    public static final String KERNEL = "kernel";
    public static final String KEY = "key";
    public static final String OS = "os";

    private LCUtil() {
        // hidden
    }

    private static final Pattern ATTRIBUTE_PATTERN =
            Pattern.compile(
                    //        key(opt)      branch    os                  flavor    bid
                    // filename
                    "(?:([^:]+):)? (.+?   ) -([^-]*linux|mac)   -([^/]+ )/ ([A-Z]?\\d+)/ (.+     "
                            + " )",
                    Pattern.COMMENTS);

    // Pattern to match a remote path startiing with a protocol (ab://)
    private static final Pattern PROTOCOL_AB_ATTRIBUTE_PATTERN =
            Pattern.compile(
                    //       protocol   branch    flavor    bid       filename
                    "ab:       /([^/]+)  /([^/]+)  /([^/]+)  /([^/]+)  ", Pattern.COMMENTS);

    private static final Pattern KERNEL_ATTRIBUTE_PATTERN =
            Pattern.compile(
                    //        kernel-branch   sha1              filename
                    "([^/]+        )/([a-fA-F0-9]{40})/(.+      )", Pattern.COMMENTS);

    /**
     * Parse a line like "userdata:git_master-linux-yakju-tests/242537/userdata.img" into a Map like
     * "{key:userdata, branch:git_master, flavor:yakju-tests, bid:242537, file:userdata.img}" It
     * also supports parsing remote path started with protocol, e.g.,
     * "ab:/aosp_master/yakju-userdebug/P123/device-tests.zip" will be parsed to a Map like
     * "{branch:aosp_master, flavor:yakju-userdebug, bid:P123, filename:device-tests.zip}"
     */
    public static Map<String, String> parseAttributeLine(String line) {
        Map<String, String> out = new HashMap<String, String>();
        Matcher m = ATTRIBUTE_PATTERN.matcher(line);
        if (m.matches()) {
            // key may be missing
            out.put(KEY, m.group(1));
            out.put(BRANCH, m.group(2));
            out.put(OS, m.group(3));
            out.put(FLAVOR, m.group(4));
            out.put(BUILD_ID, m.group(5));
            out.put(FILENAME, m.group(6));
            return out;
        }
        m = KERNEL_ATTRIBUTE_PATTERN.matcher(line);
        if (m.matches()) {
            // key may be missing
            out.put(KERNEL, "");
            out.put(BRANCH, m.group(1));
            out.put(BUILD_ID, m.group(2));
            out.put(FILENAME, m.group(3));
            return out;
        }
        m = PROTOCOL_AB_ATTRIBUTE_PATTERN.matcher(line);
        if (m.matches()) {
            out.put(BRANCH, m.group(1));
            out.put(FLAVOR, m.group(2));
            out.put(BUILD_ID, m.group(3));
            out.put(FILENAME, m.group(4));
            return out;
        }
        return null;
    }

    /**
     * Gets the file path can be used to retrieve a resolver.
     *
     * @param remoteFilePath the remote path to the file to download in LaunchControlProvider, e.g.,
     *     aosp_master-linux-yakju-userdebug/P123/device-tests.zip
     */
    public static String createResolvableFilePath(String remoteFilePath) {
        Map<String, String> attribs = LCUtil.parseAttributeLine(remoteFilePath);
        List<String> requiredKeys = Arrays.asList(BUILD_ID, BRANCH, FILENAME, FLAVOR);
        for (String key : requiredKeys) {
            if (!attribs.containsKey(key)) {
                throw new RuntimeException(
                        String.format(
                                "Missing required key %s in remote file path %s.",
                                key, remoteFilePath));
            }
        }
        return String.format(
                "ab:/%s/%s/%s/%s",
                attribs.get(BRANCH),
                attribs.get(FLAVOR),
                attribs.get(BUILD_ID),
                attribs.get(FILENAME));
    }

    // Add reverse operation
    public static String reverseDynamicAbPath(String abDynamicLink) {
        Matcher m = AndroidBuildRemoteFileResolver.DOWNLOAD_PATTERN.matcher(abDynamicLink);
        if (!m.matches()) {
            return null;
        }
        String buildId = m.group(AndroidBuildRemoteFileResolver.BUILD_ID_STRING);
        String branch = m.group(AndroidBuildRemoteFileResolver.BUILD_BRANCH_STRING);
        String buildFlavor = m.group(AndroidBuildRemoteFileResolver.BUILD_TARGET_STRING);
        String pattern = m.group(AndroidBuildRemoteFileResolver.BUILD_PATTERN_STRING);
        return String.format("%s-linux-%s/%s/%s", branch, buildFlavor, buildId, pattern);
    }
}
