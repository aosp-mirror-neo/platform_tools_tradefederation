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
package com.google.android.tradefed.sandbox;

import com.google.common.base.Strings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Parse and represents the data held in --invocation extra_builds={} sent from ATP. */
public class ExtraBuildsData {

    public static final String BUILD_ID_KEY = "build_id";
    public static final String BRANCH_KEY = "branch";
    public static final String BUILD_TARGET_KEY = "build_target";
    public static final String BUILD_OS_KEY = "build_os";
    public static final String BUILD_FLAVOR_KEY = "build_flavor";
    public static final String BUILD_PLATFORM_KEY = "build_platform";
    public static final String BUILD_ALIAS = "build_alias";

    private static final String NULL_STRING = "null";

    public String mBuildId;
    public String mBranch;
    public String mBuildTarget;
    public String mBuildPlatform;
    public String mBuildAlias;
    public String mBuildFlavor;
    public String mBuildOs;

    private ExtraBuildsData() {}

    /**
     * Parse directly from --invocation-data extra_builds field as specified by ATP.
     *
     * @param json The String representation of the JSON holding the data.
     * @return A list of {@link ExtraBuildsData} representing each extra builds.
     */
    public static List<ExtraBuildsData> parseFromJson(String json) throws JSONException {
        List<ExtraBuildsData> listData = new ArrayList<>();
        // Unescape in case the json was previously escaped.
        String unescapedJson = json.replace("\\\"", "\"");
        JSONArray mainArray = new JSONArray(unescapedJson);
        for (int i = 0; i < mainArray.length(); i++) {
            ExtraBuildsData data = new ExtraBuildsData();
            JSONObject obj = mainArray.getJSONObject(i);
            data.mBuildId = obj.getString(BUILD_ID_KEY);
            data.mBranch = obj.getString(BRANCH_KEY);
            data.mBuildTarget = obj.getString(BUILD_TARGET_KEY);

            String buildPlatform = obj.optString(BUILD_PLATFORM_KEY);
            if (!Strings.isNullOrEmpty(buildPlatform) && !NULL_STRING.equals(buildPlatform)) {
                data.mBuildPlatform = buildPlatform;
            }

            // TODO: Remove the special fallback when b/109717209 is fixed
            String buildFlavor = obj.optString(BUILD_FLAVOR_KEY);
            if (!Strings.isNullOrEmpty(buildFlavor) && !NULL_STRING.equals(buildFlavor)) {
                data.mBuildFlavor = buildFlavor;
            } else {
                // Special fallback: If we have a build platform use it for build-flavor
                if (buildPlatform != null) {
                    data.mBuildFlavor = buildPlatform;
                    data.mBuildOs = "linux";
                }
            }
            // Build os and platform will be removed eventually.
            String buildOs = obj.optString(BUILD_OS_KEY);
            if (!Strings.isNullOrEmpty(buildOs) && !NULL_STRING.equals(buildOs)) {
                data.mBuildOs = buildOs;
            }
            String buildAlias = obj.optString(BUILD_ALIAS);
            if (!Strings.isNullOrEmpty(buildAlias) && !NULL_STRING.equals(buildAlias)) {
                data.mBuildAlias = buildAlias;
            }
            listData.add(data);
        }

        return listData;
    }
}
