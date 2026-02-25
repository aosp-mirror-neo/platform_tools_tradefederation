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
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.testtype.InstrumentationTest;
import com.android.tradefed.testtype.IsolatedHostTest;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A utility class for Atest runners. */
public class AtestUtils {

    private static final Pattern CONFIG_RE =
            Pattern.compile(".*/(?<config>[^/]+).config", Pattern.CASE_INSENSITIVE);

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

    /** Get a collection of include filters grouped by module name. */
    private static LinkedHashMap<String, HashSet<String>> getIncludeFilters(
            List<String> includeFilters) {
        LinkedHashMap<String, HashSet<String>> parsedFilters =
                new LinkedHashMap<String, HashSet<String>>();
        for (String filter : includeFilters) {
            int moduleSep = filter.indexOf(":");
            if (moduleSep == -1) {
                throw new RuntimeException("Expected delimiter ':' for module or class.");
            }
            String moduleName = canonicalizeConfigName(filter.substring(0, moduleSep));
            String moduleFilter = filter.substring(moduleSep + 1);
            HashSet<String> moduleFilters = parsedFilters.get(moduleName);
            if (moduleFilters == null) {
                moduleFilters = new LinkedHashSet<>();
                parsedFilters.put(moduleName, moduleFilters);
            }
            moduleFilters.add(moduleFilter);
        }
        return parsedFilters;
    }

    /**
     * Non-integrated modules have full file paths as their name, .e.g /foo/bar/name.config, but all
     * we want is the name.
     */
    private static String canonicalizeConfigName(String originalName) {
        Matcher match = CONFIG_RE.matcher(originalName);
        if (match.find()) {
            return match.group("config");
        }
        return originalName;
    }

    /**
     * Add filter to the tests in an IConfiguration.
     *
     * @param testConfig The configuration containing tests to filter.
     * @param filter The filter to add to the tests in the testConfig.
     */
    private static void addFilter(IConfiguration testConfig, String filter) {
        List<IRemoteTest> tests = testConfig.getTests();
        for (IRemoteTest test : tests) {
            if (test instanceof ITestFilterReceiver) {
                CLog.d(
                        "%s:%s - Applying filter (%s)",
                        testConfig.getName(), test.getClass().getSimpleName(), filter);
                ((ITestFilterReceiver) test).addIncludeFilter(filter);
            } else {
                CLog.e(
                        "Test Class (%s) does not support filtering. Cannot apply filter: %s.\n"
                                + "Please update test to use a class that implements "
                                + "ITestFilterReceiver. Running entire test module instead.",
                        test.getClass().getSimpleName(), filter);
            }
        }
    }

    /**
     * Helper to apply include filters to the tests in the config map.
     *
     * @param configMap The map of loaded configurations.
     * @param includeFilters The list of include filters to apply.
     */
    public static void applyIncludeFilters(
            LinkedHashMap<String, IConfiguration> configMap, List<String> includeFilters) {
        if (includeFilters == null || includeFilters.isEmpty()) {
            return;
        }
        LinkedHashMap<String, HashSet<String>> parsedFilters = getIncludeFilters(includeFilters);
        for (IConfiguration testConfig : configMap.values()) {
            HashSet<String> moduleFilters =
                    parsedFilters.get(canonicalizeConfigName(testConfig.getName()));
            if (moduleFilters != null) {
                for (String filter : moduleFilters) {
                    addFilter(testConfig, filter);
                }
            }
        }
    }
}
