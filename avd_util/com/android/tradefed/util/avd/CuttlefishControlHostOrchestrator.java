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

import java.util.Base64;
import java.util.Map;

/** Interacts with a Cuttlefish device via the Cuttlefish Host Orhcestrator. */
public class CuttlefishControlHostOrchestrator implements CuttlefishControl {

    private final HostOrchestratorUtil mHostOrchestratorUtil;

    public CuttlefishControlHostOrchestrator(HostOrchestratorUtil hostOrchestratorUtil) {
        this.mHostOrchestratorUtil = hostOrchestratorUtil;
    }

    @Override
    public CommandResultWith<Integer> addDisplay(
            int width, int height, int dpi, int refreshRateHz) {
        CommandResultWith<HostOrchestratorClient.DisplayAddResponse> addDisplayResult =
                mHostOrchestratorUtil.addDisplay(width, height, dpi, refreshRateHz);
        if (!addDisplayResult.isSuccess()) {
            return CommandResultWith.errorString(
                    String.format(
                            "CuttlefishControlHostOrhcestrator: failed to add display"
                                    + " error: %s",
                            addDisplayResult.getCommandResult().getStderr()));
        }
        HostOrchestratorClient.DisplayAddResponse addDisplayResponse = addDisplayResult.getValue();
        CLog.d("HostOrchestrator add display response: %s", addDisplayResponse.toString());

        return CommandResultWith.success(addDisplayResponse.display_number);
    }

    @Override
    public CommandResultWith<CuttlefishDisplays> listDisplays() {
        CommandResultWith<HostOrchestratorClient.DisplayListResponse> listDisplaysResult =
                mHostOrchestratorUtil.listDisplays();
        if (!listDisplaysResult.isSuccess()) {
            return CommandResultWith.errorString(
                    String.format(
                            "CuttlefishControlHostOrhcestrator: failed to list displays"
                                    + " error: %s",
                            listDisplaysResult.getCommandResult().getStderr()));
        }
        HostOrchestratorClient.DisplayListResponse listDisplaysReseponse =
                listDisplaysResult.getValue();
        CLog.d("HostOrchestrator list displays response: %s", listDisplaysReseponse.toString());

        CuttlefishDisplays cuttlefishDisplays = new CuttlefishDisplays();
        for (Map.Entry<Integer, HostOrchestratorClient.Display> entry :
                listDisplaysReseponse.displays.entrySet()) {
            int displayNumber = entry.getKey();
            HostOrchestratorClient.Display display = entry.getValue();

            // NOTE: Crosvm has separate parameters for horizontal and vertical DPI and the host
            // orchestrator effectively just forwards the raw crosvm output. The Cuttlefish host
            // launcher only exposes a single DPI parameter though.
            if (display.dpi.size() != 2) {
                return CommandResultWith.errorString(
                        String.format(
                                "CuttlefishControlHostOrhcestrator: unexpected dpi dimensions %d",
                                display.dpi.size()));
            }
            if (!display.dpi.get(0).equals(display.dpi.get(1))) {
                return CommandResultWith.errorString(
                        String.format(
                                "CuttlefishControlHostOrhcestrator: unexpected dpi values %d vs %d",
                                display.dpi.get(0), display.dpi.get(1)));
            }

            if (display.mode.windowed.size() != 2) {
                return CommandResultWith.errorString(
                        String.format(
                                "CuttlefishControlHostOrhcestrator: unexpected dimensions %d",
                                display.mode.windowed.size()));
            }

            CuttlefishDisplay cuttlefishDisplay = new CuttlefishDisplay();
            cuttlefishDisplay.width = display.mode.windowed.get(0);
            cuttlefishDisplay.height = display.mode.windowed.get(1);
            cuttlefishDisplay.dpi = display.dpi.get(0);
            cuttlefishDisplay.refreshRateHz = display.refresh_rate_hz;
            cuttlefishDisplays.displays.put(displayNumber, cuttlefishDisplay);
        }
        return CommandResultWith.success(cuttlefishDisplays, listDisplaysResult.getCommandResult());
    }

    @Override
    public CommandResult removeDisplay(int displayNumber) {
        return mHostOrchestratorUtil.removeDisplay(displayNumber);
    }

    @Override
    public CommandResultWith<CuttlefishScreenshot> screenshotDisplay(int displayNumber) {
        CommandResultWith<HostOrchestratorClient.DisplayScreenshotResponse>
                screenshotDisplayResult = mHostOrchestratorUtil.screenshotDisplay(displayNumber);
        if (!screenshotDisplayResult.isSuccess()) {
            return CommandResultWith.errorString(
                    String.format(
                            "CuttlefishDisplayFeature: screenshotDisplay failed with host"
                                    + " orchestrator error: %s",
                            screenshotDisplayResult.getCommandResult().getStderr()));
        }
        HostOrchestratorClient.DisplayScreenshotResponse screenshotDisplayResponse =
                screenshotDisplayResult.getValue();
        CLog.d(
                "HostOrchestrator screenshot display response: %s",
                screenshotDisplayResponse.toString());

        if (screenshotDisplayResponse.screenshot_mime_type == null) {
            return CommandResultWith.errorString(
                    "CuttlefishDisplayFeature: screenshotDisplay failed: host"
                            + " orchestrator response missing screenshot mime type.");
        }
        if (screenshotDisplayResponse.screenshot_bytes == null) {
            return CommandResultWith.errorString(
                    "CuttlefishDisplayFeature: screenshotDisplay failed: host"
                            + " orchestrator response missing screenshot bytes.");
        }

        CuttlefishScreenshot cuttlefishScreenshot = new CuttlefishScreenshot();
        cuttlefishScreenshot.screenshotMimeType = screenshotDisplayResponse.screenshot_mime_type;
        cuttlefishScreenshot.screenshotBytes =
                Base64.getDecoder().decode(screenshotDisplayResponse.screenshot_bytes);
        return CommandResultWith.success(
                cuttlefishScreenshot, screenshotDisplayResult.getCommandResult());
    }
}
