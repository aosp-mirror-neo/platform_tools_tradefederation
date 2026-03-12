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

package com.google.android.tradefed.result;


import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.result.ITestSummaryListener;
import com.android.tradefed.result.TestSummary;

import com.google.api.services.androidbuildinternal.Androidbuildinternal;

import java.util.List;

/** Reports test results to Android Build API. */
@OptionClass(alias = "build-api-test-result-reporter")
public class AndroidBuildResultReporter extends CollectingTestListener
        implements ITestSummaryListener {
    public static final String STATUS_IN_PROGRESS = "inProgress";
    public static final String STATUS_PASS = "completePass";
    public static final String STATUS_FAIL = "completeFail";
    public static final String STATUS_ERROR = "error";
    static final String BUILD_ATTEMPT_ID = "build_attempt_id";
    static final String DEFAULT_BUILD_ATTEMPT_ID = "0";
    static final String TEST_RESULT_ID = "test_result_id";

    @Option(
            name = "test-result-id",
            description =
                    "Test result id is used to report test resultsto build API. If the test result"
                            + " id is provided in command options, it is impliedthat the test was"
                            + " requested from ATP and ATP will report the result back.")
    private Long mTestResultId = null;

    public AndroidBuildResultReporter() {}

    /**
     * Create a new AndroidBuildResultReporter. Exposed for testing.
     *
     * @param client
     */
    AndroidBuildResultReporter(Androidbuildinternal client) {

    }

    /** {@inheritDoc} */
    @Override
    public void putSummary(List<TestSummary> summaries) {
    }

    /** {@inheritDoc} */
    @Override
    public void invocationStarted(IInvocationContext context) {

    }


    /** {@inheritDoc} */
    @Override
    public void invocationFailed(Throwable cause) {

    }

    @Override
    public void invocationEnded(long elapsedTime) {

    }
}
