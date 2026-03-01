/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.SubprocessResultsReporter;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.util.AtestUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Implementation of {@link ITestSuite} */
public class AtestRunner extends BaseTestSuite {

    @Option(name = "all-abi", description = "Determine run all abi or not.")
    private boolean mAllAbi = false;

    @Option(
        name = "wait-for-debugger",
        description = "For InstrumentationTests, we pass debug to the instrumentation run."
    )
    private boolean mDebug = false;

    @Option(
        name = "disable-target-preparers",
        description =
                "Skip the target preparer steps enumerated in test config. Skips the teardown step "
                        + "as well."
    )
    private boolean mSkipSetUp = false;

    @Option(name = "disable-teardown", description = "Skip the teardown of the target preparers.")
    private boolean mSkipTearDown = false;

    @Option(
        name = "subprocess-report-port",
        description = "the port where to connect to send the" + "events."
    )
    private Integer mReportPort = null;

    @Option(
        name = "atest-include-filter",
        description =
                "the include filters to pass to a module. The expected format is"
                        + "\"<module-name>:<include-filter-value>\"",
        importance = Importance.ALWAYS
    )
    private List<String> mIncludeFilters = new ArrayList<>();

    @Option(
        name = "tf-config-path",
        description =
                "Allows to run a specific TF configuration path."
    )
    private List<String> mTfConfigPaths = new ArrayList<>();

    @Option(
        name = "module-config-path",
        description =
                "Allows to run a specific module configuration path."
    )
    private List<File> mModuleConfigPaths = new ArrayList<>();

    @Option(
            name = "incremental-setup",
            description =
                    "Indicates the user specification of whether to enable incremental setup, "
                            + "default to UNSPECIFIED.")
    private AtestUtils.IncrementalSetupEnabled mIncrementalSetupEnabled =
            AtestUtils.IncrementalSetupEnabled.UNSPECIFIED;

    public AtestRunner() {
        setMultiDeviceStrategy(MultiDeviceModuleStrategy.RUN);
    }

    @Override
    public LinkedHashMap<String, IConfiguration> loadingStrategy(Set<IAbi> abis,
        List<File> testsDirs,
        String suitePrefix, String suiteTag) {
        if (mTfConfigPaths.isEmpty() && mModuleConfigPaths.isEmpty()) {
            return super.loadingStrategy(abis, testsDirs, suitePrefix, suiteTag);
        }
        LinkedHashMap<String, IConfiguration> loadedConfigs = new LinkedHashMap<>();
        loadedConfigs.putAll(
                getModuleLoader().loadTfConfigsFromSpecifiedPaths(mTfConfigPaths, abis, suiteTag));
        loadedConfigs.putAll(
                getModuleLoader().loadConfigsFromSpecifiedPaths(
                        mModuleConfigPaths, abis, suiteTag));
        return loadedConfigs;
    }

    @Override
    public LinkedHashMap<String, IConfiguration> loadTests() {
        // atest only needs to run on primary or specified abi.
        if (getRequestedAbi() == null && !mAllAbi) {
            setPrimaryAbiRun(true);
        }
        LinkedHashMap<String, IConfiguration> configMap = super.loadTests();
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
        AtestUtils.applyIncludeFilters(configMap, mIncludeFilters);

        return configMap;
    }

    /** Return a ConfigurationFactory instance. Organized this way for testing purposes. */
    public IConfigurationFactory loadConfigFactory() {
        return ConfigurationFactory.getInstance();
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
