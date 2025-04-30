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

import com.android.tradefed.result.proto.TestRecordProto;

/** Enum representing possible outcomes of a test. */
public enum TestStatus {
    PASS("pass"),
    FAIL("fail"),
    IGNORED("ignored"),
    ASSUMPTION_FAILURE("assumptionFailure"),
    TEST_ERROR("testError"),
    TEST_SKIPPED("testSkipped");

    private final String value;

    private TestStatus(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static TestStatus fromDescription(TestRecordProto.FailureStatus status) {
        if (status == null) {
            return FAIL;
        }
        switch (status) {
            case TEST_FAILURE:
                return FAIL;
            case TIMED_OUT:
                return TEST_ERROR;
            case CANCELLED:
                return TEST_ERROR;
            case INFRA_FAILURE:
                return TEST_ERROR;
            case SYSTEM_UNDER_TEST_CRASHED:
                return TEST_ERROR;
            case NOT_EXECUTED:
                return FAIL;
            default:
                return FAIL;
        }
    }
}
