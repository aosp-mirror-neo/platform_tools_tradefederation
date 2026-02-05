/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tradefed.targetprep;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceRuntimeException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Starts ehide, which hides the ethernet interface from the Android network stack. Skip cuttlefish
 * for now since the ethernet interface is hidden by default.
 *
 * <p>Requires adb root.
 */
public class EhideTargetPreparer extends BaseTargetPreparer {

    private static final Duration POLLING_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POLLING_INTERVAL = Duration.ofSeconds(1);
    private static final Duration WAIT_FOR_DEVICE_ONLINE_TIMEOUT = Duration.ofSeconds(15);
    // Ehide-related commands should not take too much time.
    private static final Duration CMD_MAX_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SLEEP_DURATION_FOR_EHIDE_INIT = Duration.ofSeconds(10);

    private static final String EHIDE_STATUS_CMD = "ehide-cli";
    private static final String EHIDE_STATUS_PREFIX = "ehide state: ";
    private static final String EHIDE_STATUS_STARTED = "running - managing";
    private static final String EHIDE_STATUS_STOPPED = "stopped";

    // Set to true in the cuttlefish case.
    private boolean mSkipEhide = false;

    private static boolean isCuttlefish(ITestDevice device) throws DeviceNotAvailableException {
        String productName = device.getProperty("ro.product.name");
        return productName != null && productName.startsWith("cf_");
    }

    private CommandResult executeShellCommand(ITestDevice device, String command)
            throws DeviceNotAvailableException {
        return device.executeShellV2Command(
                command,
                /* stdinSource= */ null,
                /* pipeToOutput= */ null,
                /* maxTimeoutForCommand= */ CMD_MAX_TIMEOUT.toSeconds(),
                TimeUnit.SECONDS,
                /* retryAttempts= */ 0);
    }

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInformation.getDevice();
        if (isCuttlefish(device)) {
            CLog.i("Skipping ehide for cuttlefish");
            mSkipEhide = true;
            return;
        }
        try {
            enableEhide(device);
        } catch (Exception e) {
            throw new TargetSetupError(e.getMessage(), e, device.getDeviceDescriptor());
        }
    }

    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        ITestDevice device = testInformation.getDevice();
        if (mSkipEhide) {
            return;
        }

        try {
            disableEhide(device);
        } catch (Exception ex) {
            CLog.e("Failed to stop ehide", ex);
        }
    }

    /**
     * Starts ehide and waits until its state begins with "running - managing".
     *
     * <p>When using ADB-over-network and ehide has just started, there is a brief period of time
     * during which ehide is moving the Ethernet interface into the ehide network namespacae. If we
     * try to check the state during this time, ehide-cli still report that ehide is running, while
     * the actual connection is not yet through ehide.
     *
     * <p>To mitigate this issue, sleep for 10 seconds. In the ADB-over-network case, this is enough
     * time for ehide to initialize. In the ADB-over-USB case it will harmlessly increase the test
     * duration.
     */
    private void enableEhide(ITestDevice device) throws Exception {
        // Successfully changing the ehide state may cause ADB to disconnect,
        // causing the command to not return with a SUCCESS. Handle this case
        // by ignoring the command status.
        executeShellCommand(device, "start ehide");
        Thread.sleep(SLEEP_DURATION_FOR_EHIDE_INIT.toMillis());
        pollForEhideState(device, EHIDE_STATUS_STARTED);
    }

    private void disableEhide(ITestDevice device) throws Exception {
        // Intentionally ignore the command status.
        executeShellCommand(device, "stop ehide");
        pollForEhideState(device, EHIDE_STATUS_STOPPED);
    }

    /** Wait for the device to be available, then poll for the ehide state. */
    private void pollForEhideState(ITestDevice device, String state) throws Exception {
        Instant deadline = Instant.now().plus(POLLING_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                device.waitForDeviceAvailable(WAIT_FOR_DEVICE_ONLINE_TIMEOUT.toMillis());
            } catch (DeviceNotAvailableException e) {
                throw new DeviceRuntimeException(
                        "Device is not available", e, DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
            }
            // Check the result of the command this time.
            CommandResult result = executeShellCommand(device, EHIDE_STATUS_CMD);
            if (result.getStatus() != CommandStatus.SUCCESS) {
                throw new DeviceRuntimeException(
                        String.format(
                                "Command '%s' failed. Return code: %d. Stderr: '%s'",
                                EHIDE_STATUS_CMD, result.getExitCode(), result.getStderr()),
                        DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
            }
            String output = result.getStdout().trim();
            if (output.startsWith(EHIDE_STATUS_PREFIX + state)) {
                return;
            }
            CLog.w("Incorrect ehide state: got %s, expected %s", output, state);
            try {
                Thread.sleep(POLLING_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                CLog.w("Polling for ehide state was interrupted.");
                break;
            }
        }
        throw new TargetSetupError(
                "Failed to reach ehide state: " + state, device.getDeviceDescriptor());
    }
}
