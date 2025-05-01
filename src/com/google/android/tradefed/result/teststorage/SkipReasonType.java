/*
 * Copyright (C) 2023 The Android Open Source Project
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

// This should be kept in sync with AnTS api (wireless.android.busytown.proto.SkippedReasonType).
enum SkipReasonType {
    REASON_UNSPECIFIED("reasonUnspecified"),
    REASON_DEMOTION("reasonDemotion");

    private final String value;

    private SkipReasonType(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return this.value;
    }
}
