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
package com.google.android.tradefed.build.content;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and expose information for the logs/last_build.info file. */
public class LastBuildInfo {
    private static final Pattern LAST_BUILD_PATTERN = Pattern.compile("(.*?) (.*) @ (.*)/[0-9]");

    public final String branch;
    public final String buildFlavor;
    public final String buildId;

    public LastBuildInfo(String branch, String buildFlavor, String buildId) {
        this.branch = branch;
        this.buildFlavor = buildFlavor;
        this.buildId = buildId;
    }

    /** Parse and returns the information from last build. */
    static LastBuildInfo parseFile(File lastBuildInfo, String buildFlavor) {
        if (lastBuildInfo == null || !lastBuildInfo.exists()) {
            return null;
        }
        try {
            Matcher m = LAST_BUILD_PATTERN.matcher(FileUtil.readStringFromFile(lastBuildInfo));
            if (m.find()) {
                return new LastBuildInfo(m.group(1), buildFlavor, m.group(3));
            }
        } catch (IOException e) {
            CLog.e(e);
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("%s:%s:%s", branch, buildFlavor, buildId);
    }
}
