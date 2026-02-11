/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tradefed.testtype.suite;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.SubprocessResultsReporter;
import com.android.tradefed.util.AtestUtils;

import com.google.common.annotations.VisibleForTesting;

import java.util.LinkedHashMap;
import java.util.List;

// TODO: b/462794425 - Support --atest-include-filter.
/** Implementation of {@link ITestSuite} */
public class AtestExecutionPlanSuiteRunner extends ExecutionPlanSuiteRunner {

    @Option(name = "all-abi", description = "Determine run all abi or not.")
    boolean mAllAbi = false;

    @Option(
            name = "wait-for-debugger",
            description = "For InstrumentationTests, we pass debug to the instrumentation run.")
    boolean mDebug = false;

    @Option(
            name = "disable-target-preparers",
            description =
                    "Skip the target preparer steps enumerated in test config. Skips the teardown"
                            + " step as well.")
    boolean mSkipSetUp = false;

    @Option(name = "disable-teardown", description = "Skip the teardown of the target preparers.")
    boolean mSkipTearDown = false;

    @Option(
            name = "subprocess-report-port",
            description = "the port where to connect to send the" + "events.")
    Integer mReportPort = null;

    @Option(
            name = "incremental-setup",
            description =
                    "Indicates the user specification of whether to enable incremental setup, "
                            + "default to UNSPECIFIED.")
    AtestUtils.IncrementalSetupEnabled mIncrementalSetupEnabled =
            AtestUtils.IncrementalSetupEnabled.UNSPECIFIED;

    public AtestExecutionPlanSuiteRunner() {
        setMultiDeviceStrategy(MultiDeviceModuleStrategy.RUN);
    }

    @Override
    public LinkedHashMap<String, IConfiguration> loadTests() {
        // atest only needs to run on primary or specified abi.
        if (getRequestedAbi() == null && !mAllAbi) {
            setPrimaryAbiRun(true);
        }
        LinkedHashMap<String, IConfiguration> configMap = callSuperLoadTests();
        loadTestsInternal(configMap);
        return configMap;
    }

    @VisibleForTesting
    LinkedHashMap<String, IConfiguration> callSuperLoadTests() {
        return super.loadTests();
    }

    @VisibleForTesting
    void loadTestsInternal(LinkedHashMap<String, IConfiguration> configMap) {
        for (IConfiguration testConfig : configMap.values()) {
            if (mSkipSetUp || mSkipTearDown) {
                AtestUtils.disableTargetPreparers(testConfig, mSkipSetUp, mSkipTearDown);
            }
            if (mDebug) {
                AtestUtils.addDebugger(testConfig);
            }

            if (mIncrementalSetupEnabled == AtestUtils.IncrementalSetupEnabled.YES) {
                AtestUtils.setIncrementalSetupEnabledForTargetPreparers(
                        testConfig, /* shouldEnable= */ true);
            } else if (mIncrementalSetupEnabled == AtestUtils.IncrementalSetupEnabled.NO) {
                AtestUtils.setIncrementalSetupEnabledForTargetPreparers(
                        testConfig, /* shouldEnable= */ false);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected List<ITestInvocationListener> createModuleListeners() {
        List<ITestInvocationListener> listeners = super.createModuleListeners();
        if (mReportPort != null) {
            SubprocessResultsReporter subprocessResult = new SubprocessResultsReporter();
            OptionCopier.copyOptionsNoThrow(this, subprocessResult);
            listeners.add(subprocessResult);
        }
        return listeners;
    }
}
