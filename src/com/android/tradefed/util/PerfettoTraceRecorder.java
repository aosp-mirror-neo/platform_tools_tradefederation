/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.tradefed.util;

import com.android.loganalysis.util.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.SearchArtifactUtil;
import com.android.tradefed.util.StreamUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;

/** A utility class for recording perfetto trace on a {@link ITestDevice}. */
public class PerfettoTraceRecorder {

    private static final String TRACE_NAME_FORMAT = "device-trace_%s_";
    private static final String ON_DEVICE_CONFIG_PATH =
            "/data/misc/perfetto-configs/trace_config.textproto";
    private static final String ON_DEVICE_TRACE_DIR = "/data/misc/perfetto-traces/";

    @Option(name = "output-path", description = "Path where the files will be saved on the host.")
    private String outputPath = System.getProperty("java.io.tmpdir");

    // A device-metadata map to store trace related metadata.
    private Map<ITestDevice, DeviceTraceMetadata> deviceMetadataMap = new LinkedHashMap<>();

    // Time in ms to wait for the perfetto process to start
    private static final long START_TIMEOUT = 5 * 1000;
    // Time in ms to wait for the perfetto process to stop and write the file
    private static final long STOP_TIMEOUT = 15 * 1000;

    /**
     * Starts recording perfetto trace on device. Must call {@link
     * PerfettoTraceRecorder#stopTrace(ITestDevice)} afterwards to stop the trace recording.
     *
     * @param device A {@link ITestDevice} where trace will be recorded.
     * @param traceConfigFileName Name of the trace config file.
     * @param extraConfigs A map of extra configs to be added to the trace config file.
     */
    public void startTrace(
            ITestDevice device, String traceConfigFileName, Map<String, String> extraConfigs)
            throws IOException {
        if (deviceMetadataMap.containsKey(device)) {
            CLog.d(
                    "Already recording trace on %s with PID %s.",
                    device.getSerialNumber(), deviceMetadataMap.get(device).getPerfettoPid());
            return;
        }

        DeviceTraceMetadata deviceTraceMetadata = new DeviceTraceMetadata();

        // Prepare the trace config on the host
        File hostTraceConfigFile = prepareTraceConfig(traceConfigFileName, extraConfigs);
        if (hostTraceConfigFile == null) {
            CLog.e("Failed to prepare trace config file.");
            return;
        }
        deviceTraceMetadata.setTraceConfig(hostTraceConfigFile, true);

        try {
            // Push the config file to the device
            if (!device.pushFile(hostTraceConfigFile, ON_DEVICE_CONFIG_PATH)) {
                CLog.e("Failed to push trace config file to device.");
                return;
            }

            // Create a unique on-device trace file path
            String onDeviceTracePath =
                    ON_DEVICE_TRACE_DIR + "trace_" + System.currentTimeMillis() + ".pftrace";
            deviceTraceMetadata.setOnDeviceTracePath(onDeviceTracePath);

            // Prepare the host output file
            File hostTraceOutput =
                    FileUtil.createTempFile(
                            String.format(TRACE_NAME_FORMAT, device.getSerialNumber()),
                            ".pftrace",
                            new File(outputPath));
            deviceTraceMetadata.setHostTraceOutput(hostTraceOutput, false);

            CLog.d("Starting Perfetto trace on device %s", device.getSerialNumber());
            CLog.d("On-device config: %s", ON_DEVICE_CONFIG_PATH);
            CLog.d("On-device output: %s", onDeviceTracePath);

            // Command to start perfetto in the background
            String startCmd =
                    String.format(
                            "perfetto -c %s --txt -o %s --background",
                            ON_DEVICE_CONFIG_PATH, onDeviceTracePath);

            // Run the command and get the PID
            CommandResult result =
                    device.executeShellV2Command(
                            "nohup " + startCmd + " > /dev/null 2>&1 & echo $!");
            if (result.getStatus() != CommandStatus.SUCCESS) {
                CLog.e("Failed to start perfetto: %s", result.getStderr());
                return;
            }

            String pidStr = result.getStdout().trim();
            try {
                long pid = Long.parseLong(pidStr);
                deviceTraceMetadata.setPerfettoPid(pid);
                CLog.i(
                        "Perfetto started in background with PID: %s on device %s",
                        pid, device.getSerialNumber());
            } catch (NumberFormatException e) {
                CLog.e("Failed to parse PID from output: %s", pidStr);
                return;
            }

            deviceMetadataMap.put(device, deviceTraceMetadata);

        } catch (DeviceNotAvailableException e) {
            CLog.e("Device not available: %s", e.getMessage());
            deviceTraceMetadata.cleanUp();
        }
    }

