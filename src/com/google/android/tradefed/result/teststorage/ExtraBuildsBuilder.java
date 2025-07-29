/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static java.util.stream.Collectors.toList;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.api.services.androidbuildinternal.v3.model.BuildDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A helper class to parse the extra builds args and update the invocation's extra builds field. */
final class ExtraBuildsBuilder {

    private static final String EXTRA_BUILDS_ATTR = "extra_builds";
    private static final String DEFAULT_BUILDPROVIDER = "androidbuild";

    private String extraBuildsAttribute;

    /**
     * Creates a builder object from the extra-builds argument of the current invocation context.
     */
    public ExtraBuildsBuilder(IInvocationContext context) {
        List<String> extraBuilds = context.getAttributes().get(EXTRA_BUILDS_ATTR);
        if (extraBuilds == null || extraBuilds.isEmpty()) {
            extraBuildsAttribute = "";
            return;
        }
        extraBuildsAttribute = extraBuilds.get(0);
    }

    /**
     * Creates an extra builds field for an invocation. Takes the current extra builds and add the
     * builds found in the extra-builds command line argument. Will only add builds that are missing
     * from the invocation. If there are no changes, returns an empty list.
     */
    public List<BuildDescriptor> build(List<BuildDescriptor> currentBuilds) {
        if (extraBuildsAttribute.isEmpty()) {
            return Collections.emptyList();
        }

        Set<BuildDescriptor> newBuilds = new LinkedHashSet<>();
        Set<BuildDescriptor> result = new LinkedHashSet<>();
        if (currentBuilds != null) {
            result.addAll(currentBuilds);
        }

        try {
            JSONArray extraBuildsArray = tryParseJSONArray(extraBuildsAttribute);
            for (int i = 0; i < extraBuildsArray.length(); i++) {
                JSONObject extraBuild = extraBuildsArray.getJSONObject(i);
                newBuilds.add(toBuild(extraBuild));
            }
        } catch (JSONException e) {
            CLog.e("Could not parse extra builds");
            CLog.e("%s", e);
        }

        if (newBuilds.isEmpty()) {
            return Collections.emptyList();
        }
        if (newBuilds.stream().allMatch(result::contains)) {
            return Collections.emptyList();
        }
        result.addAll(newBuilds);
        return Collections.unmodifiableList(result.stream().collect(toList()));
    }

    /** Returns true if there are no extra builds in the invocation attributes. */
    public boolean isEmpty() {
        return extraBuildsAttribute.isEmpty();
    }

    private static BuildDescriptor toBuild(JSONObject arg) throws JSONException {
        return new BuildDescriptor()
                .setBuildProvider(DEFAULT_BUILDPROVIDER)
                .setBuildId(arg.getString("build_id"))
                .setBranch(arg.getString("branch"))
                .setBuildTarget(arg.getString("build_target"));
    }

    // Will parse extra builds in json array. This is a workaround for
    // b/157228161. Sometimes the json string is escaped too many times, so
    // when it gets here quotes around strings still have a slash in front of them.
    private static JSONArray tryParseJSONArray(String in) throws JSONException {
        JSONException error = null;
        try {
            return new JSONArray(in);
        } catch (JSONException e) {
            error = e;
        }

        // If the parsing failed unescape and try again.
        String escaped = in.replace("\\\"", "\"");
        try {
            return new JSONArray(escaped);
        } catch (JSONException e) {
            // ignore
        }
        // If the unescape failed we want to throw an error that
        // contains the initial string.
        throw error;
    }
}
