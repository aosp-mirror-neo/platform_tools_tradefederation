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

package com.android.tradefed.device.metric;

/** A class that stores starting offsets of a log file for different test levels. */
public class LogOffsets {

    private Integer mTestCaseOffset = null;
    private Integer mTestRunOffset = null;
    private Integer mModuleOffset = null;

    public Integer getOffset(IMetricCollector.MetricCollectionLevel level) {
        switch (level) {
            case TEST_CASE:
                return mTestCaseOffset;
            case TEST_RUN:
                return mTestRunOffset;
            case MODULE:
                return mModuleOffset;
            default:
                // should never happen
                return null;
        }
    }

    public void setOffset(Integer offset, IMetricCollector.MetricCollectionLevel level) {
        switch (level) {
            case TEST_CASE:
                mTestCaseOffset = offset;
                break;
            case TEST_RUN:
                mTestRunOffset = offset;
                break;
            case MODULE:
                mModuleOffset = offset;
                break;
            default:
                // should never happen
                break;
        }
    }
}
