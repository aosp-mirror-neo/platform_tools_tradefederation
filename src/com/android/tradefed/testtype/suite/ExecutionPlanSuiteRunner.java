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
package com.android.tradefed.testtype.suite;

import com.android.soong.testconfigs.proto.KeyValue;
import com.android.soong.testconfigs.proto.ModulePlan;
import com.android.soong.testconfigs.proto.TestConfigs;
import com.android.soong.testconfigs.proto.TestExecutionPlan;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.Pair;
import com.android.tradefed.util.ZipUtil2;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@link BaseTestSuite} to run tests based on specified execution plans in the
 * build.
 */
public class ExecutionPlanSuiteRunner extends BaseTestSuite {

    private static final String TEST_CONFIGS_PROTO_PATH = "test_configs.pb";

    @Option(name = "execution-plans", description = "One or more execution plan names.")
    private List<String> mExecutionPlans = new ArrayList<>();

    @Option(
            name = "config-zip-paths",
            description = "One or more paths to zip files containing test configurations.")
    private List<String> mConfigZipPaths = new ArrayList<>();

    public ExecutionPlanSuiteRunner() {}

    @Override
    public LinkedHashMap<String, IConfiguration> loadTests() {
        if (!getIncludeFilter().isEmpty()) {
            throw new HarnessRuntimeException(
                    "The option --include-filter is forbidden in ExecutionPlanSuiteRunner. "
                            + "Please specify --execution-plans instead.",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }

        // Step 1: load and match execution plan protos from zip files
        List<TestExecutionPlan> matchedExecutionPlanProtos = loadAndMatchExecutionPlans();

        // Step 2: create a map of module names to their execution plan and module plan
        LinkedHashMap<String, List<Pair<TestExecutionPlan, ModulePlan>>> moduleNameToPlansMap =
                new LinkedHashMap<>();
        for (TestExecutionPlan executionPlan : matchedExecutionPlanProtos) {
            for (ModulePlan modulePlan : executionPlan.getTestsList()) {
                moduleNameToPlansMap
                        .computeIfAbsent(modulePlan.getModule(), k -> new ArrayList<>())
                        .add(new Pair<>(executionPlan, modulePlan));
            }
        }

        // Get all individual module names from the matched execution plans
        Set<String> modulesToLoad = moduleNameToPlansMap.keySet();

        // Step 3: pre-load all IConfiguration for per matched module.
        // Note that each IConfiguration needs to reload again per ModulePlan in
        // the next step, with filters and module level args reset before loading,
        // so that parameters in IRemoteTest could be assigned correctly
        // during loadTest calls.
        setIncludeFilter(modulesToLoad);
        LinkedHashMap<String, IConfiguration> discoveredConfigs = super.loadTests();

        // Step 4: reload discovered configurations with args set per ModulePlan
        LinkedHashMap<String, IConfiguration> finalConfigs = new LinkedHashMap<>();
        for (Map.Entry<String, IConfiguration> entry : discoveredConfigs.entrySet()) {
            IConfiguration moduleConfig = entry.getValue();
            String moduleName = entry.getKey();

            // Normalize module name with abi.
            if (moduleConfig.getConfigurationDescription().getAbi() != null) {
                moduleName =
                        moduleName.replace(
                                String.format(
                                        "%s ",
                                        moduleConfig
                                                .getConfigurationDescription()
                                                .getAbi()
                                                .getName()),
                                "");
            }

            // Get executionPlan and modulePlan of current module.
            List<Pair<TestExecutionPlan, ModulePlan>> plans = moduleNameToPlansMap.get(moduleName);
            if (plans == null) {
                CLog.w("Discovered module %s was not requested in any execution plan.", moduleName);
                continue;
            }

            IConfiguration clonedConfig = moduleConfig.clone();

            List<IRemoteTest> allTestsForModule = new ArrayList<>();
            // Reload IRemoteTests per modulePlan.
            for (Pair<TestExecutionPlan, ModulePlan> planPair : plans) {
                allTestsForModule.addAll(
                        createIndividualTests(moduleConfig, planPair.second, planPair.first));
            }
            // Set final set of IRemoteTest of IConfiguration of current module.
            clonedConfig.setTests(allTestsForModule);

            if (plans.size() > 1) {
                clonedConfig.getConfigurationDescription().setNotIRemoteTestShardable(true);
            }

            finalConfigs.put(entry.getKey(), clonedConfig);
        }

        return finalConfigs;
    }

    /**
     * Create individual tests by reloading configuration per {@link ModulePlan}.
     *
     * @param moduleConfiguration The {@link IConfiguration} of the module config.
     * @param modulePlan The {@link ModulePlan} that the tests are created against.
     * @param executionPlan The {@link TestExecutionPlan} that the tests are created against.
     * @return a {@code List<IRemoteTest>} of the tests reloaded from configuration.
     */
    private List<IRemoteTest> createIndividualTests(
            IConfiguration moduleConfiguration,
            ModulePlan modulePlan,
            TestExecutionPlan executionPlan) {
        List<IRemoteTest> tests = new ArrayList<>();
        String configPath = moduleConfiguration.getName();
        IAbi abi = moduleConfiguration.getConfigurationDescription().getAbi();

        super.cleanUpSuiteSetup();
        super.clearModuleArgs();

        clearConfigPaths();
        if (configPath != null) {
            File configFile = new File(configPath);
            if (configFile != null) {
                addConfigPaths(configFile);
            }
        }

        parseOptions(modulePlan, executionPlan);

        LinkedHashMap<String, IConfiguration> loadedConfigs = super.loadTests();

        for (Map.Entry<String, IConfiguration> entry : loadedConfigs.entrySet()) {
            if (entry.getValue().getConfigurationDescription().getAbi() != null
                    && !entry.getValue().getConfigurationDescription().getAbi().equals(abi)) {
                continue;
            }
            tests.addAll(entry.getValue().getTests());
        }
        return tests;
    }

    /**
     * Parse the options from {@link ModulePlan} and {@link TestExecutionPlan} and apply them to the
     * current test suite runner for individual test loading.
     *
     * @param modulePlan The {@link ModulePlan} containing module-specific options.
     * @param executionPlan The {@link TestExecutionPlan} containing overall execution options.
     */
    private void parseOptions(ModulePlan modulePlan, TestExecutionPlan executionPlan) {
        // Process filters
        Set<String> moduleIncludeFilters = new HashSet<>();
        for (String includeFilter : modulePlan.getIncludeList()) {
            moduleIncludeFilters.add(String.format("%s %s", modulePlan.getModule(), includeFilter));
        }
        if (!moduleIncludeFilters.isEmpty()) {
            setIncludeFilter(moduleIncludeFilters);
        } else {
            setIncludeFilter(Set.of(modulePlan.getModule()));
        }

        Set<String> moduleExcludeFilters = new HashSet<>();
        for (String excludeFilter : modulePlan.getExcludeList()) {
            moduleExcludeFilters.add(String.format("%s %s", modulePlan.getModule(), excludeFilter));
        }
        if (!moduleExcludeFilters.isEmpty()) {
            setExcludeFilter(moduleExcludeFilters);
        }

        // Process module arguments
        Set<String> moduleArgs = new HashSet<>();
        for (KeyValue kv : modulePlan.getModuleArgsList()) {
            String moduleArg = String.format("%s:%s", modulePlan.getModule(), kv.getKey());
            if (!kv.getValue().isEmpty()) {
                moduleArg = String.format("%s:%s", moduleArg, kv.getValue());
            }
            moduleArgs.add(moduleArg);
        }
        addModuleArgs(moduleArgs);
    }

    /**
     * Load and match {@link TestExecutionPlan} from zip files.
     *
     * @return a {@code List<TestExecutionPlan>} matched with specified names.
     */
    private List<TestExecutionPlan> loadAndMatchExecutionPlans() {
        List<TestExecutionPlan> allPlans = new ArrayList<>();
        for (String zipPath : mConfigZipPaths) {
            File zipFile = getBuildInfo().getFile(zipPath);
            if (zipFile == null || !zipFile.exists()) {
                CLog.w("Config zip path %s does not exist.", zipPath);
                continue;
            }
            File protoFile = null;
            try (ZipFile zf = new ZipFile(zipFile)) {
                protoFile = ZipUtil2.extractFileFromZip(zf, TEST_CONFIGS_PROTO_PATH);
                if (protoFile != null) {
                    try (FileInputStream fis = new FileInputStream(protoFile)) {
                        TestConfigs testConfigs = TestConfigs.parseFrom(fis);
                        allPlans.addAll(testConfigs.getExecutionPlansList());
                    }
                } else {
                    CLog.w("Did not find %s in %s", TEST_CONFIGS_PROTO_PATH, zipPath);
                }
            } catch (IOException e) {
                throw new HarnessRuntimeException(
                        "Failed to process test-configs zip: " + zipPath,
                        e,
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            } finally {
                FileUtil.deleteFile(protoFile);
            }
        }
        CLog.i("Loaded %d execution plan protos from zips: %s", allPlans.size(), mConfigZipPaths);

        List<TestExecutionPlan> matchedExecutionPlanProtos = new ArrayList<>();
        CLog.i("Attempting to match execution plans: %s", mExecutionPlans);
        for (String planName : mExecutionPlans) {
            boolean planNameMatched = false;
            for (TestExecutionPlan executionPlanProto : allPlans) {
                if (planName.equals(executionPlanProto.getName())) {
                    CLog.i("Found execution plan: %s", planName);
                    matchedExecutionPlanProtos.add(executionPlanProto);
                    planNameMatched = true;
                    break;
                }
            }
            if (!planNameMatched) {
                throw new HarnessRuntimeException(
                        String.format("Could not find execution plan: %s", planName),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
        }
        return matchedExecutionPlanProtos;
    }
}