    /**
     * Stops recording perfetto trace on the device and pulls the file.
     *
     * @param device device for which to stop the recording.
     * @return Returns the host-side perfetto trace file, or null if failed.
     */
    public File stopTrace(ITestDevice device) {
        DeviceTraceMetadata metadata = deviceMetadataMap.remove(device);
        if (metadata == null) {
            CLog.d("No active trace session found for device %s", device.getSerialNumber());
            return null;
        }

        File hostTraceFile = metadata.getHostTraceOutput();
        String onDeviceTracePath = metadata.getOnDeviceTracePath();
        Long pid = metadata.getPerfettoPid();

        try {
            if (pid != null) {
                CLog.d(
                        "Stopping perfetto process PID %d on device %s",
                        pid, device.getSerialNumber());
                // Send SIGINT to the perfetto process to stop it gracefully.
                device.executeShellCommand("kill -INT " + pid);

                // Wait for the process to exit and write the file.
                // Poll for the process to disappear.
                boolean stopped = false;
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < STOP_TIMEOUT) {
                    // Inside the while loop in stopTrace
                    if (!isProcessRunning(device, pid)) {
                        CLog.d("Perfetto process %d terminated.", pid);
                        stopped = true;
                        break;
                    }

                    RunUtil.getDefault().sleep(500);
                }
                if (!stopped) {
                    CLog.w(
                            "Perfetto process %d did not terminate within timeout. Forcing kill.",
                            pid);
                    device.executeShellCommand("kill -KILL " + pid);
                }
            } else {
                CLog.w("Perfetto PID not found for device %s", device.getSerialNumber());
            }

            // Regardless of how it stopped, attempt to pull the file.
            CLog.i(
                    "Pulling trace file from %s to %s",
                    onDeviceTracePath, hostTraceFile.getAbsolutePath());
            if (device.pullFile(onDeviceTracePath, hostTraceFile)) {
                CLog.i("Successfully pulled trace file.");
                // Optionally check file size, e.g., > 0 bytes
                if (hostTraceFile.length() == 0) {
                    CLog.w("Pulled trace file is empty.");
                }
            } else {
                CLog.e("Failed to pull trace file from %s", onDeviceTracePath);
                // The hostTraceFile might be empty or non-existent.
                return null;
            }

        } catch (DeviceNotAvailableException e) {
            CLog.e(e);
            return null; // Failed to stop or pull
        } finally {
            // Cleanup on-device files
            try {
                if (onDeviceTracePath != null) {
                    device.executeShellCommand("rm -f " + onDeviceTracePath);
                }
                device.executeShellCommand("rm -f " + ON_DEVICE_CONFIG_PATH);
            } catch (DeviceNotAvailableException e) {
                CLog.w("Failed to cleanup on-device files: %s", e.getMessage());
            }
            // Cleanup host-side temp config file
            metadata.cleanUp();
        }

        return hostTraceFile;
    }

    @VisibleForTesting
    Long getPerfettoPid(ITestDevice device) {
        DeviceTraceMetadata metadata = deviceMetadataMap.get(device);
        if (metadata == null) {
            return null;
        }
        // The pid is set in startTrace and cleared in stopTrace.
        // This will be null if trace is not running.
        return metadata.getPerfettoPid();
    }

    private File prepareTraceConfig(String traceConfigFileName, Map<String, String> extraConfigs)
            throws IOException {
        String configStr;
        String resolvedTraceConfigPath =
                (traceConfigFileName == null)
                        ? "/perfetto/trace_config.textproto"
                        : String.format("/perfetto/%s", traceConfigFileName);

        InputStream configStream =
                PerfettoTraceRecorder.class.getResourceAsStream(resolvedTraceConfigPath);
        if (configStream != null) {
            configStr = StreamUtil.getStringFromStream(configStream);
        } else {
            File file = SearchArtifactUtil.searchFile(traceConfigFileName, true);
            if (file == null) {
                CLog.e("Cannot find config file: %s", traceConfigFileName);
                return null;
            }
            configStr = FileUtil.readStringFromFile(file);
        }

        if (extraConfigs != null) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> configKeyValue : extraConfigs.entrySet()) {
                sb.append(
                        String.format(
                                "%s: %s\n", configKeyValue.getKey(), configKeyValue.getValue()));
            }
            configStr = configStr.replace("# {injected_config}", sb.toString());
        }

        File traceConfigFile = FileUtil.createTempFile("trace_config", ".textproto");
        FileUtil.writeToFile(configStr, traceConfigFile);
        return traceConfigFile;
    }

    // In PerfettoTraceRecorder.java
    @VisibleForTesting
    boolean isProcessRunning(ITestDevice device, long pid)
            throws DeviceNotAvailableException {
        // Check if the process ID exists in the /proc directory
        CommandResult result = device.executeShellV2Command(String.format("test -d /proc/%d", pid));
        // Command succeeds (exit code 0) if the directory exists
        return result.getStatus() == CommandStatus.SUCCESS && result.getExitCode() == 0;
    }

    /** Stores metadata related to a trace running on an {@link ITestDevice}. */
    private static class DeviceTraceMetadata {
        private Long mPerfettoPid;
        private File mTraceConfig; // Host side temp config
        private String mOnDeviceTracePath;
        private File mHostTraceOutput; // Host side final output

        private List<File> tempFiles = new ArrayList<>();

        public Long getPerfettoPid() {
            return mPerfettoPid;
        }

        public void setPerfettoPid(Long pid) {
            mPerfettoPid = pid;
        }

        public File getTraceConfig() {
            return mTraceConfig;
        }

        public void setTraceConfig(File traceConfig, boolean needToDelete) {
            mTraceConfig = traceConfig;
            if (needToDelete) {
                tempFiles.add(traceConfig);
            }
        }

        public String getOnDeviceTracePath() {
            return mOnDeviceTracePath;
        }

        public void setOnDeviceTracePath(String onDeviceTracePath) {
            mOnDeviceTracePath = onDeviceTracePath;
        }

        public File getHostTraceOutput() {
            return mHostTraceOutput;
        }

        public void setHostTraceOutput(File hostTraceOutput, boolean needToDelete) {
            mHostTraceOutput = hostTraceOutput;
            if (needToDelete) {
                tempFiles.add(hostTraceOutput);
            }
        }

        public void cleanUp() {
            for (File file : tempFiles) {
                FileUtil.deleteFile(file);
            }
        }
    }
}
