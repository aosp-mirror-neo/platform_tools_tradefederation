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

import com.android.tradefed.config.ConfigurationDescriptor.LocalTestRunner;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.LocalRunInstructionBuilder;

import java.util.List;

/**
 * A helper class for generating instructions for running tests using atest. To enable instructions
 * invocation attribute "local-test-runner" must be set.
 */
final class LocalInstructions {

    private static final String LOCAL_RUNNER = "local-test-runner";

    private final LocalTestRunner mRunner;
    private final IInvocationContext mModuleContext;

    public LocalInstructions(IInvocationContext invContext, IInvocationContext moduleContext) {
        mRunner = getRunner(invContext);
        mModuleContext = moduleContext;
    }

    /** Returns true if the local instructions are available for the current module. */
    private boolean available() {
        return mRunner != null && mModuleContext != null;
    }

    /**
     * Generates local instructions for a given test if available. If instructions are not available
     * will return an empty String.
     */
    public String forTest(TestDescription test) {
        if (!available()) {
            return "";
        }

        return LocalRunInstructionBuilder.getCommand(
                mModuleContext.getConfigurationDescriptor(), test, mRunner);
    }

    /** Checks invocation context to figure out which local runner is being used. */
    private static LocalTestRunner getRunner(IInvocationContext context) {
        List<String> values = context.getAttributes().get(LOCAL_RUNNER);
        if (values == null || values.isEmpty()) {
            return null;
        }

        try {
            return LocalTestRunner.valueOf(values.get(0).toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
