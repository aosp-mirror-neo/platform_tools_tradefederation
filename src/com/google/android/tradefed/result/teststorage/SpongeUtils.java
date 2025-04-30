/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.tradefed.result.teststorage;

import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.net.HttpHelper;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Class to hold helper methods for dealing with Sponge. */
public final class SpongeUtils {

    private static final Pattern SPONGE_V1_URL_PATTERN =
            Pattern.compile(
                    "(http[s]?:\\/\\/sponge(-qa)?.corp.google.com)\\/([^\\?\\s]+)\\?"
                            + "([^\\s]*&)?id=([^&\\s]*)(&[^\\s]*)?");

    private static final String BASE_SPONGE_URL = "https://sponge2.corp.google.com";

    /**
     * Creates a Sponge V1 URL for a test run. It adds target=runName URL parameter to the base
     * Sponge URL.
     *
     * @param url - base Sponge URL.
     * @param runName name of a test run
     * @return A sponge URL with additional target parameter set to runName.
     */
    public static String createSpongeV1Url(String url, String runName) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        Matcher m = SPONGE_V1_URL_PATTERN.matcher(url);
        if (!m.matches()) {
            return url;
        }

        String baseSpongeUrl = m.group(1);
        String invocationId = m.group(5);

        if (baseSpongeUrl == null || invocationId == null || runName == null) {
            return url;
        }

        MultiMap<String, String> paramMap = new MultiMap<>();
        paramMap.put("show", "FAILED");
        paramMap.put("sortBy", "STATUS");
        paramMap.put("id", invocationId);
        paramMap.put("target", runName);
        return new HttpHelper().buildUrl(String.format("%s/target", baseSpongeUrl), paramMap);
    }

    /**
     * Creates a Sponge V2 URL for a test run. It sets target to runName.
     *
     * @param invocationId - Sponge invocation id.
     * @param runName name of a test run
     * @return A sponge URL with additional target set to runName.
     */
    public static String createSpongeV2Url(String invocationId, String runName) {
        if (invocationId == null || invocationId.isEmpty()) {
            return "";
        }

        if (runName == null) {
            return String.format("%s/%s", BASE_SPONGE_URL, invocationId);
        }

        return String.format("%s/%s/targets/%s/tests", BASE_SPONGE_URL, invocationId, runName);
    }

    /**
     * Create a Sponge invocation id.
     *
     * @return A random Sponge invocation id.
     */
    public static String createInvocationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Gets the https url to the invocation displayed on sponge v2 UI.
     *
     * @return the https url or {@code null} if not available
     */
    public static String getInvocationUrl(String invocationId) {
        if (invocationId == null) {
            return null;
        }
        return String.format("%s/%s", BASE_SPONGE_URL, invocationId);
    }
}
