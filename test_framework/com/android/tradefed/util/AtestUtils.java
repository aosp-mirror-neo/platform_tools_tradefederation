/*
* Copyright (C) 2026 The Android Open Source Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may
may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.tradefed.util;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.incremental.IIncrementalSetup;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.InstrumentationTest;
import com.android.tradefed.testtype.IsolatedHostTest;

/** A utility class for Atest runners. */
public class AtestUtils {

    /** Enum representing the spec for incremental setup. */
    public static enum IncrementalSetupEnabled {
        UNSPECIFIED,
        NO,
        YES,
    }

    /** Helper to disable TargetPreparers of a test. */
    public static void disableTargetPreparers(
            IConfiguration testConfig, boolean skipSetUp, boolean skipTearDown) {
        for (ITargetPreparer targetPreparer : testConfig.getTargetPreparers()) {
            if (skipSetUp) {
                CLog.d(
                        "%s: Disabling Target Preparer (%s)",
                        testConfig.getName(), targetPreparer.getClass().getSimpleName());
                targetPreparer.setDisable(true);
            } else if (skipTearDown) {
                CLog.d(
                        "%s: Disabling Target Preparer TearDown (%s)",
                        testConfig.getName(), targetPreparer.getClass().getSimpleName());
                targetPreparer.setDisableTearDown(true);
            }
        }
    }

    /**
     * Helper to set incremental setup enabled or disabled to TargetPreparers of a test.
     *
     * @param testConfig the test config which contains all target preparers.
     * @param shouldEnable {@code true} to enable incremental setup, otherwise disable incremental
     *     setup.
     */
    public static void setIncrementalSetupEnabledForTargetPreparers(
            IConfiguration testConfig, boolean shouldEnable) {
        for (IDeviceConfiguration deviceConfig : testConfig.getDeviceConfig()) {
            for (ITargetPreparer targetPreparer : deviceConfig.getTargetPreparers()) {
                if (targetPreparer instanceof IIncrementalSetup) {
                    ((IIncrementalSetup) targetPreparer).setIncrementalSetupEnabled(shouldEnable);
                }
            }
        }
    }

    /** Helper to attach the debugger to any Instrumentation tests in the config. */
    public static void addDebugger(IConfiguration testConfig) {
        for (IRemoteTest test : testConfig.getTests()) {
            if (test instanceof InstrumentationTest) {
                ((InstrumentationTest) test).setDebug(true);
            }
            if (test instanceof IsolatedHostTest) {
                ((IsolatedHostTest) test).setDebug(true);
            }
        }
    }
}
