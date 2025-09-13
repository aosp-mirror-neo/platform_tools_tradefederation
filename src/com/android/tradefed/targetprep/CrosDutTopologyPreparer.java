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
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A preparer that runs the dt-converter tool to generate test configuration and test parameters.
 */
@OptionClass(alias = "cros-dut-topology-preparer")
public class CrosDutTopologyPreparer extends BaseTargetPreparer {

    @Option(name = "dt-converter-path", description = "The path to the dt-converter executable.")
    private File mDtConverterPath = new File("/tradefed/cipd_packages/dt-converter");

    @Option(
            name = "inventory-service",
            description = "The address of the ChromeOS inventory service.")
    private String mInventoryService = "localhost:1485";

    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    String getDutName(ITestDevice device) {
        String serial = device.getSerialNumber();
        int colonIndex = serial.indexOf(':');
        if (colonIndex != -1) {
            return serial.substring(0, colonIndex);
        }
        return serial;
    }

    File getLogRoot(TestInformation testInfo, ITestDevice device) throws TargetSetupError {
        try {
            String logDirRoot =
                    testInfo.getContext()
                            .getBuildInfos()
                            .get(0)
                            .getBuildAttributes()
                            .get("LOG_DIR");
            if (logDirRoot == null) {
                logDirRoot = System.getProperty("java.io.tmpdir");
            }
            return new File(logDirRoot);
        } catch (Exception e) {
            CLog.e("Failed to get Tradefed log directory", e);
            throw new TargetSetupError(
                    "Failed to get Tradefed log directory", e, device.getDeviceDescriptor());
        }
    }

    List<String> buildCommand(
            TestInformation testInfo, ITestDevice device, String dutName, File logDir) {
        List<String> command =
                new ArrayList<>(
                        Arrays.asList(
                                mDtConverterPath.getAbsolutePath(),
                                "convert",
                                "-unit",
                                dutName,
                                "-labservice",
                                mInventoryService,
                                "-logdir",
                                logDir.getAbsolutePath(),
                                "-config_name",
                                "config_" + dutName,
                                "-mh",
                                "-tf"));

        String lsnexusAddr = testInfo.properties().get("lsnexus_" + dutName);
        if (lsnexusAddr != null) {
            command.add("-lsnexus");
            command.add(dutName + "=" + lsnexusAddr);
        }
        return command;
    }

    CommandResult runCommand(List<String> command) {
        return getRunUtil().runTimedCmd(60000, command.toArray(new String[0]));
    }

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        String dutName = getDutName(device);
        File logDir = getLogRoot(testInfo, device);

        List<String> command = buildCommand(testInfo, device, dutName, logDir);
        CLog.i("Executing command: %s", String.join(" ", command));
        CommandResult result = runCommand(command);
        CLog.i("Command result: %s", result);

        if (result == null) {
            CLog.e("dt-converter command execution failed to return a result.");
            throw new TargetSetupError(
                    "dt-converter execution failed to return a result",
                    device.getDeviceDescriptor());
        }

        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            CLog.e(
                    "dt-converter failed. Stdout: %s, Stderr: %s",
                    result.getStdout(), result.getStderr());
            throw new TargetSetupError(
                    "dt-converter execution failed", device.getDeviceDescriptor());
        }

        CLog.d("dt-converter stdout: %s", result.getStdout());
        CLog.d("dt-converter stderr: %s", result.getStderr());
        parseOutput(testInfo, result.getStdout());
    }

    void parseOutput(TestInformation testInfo, String output) {
        String[] lines = output.split("\r?\n");
        var context = testInfo.getContext();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) {
                CLog.w("Skipping malformed output line from dt-converter: %s", line);
                continue;
            }
            String key = parts[0].trim();
            String value = parts[1].trim();
            if (key.isEmpty() || value.isEmpty()) {
                CLog.w("Skipping malformed output line from dt-converter: %s", line);
                continue;
            }
            if (key.equals("--invocation-data")) {
                String[] dParts = value.split("=", 2);
                if (dParts.length < 2) {
                    CLog.w("Skipping malformed invocation-data line from dt-converter: %s", line);
                    continue;
                }
                String dKey = dParts[0].trim();
                String dVal = dParts[1].trim();
                context.addInvocationAttribute(dKey, dVal);
                CLog.d("Add invocation attribute: '%s' = '%s'", dKey, dVal);
            } else {
                testInfo.properties().put(key, value);
                CLog.d("Set property: '%s' = '%s'", key, value);
            }
        }
    }
}
