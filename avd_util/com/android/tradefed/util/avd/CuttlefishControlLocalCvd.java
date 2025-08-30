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
import com.android.tradefed.util.RunUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Interacts with a Cuttlefish device via `cvd` commands. */
public class CuttlefishControlLocalCvd implements CuttlefishControl {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final IRunUtil mRunUtil = new RunUtil();

    private CommandResult run(List<String> commandArgs) {
        return mRunUtil.runTimedCmd(DEFAULT_TIMEOUT_MS, commandArgs.toArray(new String[0]));
    }

    @Override
    public CommandResultWith<Integer> addDisplay(
            int width, int height, int dpi, int refreshRateHz) {
        CommandResultWith<CuttlefishDisplays> oldDisplaysResult = listDisplays();
        if (!oldDisplaysResult.isSuccess()) {
            return CommandResultWith.error(oldDisplaysResult.getCommandResult());
        }
        CuttlefishDisplays oldDisplays = oldDisplaysResult.getValue();

        List<String> addDisplayCommand = new ArrayList<String>();
        addDisplayCommand.add("cvd");
        addDisplayCommand.add("display");
        addDisplayCommand.add("add");
        addDisplayCommand.add(
                String.format(
                        "--display=width=%d,height=%d,dpi=%d,refresh_rate_hz=%d",
                        width, height, dpi, refreshRateHz));
        CommandResult addDisplayResult = run(addDisplayCommand);
        if (!CommandStatus.SUCCESS.equals(addDisplayResult.getStatus())) {
            return CommandResultWith.error(addDisplayResult);
        }
        CLog.d("`cvd display add` stdout: %s", addDisplayResult.getStdout());

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
        List<String> listDisplaysCommand = new ArrayList<String>();
        listDisplaysCommand.add("cvd");
        listDisplaysCommand.add("display");
        listDisplaysCommand.add("list");

        CommandResult listDisplaysResult = run(listDisplaysCommand);
        if (!CommandStatus.SUCCESS.equals(listDisplaysResult.getStatus())) {
            return CommandResultWith.error(listDisplaysResult);
        }
        CLog.d("`cvd display list` stdout: %s", listDisplaysResult.getStdout());

        CuttlefishDisplays cuttlefishDisplays = new CuttlefishDisplays();
        try {
            JSONObject json = new JSONObject(listDisplaysResult.getStdout());
            JSONObject jsonDisplays = json.getJSONObject("displays");
            for (Iterator<String> keyIt = jsonDisplays.keys(); keyIt.hasNext(); ) {
                String displayNumberString = keyIt.next();

                JSONObject jsonDisplay = jsonDisplays.getJSONObject(displayNumberString);
                JSONObject jsonDisplayMode = jsonDisplay.getJSONObject("mode");
                JSONArray jsonDisplayModeWindowed = jsonDisplayMode.getJSONArray("windowed");
                JSONArray jsonDisplayDpi = jsonDisplayMode.getJSONArray("dpi");

                int id = Integer.parseInt(displayNumberString);
                int w = jsonDisplayModeWindowed.getInt(0);
                int h = jsonDisplayModeWindowed.getInt(1);
                int dpi = jsonDisplayDpi.getInt(0);
                int refreshRateHz = jsonDisplay.getInt("refresh-rate");

                CuttlefishDisplay cuttlefishDisplay = new CuttlefishDisplay();
                cuttlefishDisplay.width = w;
                cuttlefishDisplay.height = h;
                cuttlefishDisplay.dpi = dpi;
                cuttlefishDisplay.refreshRateHz = refreshRateHz;
                cuttlefishDisplays.displays.put(id, cuttlefishDisplay);
            }
        } catch (JSONException e) {
            return CommandResultWith.errorString(
                    String.format(
                            "Failed to parse Cuttlefish displays from json: %s",
                            listDisplaysResult.getStdout()));
        }

        return CommandResultWith.success(cuttlefishDisplays, listDisplaysResult);
    }

    @Override
    public CommandResult removeDisplay(int displayNumber) {
        List<String> removeDisplayCommand = new ArrayList<String>();
        removeDisplayCommand.add("cvd");
        removeDisplayCommand.add("display");
        removeDisplayCommand.add("remove");
        removeDisplayCommand.add("--display=" + displayNumber);

        CommandResult removeDisplayResult = run(removeDisplayCommand);
        CLog.d("`cvd display remove` stdout: %s", removeDisplayResult.getStdout());
        return removeDisplayResult;
    }

    @Override
    public CommandResultWith<CuttlefishScreenshot> screenshotDisplay(int displayNumber) {
        try {
            File screenshotTempFile = File.createTempFile("screenshot", ".png");
            screenshotTempFile.deleteOnExit();

            List<String> screenshotDisplayCommand = new ArrayList<String>();
            screenshotDisplayCommand.add("cvd");
            screenshotDisplayCommand.add("display");
            screenshotDisplayCommand.add("screenshot");
            screenshotDisplayCommand.add("--display=" + displayNumber);
            screenshotDisplayCommand.add(
                    "--screenshot_path=" + screenshotTempFile.getAbsolutePath());

            CommandResult screenshotDisplayResult = run(screenshotDisplayCommand);
            if (!CommandStatus.SUCCESS.equals(screenshotDisplayResult.getStatus())) {
                return CommandResultWith.error(screenshotDisplayResult);
            }
            CLog.d("`cvd display screenshot` stdout: %s", screenshotDisplayResult.getStdout());

            CuttlefishScreenshot cuttlefishScreenshot = new CuttlefishScreenshot();
            cuttlefishScreenshot.screenshotMimeType = "image/png";
            cuttlefishScreenshot.screenshotBytes = Files.readAllBytes(screenshotTempFile.toPath());
            return CommandResultWith.success(cuttlefishScreenshot, screenshotDisplayResult);
        } catch (IOException e) {
            return CommandResultWith.errorString(String.format("Failed to read screenshot: %s", e));
        }
    }
}
