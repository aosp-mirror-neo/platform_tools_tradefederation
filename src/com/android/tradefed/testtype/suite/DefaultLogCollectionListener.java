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

import static com.android.tradefed.device.metric.AutoLogCollector.BUGREPORTZ_ON_FAILURE;
import static com.android.tradefed.device.metric.AutoLogCollector.HOSTLOG_ON_FAILURE;
import static com.android.tradefed.device.metric.AutoLogCollector.LOGCAT_ON_FAILURE;
import static com.android.tradefed.device.metric.AutoLogCollector.SCREENSHOT_ON_FAILURE;
import static com.android.tradefed.device.metric.IMetricCollector.MetricCollectionLevel.MODULE;
import static com.android.tradefed.device.metric.IMetricCollector.MetricCollectionLevel.NULL;
import static com.android.tradefed.device.metric.IMetricCollector.MetricCollectionLevel.TEST_CASE;
import static com.android.tradefed.device.metric.IMetricCollector.MetricCollectionLevel.TEST_RUN;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.metric.AutoLogCollector;
import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.device.metric.IMetricCollector.MetricCollectionLevel;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.ResultAndLogForwarder;
import com.android.tradefed.result.TestDescription;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A listener that will collect test logs by default. */
public class DefaultLogCollectionListener extends ResultAndLogForwarder {

    /** Mapping of log collectors to the level at which they will collect during failures. */
    private Map<AutoLogCollector, MetricCollectionLevel> mOnFailureCollectors =
            new LinkedHashMap<>();

    /** Mapping of log collectors to the level at which they will collect regardless of failures. */
    private Map<AutoLogCollector, MetricCollectionLevel> mAlwaysOnCollectors =
            new LinkedHashMap<>();

    /** Mapping of log collectors and their instances. */
    private Map<AutoLogCollector, IMetricCollector> mCollectorInstances = new LinkedHashMap<>();

    private ITestInvocationListener mMainListeners;
    private IInvocationContext mContext;

    /** we will collect at test level if a test failure occurs. */
    private boolean mCollectTestLevel = false;

    /** we will collect at testRun level if any test failure or testRun failure occurs. */
    private boolean mCollectTestRunLevel = false;

    /** we will collect at module level if any test or testRun failure occurs in the module. */
    private boolean mCollectAtModuleLevel = false;

    private String mCurrentTestRunName = null;

    public DefaultLogCollectionListener(
            ITestInvocationListener mainListeners, IInvocationContext context) {
        super(mainListeners);
        mMainListeners = mainListeners;
        mContext = context;
        populateDefaultCollectionLevels();
    }

    private void populateDefaultCollectionLevels() {
        // every default collector should have their always-on and on-failure behavior set.
        mOnFailureCollectors.put(LOGCAT_ON_FAILURE, TEST_CASE);
        mOnFailureCollectors.put(HOSTLOG_ON_FAILURE, TEST_CASE);
        mOnFailureCollectors.put(SCREENSHOT_ON_FAILURE, TEST_CASE);
        mOnFailureCollectors.put(BUGREPORTZ_ON_FAILURE, MODULE);

        mAlwaysOnCollectors.put(LOGCAT_ON_FAILURE, MODULE);
        mAlwaysOnCollectors.put(HOSTLOG_ON_FAILURE, MODULE);
        mAlwaysOnCollectors.put(SCREENSHOT_ON_FAILURE, NULL);
        mAlwaysOnCollectors.put(BUGREPORTZ_ON_FAILURE, NULL);
    }

    /** Initializes the collectors for current test. */
    public void createAndInitializeCollectors() throws DeviceNotAvailableException {
        // TODO: include collectors based on test runner type. for POC, include all
        for (AutoLogCollector logCollectorType : mOnFailureCollectors.keySet()) {
            IMetricCollector collector = logCollectorType.getInstanceForValue();
            collector.enableDefaultMetricCollection();
            mCollectorInstances.put(logCollectorType, collector);
        }

        for (IMetricCollector collector : mCollectorInstances.values()) {
            mMainListeners = collector.init(mContext, mMainListeners);
        }
    }

    public Set<AutoLogCollector> getInitializedCollectors() {
        return mCollectorInstances.keySet();
    }

