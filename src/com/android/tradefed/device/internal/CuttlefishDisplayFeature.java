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

package com.android.tradefed.device.internal;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.connection.AbstractConnection;
import com.android.tradefed.device.connection.AdbSshConnection;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.service.IRemoteFeature;
import com.android.tradefed.testtype.ITestInformationReceiver;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandResultWith;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.avd.CuttlefishControl;
import com.android.tradefed.util.avd.CuttlefishControlLocalCvd;

import com.google.protobuf.InvalidProtocolBufferException;
import com.proto.tradefed.feature.ErrorInfo;
import com.proto.tradefed.feature.FeatureRequest;
import com.proto.tradefed.feature.FeatureResponse;
import com.proto.tradefed.feature.internal.CuttlefishDisplay;
import com.proto.tradefed.feature.internal.CuttlefishDisplayAddRequest;
import com.proto.tradefed.feature.internal.CuttlefishDisplayFeatureRequest;
import com.proto.tradefed.feature.internal.CuttlefishDisplayFeatureResponse;
import com.proto.tradefed.feature.internal.CuttlefishDisplayListResponse;
import com.proto.tradefed.feature.internal.CuttlefishDisplayRemoveRequest;
import com.proto.tradefed.feature.internal.CuttlefishDisplayScreenshotRequest;

import java.util.Base64;

/**
 * Tradefed server implementation to interact with Cuttlefish display features (effectively `cvd
 * display` locally).
 */
