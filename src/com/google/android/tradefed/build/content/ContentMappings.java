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

import com.google.common.collect.ImmutableSet;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ContentMappings {

    public static final Map<String, Set<String>> WORKDIR_COMMON_DIR = new HashMap<>();

    static {
        WORKDIR_COMMON_DIR.put(
                "art-host-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "host-unit-tests.zip",
                Collections
                        .emptySet()); // host-unit-tests links shared libs to the module using them
        // (b/351909435)
        WORKDIR_COMMON_DIR.put(
                "robolectric-tests.zip", ImmutableSet.of("host/testcases/android-all/"));
        WORKDIR_COMMON_DIR.put(
                "device-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "performance-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "device-platinum-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "camera-hal-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "device-pixel-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "general-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "automotive-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "automotive-sdv-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "automotive-general-tests.zip",
                ImmutableSet.of("host/testcases/lib/", "host/testcases/lib64/"));
        WORKDIR_COMMON_DIR.put(
                "ravenwood-tests.zip",
                ImmutableSet.of(
                        "host/testcases/ravenwood-utils/", "host/testcases/ravenwood-runtime/"));
    }
}
