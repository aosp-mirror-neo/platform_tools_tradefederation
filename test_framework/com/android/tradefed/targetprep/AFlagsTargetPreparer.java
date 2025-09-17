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
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.flag.AFlagsFeatureFlag;
import com.android.tradefed.util.flag.DeviceFeatureFlag;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Uses {@code aflags} to override flags prior to running a test. If any flags were changed, the
 * device will be rebooted in order for the new values to take effect.
 *
 * <p>On teardown, any flags will be reverted to their previous state. If the flag was changed from
 * a default value, the override will be cleared (in order for the default value to take hold, and
 * to allow it to be server-overridable again). If the flag was previously overridden to a different
 * value, it will be set back to that value again.
 *
 * <p>If the current flag state matches the target state, the flag will not be overridden. If an
 * override is still desired in this case (e.g. to prevent a default-set flag being
 * server-overridden), the {@code --force-set} option can be used.
 *
 * <p>Example usage: {@code --flag-value namespace/flag_name=true --flag-value
 * other_namespace/another_flag=false}.
 */
public class AFlagsTargetPreparer extends BaseTargetPreparer {
    @Option(name = "flag-value", description = "Additional flag values to apply. Can be repeated.")
    private List<String> mFlagValues = new ArrayList<>();

    @Option(
            name = "force-set",
            description =
                    "Whether to set flags, if the flag is in its default state, and its current"
                            + " value matches the target value.")
    private boolean mForceSet = false;

    private final Map<String, AFlagsFeatureFlag.State> mFlagsToRestore = new HashMap<>();

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInformation.getDevice();
        if (mFlagValues.isEmpty()) {
            CLog.i("No flag-file or flag-value option provided, skipping");
            return;
        }

        Map<String, AFlagsFeatureFlag.State> targetFlags =
                parseDeviceConfigFlags(device, mFlagValues);

        // Get the initial flags, so they can be restored.
        Map<String, AFlagsFeatureFlag> initialFlags = listFlags(device);
        Map<String, AFlagsFeatureFlag.State> flagsToSet = new HashMap<>();

        for (Map.Entry<String, AFlagsFeatureFlag.State> flag : targetFlags.entrySet()) {
            AFlagsFeatureFlag currentValue = initialFlags.get(flag.getKey());
            if (currentValue == null) {
                CLog.w(String.format("Could not find flag %s to set", flag.getKey()));
                continue;
            }

            if (currentValue.getMutability() != AFlagsFeatureFlag.Mutability.READ_WRITE) {
                throw new TargetSetupError(
                        String.format("Flag %s is not read-write, cannot set", flag.getKey()),
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }

            // Does it need to be set?
            if (currentValue.getCurrentState() != flag.getValue()) {
                flagsToSet.put(flag.getKey(), flag.getValue());

                // If the previous value wasn't locally overridden, use "NONE" as the previous value
                // to use the "unset" path (restoring the default/server flag instead).
                AFlagsFeatureFlag.State previousValue =
                        (currentValue.getSetter() == AFlagsFeatureFlag.Setter.LOCAL)
                                ? currentValue.getCurrentState()
                                : AFlagsFeatureFlag.State.NONE;

                mFlagsToRestore.put(flag.getKey(), previousValue);
            } else {
                // If we're forcing the set, and the flag hasn't been locally overridden (e.g. it's
                // default or server), force the value anyway.
                if (mForceSet && currentValue.getSetter() != AFlagsFeatureFlag.Setter.LOCAL) {
                    flagsToSet.put(flag.getKey(), flag.getValue());
                    // The only way we get here is if the current value isn't a local override, so
                    // use "NONE" to clear the value afterwards.
                    mFlagsToRestore.put(flag.getKey(), AFlagsFeatureFlag.State.NONE);
                }
            }
        }

        if (!flagsToSet.isEmpty()) {
            updateFlags(device, flagsToSet);
            device.reboot();
        }
    }

    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        if (e instanceof DeviceNotAvailableException) {
            CLog.i("Device not available - skipping flag cleanup.");
            return;
        }
        if (mFlagsToRestore.isEmpty()) {
            CLog.i("No flags to restore, skipping teardown.");
            return;
        }

        try {
            ITestDevice device = testInformation.getDevice();
            updateFlags(device, mFlagsToRestore);
            device.reboot();
        } catch (TargetSetupError tse) {
            CLog.e("Failed to restore flags: %s", tse);
        }
    }

    private void updateFlags(ITestDevice device, Map<String, AFlagsFeatureFlag.State> flagsToSet)
            throws DeviceNotAvailableException, TargetSetupError {
        for (Map.Entry<String, AFlagsFeatureFlag.State> flag : flagsToSet.entrySet()) {
            switch (flag.getValue()) {
                case DISABLED:
                    runCommand(device, String.format("aflags disable '%s'", flag.getKey()));
                    break;
                case ENABLED:
                    runCommand(device, String.format("aflags enable '%s'", flag.getKey()));
                    break;
                case NONE:
                    runCommand(device, String.format("aflags unset '%s'", flag.getKey()));
                    break;
            }
        }
    }

    private Map<String, AFlagsFeatureFlag> listFlags(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        String values = runCommand(device, "aflags list");
        try (ByteArrayInputStream stream = new ByteArrayInputStream(values.getBytes());
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            return parseFlags(reader.lines());
        } catch (IOException ioe) {
            throw new TargetSetupError(
                    "Failed to parse aflags list",
                    ioe,
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }
    }

    /** Parse a stream of lines from `aflags list`. Unknown inputs are skipped. */
    private Map<String, AFlagsFeatureFlag> parseFlags(Stream<String> lines) {
        return lines.map(
                        line -> {
                            try {
                                return new AFlagsFeatureFlag(line);
                            } catch (IllegalArgumentException ex) {
                                CLog.w("Could not parse flag line %s %s", line, ex.toString());
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(AFlagsFeatureFlag::getFlagName, v -> v));
    }

    /**
     * Parse a list of lines of the form namespace/flag_name=flag_value. The namespace is currently
     * ignored, but the test runner accepts it for forwards compatibility. Unknown inputs will throw
     * an exception.
     */
    private Map<String, AFlagsFeatureFlag.State> parseDeviceConfigFlags(
            ITestDevice device, List<String> values) throws TargetSetupError {
        Map<String, AFlagsFeatureFlag.State> ret = new HashMap<>();
        for (String line : values) {
            try {
                DeviceFeatureFlag flag = new DeviceFeatureFlag(line);
                ret.put(
                        flag.getFlagName(),
                        parseDeviceConfigFlagValue(device, flag.getFlagValue()));
            } catch (IllegalArgumentException ex) {
                throw new TargetSetupError(
                        String.format("Could not parse flag %s", line),
                        ex,
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
        }

        return ret;
    }

    private String runCommand(ITestDevice device, String command)
            throws DeviceNotAvailableException, TargetSetupError {
        CommandResult result = device.executeShellV2Command(command);
        if (result.getStatus() != CommandStatus.SUCCESS) {
            throw new TargetSetupError(
                    String.format(
                            "Command %s failed, stdout = [%s], stderr = [%s]",
                            command, result.getStdout(), result.getStderr()),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }
        return result.getStdout();
    }

    private AFlagsFeatureFlag.State parseDeviceConfigFlagValue(ITestDevice device, String value)
            throws TargetSetupError {
        if (value.equals("true")) {
            return AFlagsFeatureFlag.State.ENABLED;
        } else if (value.equals("false")) {
            return AFlagsFeatureFlag.State.DISABLED;
        } else {
            throw new TargetSetupError(
                    String.format("Could not parse %s as boolean", value),
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
    }
}
