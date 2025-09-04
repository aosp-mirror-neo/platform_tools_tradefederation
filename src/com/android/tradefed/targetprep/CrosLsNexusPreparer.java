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
package com.android.tradefed.targetprep;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestLoggerReceiver;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A preparer that starts and stops the LSNexus service for a ChromeOS device.
 *
 * <p>The service is started before test execution and stopped after completion.
 */
@OptionClass(alias = "cros-lsnexus-preparer")
public class CrosLsNexusPreparer extends BaseTargetPreparer
        implements ITargetCleaner, ITestLoggerReceiver {

    @Option(name = "lsnexus-path", description = "The path to the lsnexus executable.")
    private File mLsNexusPath = new File("/tradefed/cipd_packages/lsnexus");

    @Option(
            name = "inventory-service",
            description = "The address of the ChromeOS inventory service.")
    private String mInventoryService = "localhost:1485";

    static final long TIMEOUT_TO_READ_USED_PORT = 60000;
    static final long INTERVAL_TO_READ_USED_PORT = 1000;

    Map<String, Process> mDeviceProcesses = new HashMap<>();
    Map<String, Integer> mDevicePorts = new HashMap<>();
    Map<String, File> mDeviceLogDirs = new HashMap<>();

    private ITestLogger mLogger;

    @Override
    public void setTestLogger(ITestLogger testLogger) {
        mLogger = testLogger;
    }

    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    int findUsedPort(ITestDevice device, String dutName, File logDir, long timeout)
            throws TargetSetupError {
        // Wait for the lsnexus_port file to be created
        File portFile = new File(logDir, "lsnexus_port");
        final long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            CLog.d("Waiting for creating of the file:%s", portFile.getAbsoluteFile());
            if (portFile.exists()) {
                // Read the port from the file
                try {
                    String portString = FileUtil.readStringFromFile(portFile).trim();
                    int port = Integer.parseInt(portString);
                    mDevicePorts.put(dutName, port);
                    CLog.d("LSNexus started on port:%d for %s", port, dutName);
                    return port;
                } catch (IOException | NumberFormatException e) {
                    throw new TargetSetupError(
                            "Failed to read port from lsnexus_port file",
                            e,
                            device.getDeviceDescriptor());
                }
            }
            try {
                Thread.sleep(INTERVAL_TO_READ_USED_PORT);
            } catch (Exception e) {
                throw new TargetSetupError(
                        "Failed to read port from lsnexus_port file",
                        e,
                        device.getDeviceDescriptor());
            }
        }
        CLog.d("Failed to find file:%s", portFile.getAbsoluteFile());
        throw new TargetSetupError(
                "LSNexus service failed to create port file within timeout",
                device.getDeviceDescriptor());
    }

    String getDutName(ITestDevice device) {
        String serial = device.getSerialNumber();
        // Extract DUT name from serial (e.g., "host1" from "host1:5555")
        int colonIndex = serial.indexOf(':');
        if (colonIndex != -1) {
            return serial.substring(0, colonIndex);
        }
        return serial;
    }

    File getServiceLogDir(TestInformation testInfo, String dutName, ITestDevice device)
            throws TargetSetupError {
        File logDir = null;
        try {
            // Create a unique log directory for this DUT's LSNexus instance
            String logDirRoot =
                    testInfo.getContext()
                            .getBuildInfos()
                            .get(0)
                            .getBuildAttributes()
                            .get("LOG_DIR");
            if (logDirRoot == null) {
                logDirRoot = System.getProperty("java.io.tmpdir");
            }
            logDir = FileUtil.createTempDir("lsnexus_" + dutName, new File(logDirRoot));
        } catch (IOException e) {
            throw new TargetSetupError(
                    "Failed to create log directory for LSNexus", e, device.getDeviceDescriptor());
        }
        return logDir;
    }

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        final String dutName = getDutName(device);
        CLog.d("Prepare to start LSNexus for %s", dutName);
        final var logDir = getServiceLogDir(testInfo, dutName, device);
        mDeviceLogDirs.put(dutName, logDir);
        List<String> command =
                Arrays.asList(
                        mLsNexusPath.getAbsolutePath(),
                        "server",
                        "-port",
                        "0",
                        "-logs",
                        logDir.getAbsolutePath(),
                        "-dut",
                        dutName,
                        "-labservice",
                        mInventoryService);

        CLog.d("Starting LSNexus for %s: %s", dutName, String.join(" ", command));
        Process process = null;
        try {
            process = getRunUtil().runCmdInBackground(command);
            mDeviceProcesses.put(dutName, process);
            final int port = findUsedPort(device, dutName, logDir, TIMEOUT_TO_READ_USED_PORT);

            // Make the LSNexus address available to the test
            testInfo.properties().put("lsnexus_" + dutName, "localhost:" + port);
        } catch (IOException e) {
            FileUtil.recursiveDelete(mDeviceLogDirs.get(dutName));
            throw new TargetSetupError(
                    "Failed to start LSNexus service", e, device.getDeviceDescriptor());
        } catch (TargetSetupError e) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw e;
        }
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        String dutName = getDutName(testInfo.getDevice());
        Process process = mDeviceProcesses.remove(dutName);
        Integer port = mDevicePorts.remove(dutName);
        File logDir = mDeviceLogDirs.remove(dutName);

        stopProcess(process, dutName, port);
        uploadLogs(logDir, true, "");
    }

    void stopProcess(Process process, String dutName, Integer port) {
        if (process == null) {
            return;
        }
        CLog.d("Stopping LSNexus for %s (port %d)", dutName, port);
        process.destroy();
        try {
            // Wait for a short period to allow the process to terminate
            boolean exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                CLog.w("LSNexus process for %s did not terminate gracefully, forcing.", dutName);
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            CLog.w("Interrupted while waiting for LSNexus to stop for %s", dutName);
            process.destroyForcibly();
        }
    }

    void uploadLogs(File logDir, boolean allowRecursiveUpload, String recursiveDirName) {
        if (logDir == null || !logDir.exists()) {
            CLog.w("Log directory is null or does not exist: %s", logDir);
            return;
        }
        if (!logDir.isDirectory()) {
            CLog.w("Provided path is not a directory: %s", logDir.getAbsolutePath());
            return;
        }
        CLog.d("Prepare upload logs from: %s", logDir.getAbsolutePath());
        File[] logFiles = logDir.listFiles();
        if (logFiles != null) {
            for (File logFile : logFiles) {
                if (logFile.isDirectory()) {
                    if (allowRecursiveUpload) {
                        uploadLogs(logFile, false, logFile.getName());
                    } else {
                        CLog.d("Skipping logs from dir:%s", logFile.getAbsolutePath());
                    }
                } else {
                    try (InputStreamSource streamSource =
                            new FileInputStreamSource(logFile, true)) {
                        String logName = logFile.getName();
                        if (!recursiveDirName.isEmpty()) {
                            logName = recursiveDirName + "_" + logName;
                        }
                        mLogger.testLog(logName, LogDataType.TEXT, streamSource);
                    }
                }
            }
        }
        FileUtil.recursiveDelete(logDir);
    }
}
