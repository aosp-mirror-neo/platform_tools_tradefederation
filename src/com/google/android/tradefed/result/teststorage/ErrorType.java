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

import com.android.tradefed.result.proto.TestRecordProto;

// This should be kept in sync with AnTS api (wireless.android.busytown.proto.ErrorType).
enum ErrorType {
    ERROR_TYPE_UNSPECIFIED("errorTypeUnspecified"),
    // The test in progress was the reason for the failure.
    TEST_FAILURE("testFailure"),
    // A timeout condition on the operation in progress occurred.
    TIMEOUT("timeout"),
    // The test in progress was cancelled.
    TEST_CANCELLED("testCancelled"),
    // A failure attributed to something not functioning properly.
    INFRA_ERROR("infraError"),
    // System under test crashed and caused the test to fail.
    SYSTEM_UNDER_TEST_CRASHED("systemUnderTestCrashed"),
    // The test was expected to run but did not.
    NOT_EXECUTED("notExecuted"),
    // System under test became unavailable and never came back available again.
    LOST_SYSTEM_UNDER_TEST("lostSystemUnderTest"),
    // Represent an error caused by an unmet dependency that the current infra
    // depends on. For example: Unfound resources, Device error, Hardware issue
    // (lab host, device wear), Underlying tools
    DEPENDENCY_ISSUE("dependencyIssue"),
    // Represent an error caused by the input from the end user. For example:
    // Unexpected option combination, Configuration error, Bad flags
    CUSTOMER_ISSUE("customerIssue");

    private final String value;

    private ErrorType(String value) {
        this.value = value;
    }

    public static ErrorType create(TestRecordProto.FailureStatus status) {
        if (status == null) {
            return ErrorType.ERROR_TYPE_UNSPECIFIED;
        }
        switch (status) {
            case TEST_FAILURE:
                return ErrorType.TEST_FAILURE;
            case TIMED_OUT:
                return ErrorType.TIMEOUT;
            case CANCELLED:
                return ErrorType.TEST_CANCELLED;
            case INFRA_FAILURE:
                return ErrorType.INFRA_ERROR;
            case SYSTEM_UNDER_TEST_CRASHED:
                return ErrorType.SYSTEM_UNDER_TEST_CRASHED;
            case NOT_EXECUTED:
                return ErrorType.NOT_EXECUTED;
            case LOST_SYSTEM_UNDER_TEST:
                return ErrorType.LOST_SYSTEM_UNDER_TEST;
            case DEPENDENCY_ISSUE:
                return ErrorType.DEPENDENCY_ISSUE;
            case CUSTOMER_ISSUE:
                return ErrorType.CUSTOMER_ISSUE;
            default:
                return ErrorType.ERROR_TYPE_UNSPECIFIED;
        }
    }

    @Override
    public String toString() {
        return this.value;
    }
}
