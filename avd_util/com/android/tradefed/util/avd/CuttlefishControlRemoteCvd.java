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

package com.android.tradefed.util.avd;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandResultWith;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RemoteFileUtil;
import com.android.tradefed.util.RemoteSshUtil;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Interacts with a remote Cuttlefish device using internal commands when interacting with devices
 * launched from `acloud`. Do not use this as a reference for interacting with Cuttlefish devices.
 */
public class CuttlefishControlRemoteCvd implements CuttlefishControl {

    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 300000;

    private static final long DEFAULT_FETCH_FILE_TIMEOUT_MS = 5 * 60 * 1000;

    // The remote instance with the Cuttlefish device.
    private String mHost;

    // The user on the remote instance with the Cuttlefish device.
    private String mUser;

    // The ssh key to use when trying to remotely interact with the Cuttlefish device.
    private File mSshPrivateKey;

    // The device number of the Cuttlefish device to interact with
    // (in case a user launches multiple devices?).
    private Integer mDeviceOffset = null;

    // If true, internal binaries must be found inside of a `/tmp`
    // directory.
    private boolean mRemoteIsOxygen = false;

    private IRunUtil mRunUtil = null;

    public CuttlefishControlRemoteCvd(
            String host,
            String user,
            File sshPrivateKey,
            Integer deviceOffset,
            boolean remoteIsOxygen,
            IRunUtil runUtil) {
        mHost = host;
        mUser = user;
        mSshPrivateKey = sshPrivateKey;
        mDeviceOffset = deviceOffset;
        mRemoteIsOxygen = remoteIsOxygen;
        mRunUtil = runUtil;
    }

    private CommandResult buildErrorResult(String error) {
        CommandResult commandResult = new CommandResult(CommandStatus.EXCEPTION);
        commandResult.setStderr(error);
        return commandResult;
    }

    CommandResult run(String binary, String args) {
        String command = String.format("/home/%s/bin/%s %s", mUser, binary, args);
        if (mDeviceOffset != null) {
            command =
                    String.format(
                            "HOME=/home/%s/acloud_cf_%d acloud_cf_%d/bin/%s %s -instance_num %d",
                            mUser,
                            mDeviceOffset + 1,
                            mDeviceOffset + 1,
                            binary,
                            args,
                            mDeviceOffset + 1);
        }

        if (mRemoteIsOxygen) {
            CommandResult findBinaryResult =
                    RemoteSshUtil.remoteSshCommandExec(
                            mHost,
                            mUser,
                            mSshPrivateKey,
                            mRunUtil,
                            DEFAULT_COMMAND_TIMEOUT_MS,
                            String.format("toybox find /tmp -name %s", binary).split(" "));
            if (!CommandStatus.SUCCESS.equals(findBinaryResult.getStatus())) {
                return buildErrorResult(
                        String.format(
                                "Failed to run command: failed to locate %s: %s",
                                binary, findBinaryResult.getStderr()));
            }

            String commandPath = findBinaryResult.getStdout().trim();
            // Remove tailing `/bin/COMMAND`
            String tmpDir = commandPath.substring(0, commandPath.length() - (binary.length() + 5));
            command = String.format("HOME=%s %s %s", tmpDir, commandPath, args);
        }

        return RemoteSshUtil.remoteSshCommandExec(
                mHost,
                mUser,
                mSshPrivateKey,
                mRunUtil,
                DEFAULT_COMMAND_TIMEOUT_MS,
                command.split(" "));
    }

