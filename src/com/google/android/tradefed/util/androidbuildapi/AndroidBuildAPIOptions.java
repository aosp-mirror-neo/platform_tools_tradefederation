/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.google.android.tradefed.util.androidbuildapi;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/** Hostwide configuration options for the Android Build API client. */
@OptionClass(alias = "android-build", global_namespace = false)
public class AndroidBuildAPIOptions {
    public static final String TYPE_NAME = "android-build";

    @Deprecated
    @Option(
            name = "service-account",
            description =
                    "The service account to authenticate as."
                            + "Deprecated. Use json-key-file instead.")
    private String mServiceAccount;

    @Deprecated
    @Option(
            name = "key-file",
            description =
                    "The key file to authenticate with. Deprecated. Use json-key-file instead.")
    private File mKeyFile;

    @Option(
            name = "json-key-file",
            description =
                    "The json key file to authenticate with."
                            + "The json key file already has the service account.")
    private File mJsonKeyFile;

    @Option(
            name = "auth-token-file",
            description = "The file contains the token to authenticate with.")
    private File mAuthTokenFile;

    // FIXME(b/71646294): Consolidate build environment default values into a single location, and
    // pass build environment as an android-build option instead of a invocation context option.
    @Option(name = "environment", description = "The build environment to communicate with")
    private String mEnvironment = "prod";

    @Option(name = "connect-timeout", description = "HTTP connect timeout in ms.")
    private int mConnectTimeout = 60000;

    @Option(name = "read-timeout", description = "HTTP read timeout in ms.")
    private int mReadTimeout = 60000;

    @Option(name = "ab-direct-download", description = "Enable direct download for AB downloads")
    private boolean mDirectDownload = false;

    private static final int MB = 0x100000;

    @Option(
            name = "ab-chunk-size",
            description =
                    "Chunk size to use when downloading through AB, inop when direct download is"
                            + " set.")
    private int mChunkSize = 32 * MB;

    @Option(
            name = "download-exclusion-patterns",
            description =
                    "List of file patterns that will be excluded from candidate download list")
    private Set<String> mDownloadExclusionPatterns =
            new LinkedHashSet<>(
                    Set.of(
                            "^attempts/",
                            "^licensetexts/",
                            "^shareprojects/",
                            "^apilint/",
                            "^soong_ui/",
                            ".*-symbols(-mapping)?-.*\\.(zip|textproto)$"));

    @Deprecated
    public String getServiceAccount() {
        return mServiceAccount;
    }

    @Deprecated
    public File getKeyFile() {
        return mKeyFile;
    }

    public String getEnvironment() {
        return mEnvironment;
    }

    public File getJsonKeyFile() {
        return mJsonKeyFile;
    }

    public int getConnectTimeout() {
        return mConnectTimeout;
    }

    public int getReadTimeout() {
        return mReadTimeout;
    }

    public File getAuthTokenFile() {
        return mAuthTokenFile;
    }

    public boolean isDirectDownload() {
        return mDirectDownload;
    }

    public int getChunkSize() {
        return mChunkSize;
    }

    public Set<String> getDownloadExclusionPatterns() {
        return mDownloadExclusionPatterns;
    }
}