public class CuttlefishDisplayFeature
        implements IRemoteFeature, IConfigurationReceiver, ITestInformationReceiver {

    public static final String CUTTLEFISH_DISPLAY_FEATURE_NAME = "cuttlefishDisplay";

    public static final String ARG_DEVICE_NAME = "device_name";

    public static final String ARG_DISPLAY_REQUEST_PROTO = "display_request_proto";

    private IConfiguration mConfig;
    private TestInformation mTestInformation;

    @Override
    public String getName() {
        return CUTTLEFISH_DISPLAY_FEATURE_NAME;
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfig = configuration;
    }

    @Override
    public void setTestInformation(TestInformation testInformation) {
        mTestInformation = testInformation;
    }

    @Override
    public TestInformation getTestInformation() {
        return mTestInformation;
    }

    private FeatureResponse buildErrorResponse(String message) {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(ErrorInfo.newBuilder().setErrorTrace(message));
        return responseBuilder.build();
    }

    private FeatureResponse buildSuccessResponse(CuttlefishDisplayFeatureResponse displayResponse) {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setResponse(
                Base64.getEncoder().encodeToString(displayResponse.toByteArray()));
        return responseBuilder.build();
    }

    private String getConectionName(AbstractConnection connection) {
        return connection != null ? connection.getClass().getSimpleName() : "[null]";
    }

    private FeatureResponse addDisplay(
            CuttlefishControl cuttlefishControl, CuttlefishDisplayAddRequest addDisplayRequest) {
        CommandResultWith<Integer> addDisplayResult =
                cuttlefishControl.addDisplay(
                        addDisplayRequest.getWidth(),
                        addDisplayRequest.getHeight(),
                        addDisplayRequest.getDpi(),
                        addDisplayRequest.getRefreshRateHz());
        if (!addDisplayResult.isSuccess()) {
            return buildErrorResponse(
                    String.format(
                            "CuttlefishDisplayFeature: failed to add display: %s",
                            addDisplayResult.getCommandResult().getStderr()));
        }

        CuttlefishDisplayFeatureResponse.Builder displayResponse =
                CuttlefishDisplayFeatureResponse.newBuilder();
        displayResponse.getAddDisplayBuilder().setDisplayNumber(addDisplayResult.getValue());
        return buildSuccessResponse(displayResponse.build());
    }

    private FeatureResponse listDisplays(CuttlefishControl cuttlefishControl) {
        CommandResultWith<CuttlefishControl.CuttlefishDisplays> listDisplaysResult =
                cuttlefishControl.listDisplays();
        if (!listDisplaysResult.isSuccess()) {
            return buildErrorResponse(
                    String.format(
                            "CuttlefishDisplayFeature: failed to list displays: %s",
                            listDisplaysResult.getCommandResult().getStderr()));
        }
        CuttlefishControl.CuttlefishDisplays cuttlefishDisplays = listDisplaysResult.getValue();

        CuttlefishDisplayFeatureResponse.Builder displayResponse =
                CuttlefishDisplayFeatureResponse.newBuilder();
        CuttlefishDisplayListResponse.Builder displayListResponseBuilder =
                displayResponse.getListDisplaysBuilder();
        cuttlefishDisplays.displays.forEach(
                (displayNumber, display) -> {
                    CuttlefishDisplay cuttlefishDisplay =
                            CuttlefishDisplay.newBuilder()
                                    .setWidth(display.width)
                                    .setHeight(display.height)
                                    .setDpi(display.dpi)
                                    .setRefreshRateHz(display.refreshRateHz)
                                    .build();
                    displayListResponseBuilder.putDisplays(displayNumber, cuttlefishDisplay);
                });
        return buildSuccessResponse(displayResponse.build());
    }

    private FeatureResponse removeDisplay(
            CuttlefishControl cuttlefishControl,
            CuttlefishDisplayRemoveRequest removeDisplayRequest) {
        CommandResult removeDisplayResult =
                cuttlefishControl.removeDisplay(removeDisplayRequest.getDisplayNumber());
        if (!CommandStatus.SUCCESS.equals(removeDisplayResult.getStatus())) {
            return buildErrorResponse(
                    String.format(
                            "CuttlefishDisplayFeature: failed to remove display %s",
                            removeDisplayResult.getStderr()));
        }
        CuttlefishDisplayFeatureResponse.Builder displayResponse =
                CuttlefishDisplayFeatureResponse.newBuilder();
        displayResponse.getRemoveDisplayBuilder();
        return buildSuccessResponse(displayResponse.build());
    }

    private FeatureResponse screenshotDisplay(
            CuttlefishControl cuttlefishControl,
            CuttlefishDisplayScreenshotRequest screenshotDisplayRequest) {
        CommandResultWith<CuttlefishControl.CuttlefishScreenshot> screenshotDisplayResult =
                cuttlefishControl.screenshotDisplay(screenshotDisplayRequest.getDisplayNumber());
        if (!screenshotDisplayResult.isSuccess()) {
            return buildErrorResponse(
                    String.format(
                            "CuttlefishDisplayFeature: failed to screenshot display: %s",
                            screenshotDisplayResult.getCommandResult().getStderr()));
        }
        CuttlefishControl.CuttlefishScreenshot screenshot = screenshotDisplayResult.getValue();

        CuttlefishDisplayFeatureResponse.Builder displayResponse =
                CuttlefishDisplayFeatureResponse.newBuilder();
        displayResponse
                .getScreenshotDisplayBuilder()
                .setScreenshotMimeType(screenshot.screenshotMimeType)
                .setScreenshotBytes(Base64.getEncoder().encodeToString(screenshot.screenshotBytes));
        return buildSuccessResponse(displayResponse.build());
    }

    private CuttlefishControl getCuttlefishControl(ITestDevice device) {
        AbstractConnection deviceConnection = device.getConnection();
        if (deviceConnection instanceof AdbSshConnection) {
            AdbSshConnection adbShhConnection = (AdbSshConnection) deviceConnection;
            return adbShhConnection.getCuttlefishControl();
        } else {
            CLog.d(
                    "CuttlefishDisplayFeature: found local device, using"
                            + " CuttlefishControlLocalCvd.");
            return new CuttlefishControlLocalCvd();
        }
    }

    @Override
    public FeatureResponse execute(FeatureRequest request) {
        String deviceName = request.getArgsMap().get(ARG_DEVICE_NAME);
        if (deviceName == null) {
            return buildErrorResponse(
                    String.format("CuttlefishDisplayFeature: missing %s arg.", ARG_DEVICE_NAME));
        }

        String displayRequestString = request.getArgsMap().get(ARG_DISPLAY_REQUEST_PROTO);
        if (displayRequestString == null) {
            return buildErrorResponse(
                    String.format(
                            "CuttlefishDisplayFeature: missing %s arg.",
                            ARG_DISPLAY_REQUEST_PROTO));
        }

        CuttlefishDisplayFeatureRequest displayRequest = null;
        try {
            displayRequest =
                    CuttlefishDisplayFeatureRequest.parseFrom(
                            Base64.getDecoder().decode(displayRequestString));
        } catch (InvalidProtocolBufferException e) {
            return buildErrorResponse(
                    String.format(
                            "CuttlefishDisplayFeature: failed to parse %s arg.",
                            ARG_DISPLAY_REQUEST_PROTO));
        }
        CLog.i("CuttlefishDisplayFeature: received request: %s", displayRequest.toString());

        IDeviceConfiguration configHolder = mConfig.getDeviceConfigByName(deviceName);
        int index = 0;
        for (IDeviceConfiguration deviceConfig : mConfig.getDeviceConfig()) {
            if (deviceConfig == configHolder) {
                break;
            }
            index++;
        }
        mTestInformation.setActiveDeviceIndex(index);

        CuttlefishControl cuttlefishControl = getCuttlefishControl(mTestInformation.getDevice());

        switch (displayRequest.getDisplayRequestOneofCase()) {
            case ADD_DISPLAY:
                return addDisplay(cuttlefishControl, displayRequest.getAddDisplay());
            case LIST_DISPLAYS:
                return listDisplays(cuttlefishControl);
            case REMOVE_DISPLAY:
                return removeDisplay(cuttlefishControl, displayRequest.getRemoveDisplay());
            case SCREENSHOT_DISPLAY:
                return screenshotDisplay(cuttlefishControl, displayRequest.getScreenshotDisplay());
            default:
                return buildErrorResponse(
                        String.format("CuttlefishDisplayFeature: unhandled request type."));
        }
    }
}