    @Override
    public CommandResultWith<Integer> addDisplay(
            int width, int height, int dpi, int refreshRateHz) {
        CommandResultWith<CuttlefishDisplays> oldDisplaysResult = listDisplays();
        if (!oldDisplaysResult.isSuccess()) {
            return CommandResultWith.error(oldDisplaysResult.getCommandResult());
        }

        CuttlefishDisplays oldDisplays = oldDisplaysResult.getValue();

        CommandResult addDisplayResult =
                run(
                        "cvd_internal_display",
                        String.format(
                                "add --display=width=%d,height=%d,dpi=%d,refresh_rate_hz=%d",
                                width, height, dpi, refreshRateHz));
        if (!CommandStatus.SUCCESS.equals(addDisplayResult.getStatus())) {
            return CommandResultWith.error(addDisplayResult);
        }
        CLog.d("`cvd_internal_display add` stdout: %s", addDisplayResult.getStdout());

        CommandResultWith<CuttlefishDisplays> newDisplaysResult = listDisplays();
        if (!newDisplaysResult.isSuccess()) {
            return CommandResultWith.error(newDisplaysResult.getCommandResult());
        }
        CuttlefishDisplays newDisplays = newDisplaysResult.getValue();

        Set<Integer> oldDisplayNumbers = new HashSet<Integer>(oldDisplays.displays.keySet());
        Set<Integer> newDisplayNumbers = new HashSet<Integer>(newDisplays.displays.keySet());
        newDisplayNumbers.removeAll(oldDisplayNumbers);
        if (newDisplayNumbers.size() != 1) {
            return CommandResultWith.errorString(
                    String.format(
                            "Unexpected number of new displays after add: %s", newDisplayNumbers));
        }
        int newDisplayNumber = newDisplayNumbers.iterator().next();
        return CommandResultWith.success(newDisplayNumber, addDisplayResult);
    }

    @Override
    public CommandResultWith<CuttlefishDisplays> listDisplays() {
        CommandResult listDisplaysResult = run("cvd_internal_display", "list");
        if (!CommandStatus.SUCCESS.equals(listDisplaysResult.getStatus())) {
            return CommandResultWith.error(listDisplaysResult);
        }
        CLog.d("`cvd_internal_display list` stdout: %s", listDisplaysResult.getStdout());

        CuttlefishDisplays cuttlefishDisplays = null;
        try {
            cuttlefishDisplays =
                    CuttlefishUtils.parseCvdListDisplayOutput(listDisplaysResult.getStdout());
        } catch (JSONException e) {
            return CommandResultWith.errorString(
                    String.format(
                            "Failed to parse Cuttlefish displays from json: %s . Error: %s",
                            listDisplaysResult.getStdout(), e.toString()));
        }

        return CommandResultWith.success(cuttlefishDisplays, listDisplaysResult);
    }

    @Override
    public CommandResult removeDisplay(int displayNumber) {
        CommandResult removeDisplayResult =
                run("cvd_internal_display", String.format("remove --display=%d", displayNumber));
        CLog.d("`cvd_internal_display remove` stdout: %s", removeDisplayResult.getStdout());
        return removeDisplayResult;
    }

    @Override
    public CommandResultWith<CuttlefishScreenshot> screenshotDisplay(int displayNumber) {
        try {
            String remoteScreenshotTempFile =
                    String.format("/tmp/screenshot_%s.png", UUID.randomUUID().toString());

            CommandResult screenshotDisplayResult =
                    run(
                            "cvd_internal_display",
                            String.format(
                                    "screenshot --display=%d --screenshot_path=%s",
                                    displayNumber, remoteScreenshotTempFile));
            if (!CommandStatus.SUCCESS.equals(screenshotDisplayResult.getStatus())) {
                return CommandResultWith.error(screenshotDisplayResult);
            }
            CLog.d(
                    "`cvd_internal_display screenshot` stdout: %s",
                    screenshotDisplayResult.getStdout());

            File localScreenshotTempFile =
                    RemoteFileUtil.fetchRemoteFile(
                            mHost,
                            mUser,
                            mSshPrivateKey,
                            mRunUtil,
                            DEFAULT_FETCH_FILE_TIMEOUT_MS,
                            remoteScreenshotTempFile);
            localScreenshotTempFile.deleteOnExit();

            CuttlefishScreenshot cuttlefishScreenshot = new CuttlefishScreenshot();
            cuttlefishScreenshot.screenshotMimeType = "image/png";
            cuttlefishScreenshot.screenshotBytes =
                    Files.readAllBytes(localScreenshotTempFile.toPath());
            return CommandResultWith.success(cuttlefishScreenshot, screenshotDisplayResult);
        } catch (IOException e) {
            return CommandResultWith.errorString(String.format("Failed to read screenshot: %s", e));
        }
    }
}
