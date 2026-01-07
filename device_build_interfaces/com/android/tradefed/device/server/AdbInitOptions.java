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
package com.android.tradefed.device.server;

import com.android.annotations.NonNull;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * Options for initialization of AndroidDebugBridge.
 *
 * <p>See {@link AdbInitOptions.Builder} for a list of options and their default values.
 */
public class AdbInitOptions {
    /** Default init options. See {@link AdbInitOptions.Builder} for default values. */
    public static final AdbInitOptions DEFAULT = builder().build();


    /**
     * Enable user managed ADB mode where ddmlib will not start, restart, or terminate the ADB
     * server.
     */
    public final boolean userManagedAdbMode;

    /**
     * ADB server port of the user managed ADB server. Only in effect when in user managed ADB mode.
     */
    public final int userManagedAdbPort;

    /** Environment variables specifically for the ADB server process. */
    public final ImmutableMap<String, String> adbEnvVars;

    /**
     * @return a new builder with default values.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link AdbInitOptions.Builder} for default values. */
    private AdbInitOptions(
            boolean userManagedAdbMode,
            int userManagedAdbPort,
            ImmutableMap<String, String> adbEnvVars) {
        this.userManagedAdbMode = userManagedAdbMode;
        this.userManagedAdbPort = userManagedAdbPort;
        this.adbEnvVars = adbEnvVars;
    }

    /**
     * Builds initialization options for ADB.
     *
     * <p>Default settings are:
     *
     * <ul>
     *   <li>userManagedAdbMode = false
     * </ul>
     */
    public static class Builder {
        boolean userManagedAdbMode = false;
        int userManagedAdbPort = 0;
        ImmutableMap.Builder<String, String> envVarBuilder = ImmutableMap.builder();

        /**
         * See {@link AdbInitOptions#userManagedAdbMode} and {@link
         * AdbInitOptions#userManagedAdbPort}.
         */
        public Builder enableUserManagedAdbMode(int port) {
            userManagedAdbMode = true;
            userManagedAdbPort = port;
            return this;
        }

        /**
         * Add an environment variable for the ADB process. Note these environment variables won't
         * be used in user managed ADB mode because ADB server management is entirely up to the
         * user.
         */
        public Builder withEnv(@NonNull String key, String value) {
            envVarBuilder.put(key, value);
            return this;
        }

        /**
         * Add all environment variables from the given map for the ADB process. Note these
         * environment variables won't be used in user managed ADB mode because ADB server
         * management is entirely up to the user.
         */
        public Builder withEnv(@NonNull Map<String, String> envVars) {
            envVarBuilder.putAll(envVars);
            return this;
        }

        public AdbInitOptions build() {
            return new AdbInitOptions(
                    userManagedAdbMode, userManagedAdbPort, envVarBuilder.build());
        }
    }
}