    /** Change the default always-on collection level for a given collector. */
    public void changeAlwaysOnLevel(AutoLogCollector logCollector, MetricCollectionLevel level) {
        if (mAlwaysOnCollectors.get(logCollector) != null) {
            mAlwaysOnCollectors.put(logCollector, level);
        } else {
            CLog.d(
                    "Ignoring always-on log level change request for %s collector since this"
                            + " collector is not part of default log collection.",
                    logCollector);
        }
    }

    /** Change the default on-failure collection level for a given collector. */
    public void changeOnFailureLevel(AutoLogCollector logCollector, MetricCollectionLevel level) {
        if (mOnFailureCollectors.get(logCollector) != null) {
            mOnFailureCollectors.put(logCollector, level);
        } else {
            CLog.d(
                    "Ignoring on-failure log level change request for %s collector since this"
                            + " collector is not part of default log collection.",
                    logCollector);
        }
    }

    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        mCollectAtModuleLevel = false;
        mMainListeners.testModuleStarted(moduleContext);
    }

    @Override
    public void testRunStarted(String runName, int testCount) {
        mCollectTestRunLevel = false;
        mCurrentTestRunName = runName;
        mMainListeners.testRunStarted(runName, testCount);
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber) {
        mCollectTestRunLevel = false;
        mCurrentTestRunName = runName;
        mMainListeners.testRunStarted(runName, testCount, attemptNumber);
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber, long startTime) {
        mCollectTestRunLevel = false;
        mCurrentTestRunName = runName;
        mMainListeners.testRunStarted(runName, testCount, attemptNumber, startTime);
    }

    @Override
    public void testStarted(TestDescription test) {
        mCollectTestLevel = false;
        mMainListeners.testStarted(test);
    }

    @Override
    public void testStarted(TestDescription test, long startTime) {
        mCollectTestLevel = false;
        mMainListeners.testStarted(test, startTime);
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        mCollectTestLevel = true;
        mCollectTestRunLevel = true;
        mCollectAtModuleLevel = true;
        mMainListeners.testFailed(test, trace);
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        mCollectTestLevel = true;
        mCollectTestRunLevel = true;
        mCollectAtModuleLevel = true;
        mMainListeners.testFailed(test, failure);
    }

    @Override
    public void testAssumptionFailure(TestDescription test, String trace) {
        // do not collect for assumption failures.
        mMainListeners.testAssumptionFailure(test, trace);
    }

    @Override
    public void testAssumptionFailure(TestDescription test, FailureDescription failure) {
        // do not collect for assumption failures.
        mMainListeners.testAssumptionFailure(test, failure);
    }

    @Override
    public void testEnded(TestDescription test, Map<String, String> testMetrics) {
        Set<AutoLogCollector> collectorsUsedInFailure = new LinkedHashSet<>();
        if (mCollectTestLevel) {
            collectorsUsedInFailure = collectForFailures(TEST_CASE, test.getTestName());
        }
        collectForAlwaysOn(TEST_CASE, test.getTestName(), collectorsUsedInFailure);
        mMainListeners.testEnded(test, testMetrics);
    }

    @Override
    public void testEnded(TestDescription test, long endTime, Map<String, String> testMetrics) {
        Set<AutoLogCollector> collectorsUsedInFailure = new LinkedHashSet<>();
        if (mCollectTestLevel) {
            collectorsUsedInFailure = collectForFailures(TEST_CASE, test.getTestName());
        }
        collectForAlwaysOn(TEST_CASE, test.getTestName(), collectorsUsedInFailure);
        mMainListeners.testEnded(test, endTime, testMetrics);
    }

    @Override
    public void testEnded(
            TestDescription test, HashMap<String, MetricMeasurement.Metric> testMetrics) {
        Set<AutoLogCollector> collectorsUsedInFailure = new LinkedHashSet<>();
        if (mCollectTestLevel) {
            collectorsUsedInFailure = collectForFailures(TEST_CASE, test.getTestName());
        }
        collectForAlwaysOn(TEST_CASE, test.getTestName(), collectorsUsedInFailure);
        mMainListeners.testEnded(test, testMetrics);
    }

    @Override
    public void testEnded(
            TestDescription test,
            long endTime,
            HashMap<String, MetricMeasurement.Metric> testMetrics) {
        Set<AutoLogCollector> collectorsUsedInFailure = new LinkedHashSet<>();
        if (mCollectTestLevel) {
            collectorsUsedInFailure = collectForFailures(TEST_CASE, test.getTestName());
        }
        collectForAlwaysOn(TEST_CASE, test.getTestName(), collectorsUsedInFailure);
        mMainListeners.testEnded(test, endTime, testMetrics);
    }

    @Override
    public void testRunFailed(String errorMessage) {
        mCollectTestRunLevel = true;
        mCollectAtModuleLevel = true;
        mMainListeners.testRunFailed(errorMessage);
    }

    @Override
    public void testRunFailed(FailureDescription failure) {
        mCollectTestRunLevel = true;
        mCollectAtModuleLevel = true;
        mMainListeners.testRunFailed(failure);
    }

    @Override
    public void testRunEnded(
            long elapsedTime, HashMap<String, MetricMeasurement.Metric> runMetrics) {
        Set<AutoLogCollector> collectorsUsedInFailure = new LinkedHashSet<>();
        if (mCollectTestRunLevel) {
            collectorsUsedInFailure = collectForFailures(TEST_RUN, mCurrentTestRunName);
        }
        collectForAlwaysOn(TEST_RUN, mCurrentTestRunName, collectorsUsedInFailure);
        mMainListeners.testRunEnded(elapsedTime, runMetrics);
    }

    @Override
    public void testRunEnded(long elapsedTimeMillis, Map<String, String> runMetrics) {
        Set<AutoLogCollector> collectorsUsedInFailure = new LinkedHashSet<>();
        if (mCollectTestRunLevel) {
            collectorsUsedInFailure = collectForFailures(TEST_RUN, mCurrentTestRunName);
        }
        collectForAlwaysOn(TEST_RUN, mCurrentTestRunName, collectorsUsedInFailure);
        mMainListeners.testRunEnded(elapsedTimeMillis, runMetrics);
    }

    @Override
    public void testModuleEnded() {
        Set<AutoLogCollector> collectorsUsedInFailure = new LinkedHashSet<>();
        if (mCollectAtModuleLevel) {
            collectForFailures(MODULE, "module");
        }
        collectForAlwaysOn(MODULE, "module", collectorsUsedInFailure);
        mMainListeners.testModuleEnded();
    }

    private Set<AutoLogCollector> collectForFailures(MetricCollectionLevel level, String name) {
        // TODO: see if we can collect asynchronously
        Set<AutoLogCollector> collectorsUsed = new LinkedHashSet<>();
        // for each collector instance, collect if their collection level at on-failure match
        for (Map.Entry<AutoLogCollector, IMetricCollector> collectorInstanceMap :
                mCollectorInstances.entrySet()) {
            MetricCollectionLevel onFailureLevel =
                    mOnFailureCollectors.get(collectorInstanceMap.getKey());
            if (onFailureLevel.equals(level)) {
                try {
                    collectorsUsed.add(collectorInstanceMap.getKey());
                    collectorInstanceMap.getValue().collectMetrics(level, name);
                } catch (Throwable t) {
                    CLog.e(t);
                }
            }
        }
        return collectorsUsed;
    }

    private void collectForAlwaysOn(
            MetricCollectionLevel level,
            String name,
            Set<AutoLogCollector> collectorsPreviouslyCalled) {
        // TODO: see if we can collect asynchronously
        // for each collector instance, collect if their collection level at always-on match
        // Also avoid collecting if the collector is already called during on failure collection.
        for (Map.Entry<AutoLogCollector, IMetricCollector> collectorInstanceMap :
                mCollectorInstances.entrySet()) {
            MetricCollectionLevel alwaysOnLevel =
                    mAlwaysOnCollectors.get(collectorInstanceMap.getKey());
            if (alwaysOnLevel.equals(level)) {
                if (collectorsPreviouslyCalled.contains(collectorInstanceMap.getKey())) {
                    CLog.d(
                            "Skipping %s during always-on since it was already collected during"
                                    + " failures.");
                    continue;
                }
                try {
                    collectorInstanceMap.getValue().collectMetrics(level, name);
                } catch (Throwable t) {
                    CLog.e(t);
                }
            }
        }
    }
}
