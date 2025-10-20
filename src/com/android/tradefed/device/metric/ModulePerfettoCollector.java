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

package com.android.tradefed.device.metric;

import com.google.common.annotations.VisibleForTesting;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.util.PerfettoTraceRecorder;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Collector that will start perfetto trace when a test module start and end and device reboots. */
public class ModulePerfettoCollector extends BaseDeviceMetricCollector {
    @Option(
            name = "trace-config-file",
            description =
                    "Name of the trace config file in the test artifacts. "
                            + "Can be used to optionally override the default trace config.")
    private String mTraceConfigFile = "trace_config_oom.textproto";

    // Format of the trace name will be:
    // perfetto-debug-trace_<run-name>_<module-name>_<event-name>_<trace-count>.
    @Option(name = "trace-name-prefix", description = "Prefix to be added to the trace name.")
    private String mTraceFileName = "perfetto-debug-trace";

    // Minimum size of the trace file to be logged.
    private static final int MIN_LOG_SIZE = 10;
    @VisibleForTesting PerfettoTraceRecorder mPerfettoTraceRecorder = new PerfettoTraceRecorder();
    private Map<ITestDevice, Integer> mTraceCountMap = new LinkedHashMap<>();
    // Map of trace files and the proper name it should be logged with
    private Map<File, String> mTraceFilesMap = new LinkedHashMap();
    private boolean mModuleLevelFlag = false;

    @Override
    public boolean captureModuleLevel() {
        return true;
    }

    // Enable IDeviceActionReceiver by default for reboot events.
    public ModulePerfettoCollector() {
        setDisableReceiver(false);
    }

    public boolean isOnModuleLevel() {
        return getModuleName() != null;
    }

    @Override
    public void onTestModuleStarted() throws DeviceNotAvailableException {
        if (isOnModuleLevel()) {
            CLog.d("ModulePerfettoCollector: onTestModuleStarted");
            startCollecting("onTestModuleStarted");
        }
    }

    @Override
    public void onTestRunStart(DeviceMetricData runData) {
        if (isOnModuleLevel()) {
            return;
        }
        CLog.d("ModulePerfettoCollector: onTestRunStart");
        startCollecting("onTestRunStart");
    }

    @Override
    public void onTestRunEnd(
            DeviceMetricData runData, final Map<String, Metric> currentRunMetrics) {
        if (isOnModuleLevel()) {
            return;
        }
        CLog.d("ModulePerfettoCollector: onTestRunEnd");
        stopCollecting("onTestRunEnd");
    }

    @Override
    public void onTestRunFailed(DeviceMetricData runData, FailureDescription failure)
            throws DeviceNotAvailableException {
        if (!isOnModuleLevel()) {
            stopCollecting("onTestRunFailed");
        }
    }

    @Override
    public void onTestModuleEnded() throws DeviceNotAvailableException {
        if (isOnModuleLevel()) {
            CLog.d("ModulePerfettoCollector: onTestModuleEnded");
            stopCollecting("onTestModuleEnded");
        }
    }

    @Override
    public void rebootStarted(ITestDevice device) throws DeviceNotAvailableException {
        CLog.d("ModulePerfettoCollector: rebootStarted");
        super.rebootStarted(device);
        // save previous trace running on this device.
        collectTraceFileFromDevice(device, "rebootStarted");
        logTraceFiles();
    }

    @Override
    public void rebootEnded(ITestDevice device) throws DeviceNotAvailableException {
        CLog.d("ModulePerfettoCollector: rebootEnded");
        super.rebootEnded(device);
        // start new trace running on this device
        startTraceOnDevice(device);
    }

    private void startCollecting(String eventName) {
        CLog.d("ModulePerfettoCollector: startCollecting on event: %s", eventName);
        for (ITestDevice device : getRealDevices()) {
            startTraceOnDevice(device);
        }
    }

    private void stopCollecting(String eventName) {
        CLog.d("ModulePerfettoCollector: stopCollecting on event: %s", eventName);
        for (ITestDevice device : getRealDevices()) {
            collectTraceFileFromDevice(device, eventName);
        }
        logTraceFiles();
    }

    private void startTraceOnDevice(ITestDevice device) {
        CLog.d("ModulePerfettoCollector: startTraceOnDevice");
        // count should be increased even if no trace file collected to make missing traces visible.
        mTraceCountMap.put(device, mTraceCountMap.getOrDefault(device, 0) + 1);
        try {
            CLog.d(
                    "Waiting for device %s to be idle before starting perfetto trace.",
                    device.getSerialNumber());
            // Wait for the device shell to be available, which indicates a basic level of idleness/responsiveness.
            // A timeout of 3 minutes (180 seconds) is used.
            device.waitForDeviceShell(180 * 1000L);
            CLog.d(
                    "Device %s is idle, proceeding to start perfetto trace.",
                    device.getSerialNumber());

            mPerfettoTraceRecorder.startTrace(device, mTraceConfigFile, null);
        } catch (IOException e) {
            CLog.d("Failed to start perfetto trace with error: %s", e.getMessage());
        }
    }

    private void collectTraceFileFromDevice(ITestDevice device, String eventName) {
        CLog.d("ModulePerfettoCollector: collectTraceFileFromDevice");
        File traceFile = mPerfettoTraceRecorder.stopTrace(device);
        if (traceFile == null) {
            CLog.d("Failed to collect device trace on event: %s.", eventName);
            return;
        }
        CLog.d("Collected device trace on event: %s.", eventName);

        String traceName =
                String.join(
                        "_",
                        mTraceFileName,
                        getModuleName(),
                        eventName,
                        String.valueOf(mTraceCountMap.get(device)));
        mTraceFilesMap.put(traceFile, traceName);
    }

    private void logTraceFiles() {
        CLog.d("ModulePerfettoCollector: logTraceFiles");
        for (Map.Entry<File, String> entry : mTraceFilesMap.entrySet()) {
            File traceFile = entry.getKey();
            if (traceFile.length() < MIN_LOG_SIZE) {
                CLog.d(
                        "Skipping logging for trace file %s with size %d bytes.",
                        traceFile.getName(), traceFile.length());
                traceFile.delete();
                continue;
            }
            try (FileInputStreamSource source = new FileInputStreamSource(traceFile, true)) {
                super.testLog(entry.getValue(), LogDataType.PERFETTO, source);
            }
        }
        mTraceFilesMap.clear();
    }
}
