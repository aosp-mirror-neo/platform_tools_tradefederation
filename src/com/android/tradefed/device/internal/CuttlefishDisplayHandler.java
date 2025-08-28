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

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.error.IHarnessException;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.service.TradefedFeatureClient;
import com.android.tradefed.util.SerializationUtil;

import com.google.protobuf.InvalidProtocolBufferException;
import com.proto.tradefed.feature.FeatureResponse;
import com.proto.tradefed.feature.internal.CuttlefishDisplayFeatureRequest;
import com.proto.tradefed.feature.internal.CuttlefishDisplayFeatureResponse;
import com.proto.tradefed.feature.internal.CuttlefishDisplayScreenshotResponse;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Utility handling Cuttlefish display requests. This is meant to only be used internally via
 * Cuttlefish specific host tests.
 */
public class CuttlefishDisplayHandler {

    private final TradefedFeatureClient mClient;
    private final IInvocationContext mContext;

    public CuttlefishDisplayHandler() {
        this(new TradefedFeatureClient(), CurrentInvocation.getInvocationContext());
    }

    @VisibleForTesting
    CuttlefishDisplayHandler(TradefedFeatureClient client, IInvocationContext context) {
        mClient = client;
        mContext = context;
    }

    private CuttlefishDisplayFeatureResponse execute(
            ITestDevice device, CuttlefishDisplayFeatureRequest displayRequest)
            throws DeviceNotAvailableException {
        CLog.d(
                "CuttlefishDisplayHandler: executing display request: %s",
                displayRequest.toString());

        FeatureResponse response;
        try {
            Map<String, String> args = new HashMap<>();
            args.put(CuttlefishDisplayFeature.ARG_DEVICE_NAME, mContext.getDeviceName(device));
            args.put(
                    CuttlefishDisplayFeature.ARG_DISPLAY_REQUEST_PROTO,
                    Base64.getEncoder().encodeToString(displayRequest.toByteArray()));
            response =
                    mClient.triggerFeature(
                            CuttlefishDisplayFeature.CUTTLEFISH_DISPLAY_FEATURE_NAME, args);
            CLog.d(
                    "CuttlefishDisplayHandler: received display response: %s",
                    response.getResponse());
        } finally {
            mClient.close();
        }

        if (response.hasErrorInfo()) {
            String trace = response.getErrorInfo().getErrorTrace();
            // Handle if it's an exception error.
            Object o = null;
            try {
                o = SerializationUtil.deserialize(trace);
            } catch (IOException | RuntimeException e) {
                CLog.e("Failed to deserialize feature response: %s", e.getMessage());
            }

            if (o instanceof DeviceNotAvailableException) {
                throw (DeviceNotAvailableException) o;
            } else if (o instanceof IHarnessException) {
                IHarnessException exception = (IHarnessException) o;
                throw new HarnessRuntimeException(
                        "Exception while executing display request.", exception);
            } else if (o instanceof Exception) {
                throw new HarnessRuntimeException(
                        "Exception while executing display request.",
                        (Exception) o,
                        InfraErrorIdentifier.UNDETERMINED);
            }
            throw new HarnessRuntimeException(
                    "Exception while executing display request. Unserialized error response: "
                            + trace,
                    InfraErrorIdentifier.UNDETERMINED);
        }

        if (!response.hasResponse()) {
            throw new RuntimeException("Response proto missing response?");
        }

        CuttlefishDisplayFeatureResponse displayResponse = null;
        try {
            displayResponse =
                    CuttlefishDisplayFeatureResponse.parseFrom(
                            Base64.getDecoder().decode(response.getResponse()));
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to parse display response from FeatureResponse.");
        }
        CLog.d(
                "CuttlefishDisplayHandler: received display response: %s",
                displayResponse.toString());
        return displayResponse;
    }

    /**
     * Calls `cvd display add <params>` for on the given Cuttlefish device.
     *
     * @param device The device to use..
     * @param width The width in pixels for the new display.
     * @param height The height in pixels for the new display.
     * @param dpi The dpi in pixels per inch for the new display.
     * @param refreshRateHz The refresh rate in hertz for the new display.
     * @return Display number of the newly added display, if successful.
     */
    public int addDisplay(ITestDevice device, int width, int height, int dpi, int refreshRateHz)
            throws DeviceNotAvailableException {
        if (device.getIDevice() instanceof StubDevice) {
            CLog.d(
                    "Device '%s' is a stub device. skipping adding display.",
                    device.getSerialNumber());
            return 0;
        }

        CuttlefishDisplayFeatureRequest.Builder displayRequestBuilder =
                CuttlefishDisplayFeatureRequest.newBuilder();
        displayRequestBuilder
                .getAddDisplayBuilder()
                .setWidth(width)
                .setHeight(height)
                .setDpi(dpi)
                .setRefreshRateHz(refreshRateHz);
        CuttlefishDisplayFeatureRequest displayRequest = displayRequestBuilder.build();

        CuttlefishDisplayFeatureResponse displayResponse = execute(device, displayRequest);
        if (!displayResponse.hasAddDisplay()) {
            throw new RuntimeException("Unexpected display response missing add display response?");
        }
        return displayResponse.getAddDisplay().getDisplayNumber();
    }

    public static final class Display {
        public int width;
        public int height;
        public int dpi;
        public int refreshRateHz;

        public Display(int width, int height, int dpi, int refreshRateHz) {
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.refreshRateHz = refreshRateHz;
        }
    }

    /**
     * Calls `cvd display list` for on the given Cuttlefish device.
     *
     * @param device The device to use.
     */
    public Map<Integer, Display> listDisplays(ITestDevice device)
            throws DeviceNotAvailableException {
        if (device.getIDevice() instanceof StubDevice) {
            CLog.d(
                    "Device '%s' is a stub device. skipping list display.",
                    device.getSerialNumber());
            return Map.of();
        }

        CuttlefishDisplayFeatureRequest.Builder displayRequestBuilder =
                CuttlefishDisplayFeatureRequest.newBuilder();
        displayRequestBuilder.getListDisplaysBuilder();
        CuttlefishDisplayFeatureRequest displayRequest = displayRequestBuilder.build();

        CuttlefishDisplayFeatureResponse displayResponse = execute(device, displayRequest);
        if (!displayResponse.hasListDisplays()) {
            throw new RuntimeException(
                    "Unexpected display response missing remove display response?");
        }

        Map<Integer, Display> displays = new HashMap<>();
        displayResponse
                .getListDisplays()
                .getDisplaysMap()
                .forEach(
                        (displayNumber, display) -> {
                            displays.put(
                                    displayNumber,
                                    new Display(
                                            display.getWidth(),
                                            display.getHeight(),
                                            display.getDpi(),
                                            display.getRefreshRateHz()));
                        });
        return displays;
    }

    /**
     * Calls `cvd display remove --display=<display number>` for on the given Cuttlefish device.
     *
     * @param device The device to use..
     * @param displayNumber The display to remove.
     */
    public void removeDisplay(ITestDevice device, int displayNumber)
            throws DeviceNotAvailableException {
        if (device.getIDevice() instanceof StubDevice) {
            CLog.d(
                    "Device '%s' is a stub device. skipping removing display.",
                    device.getSerialNumber());
            return;
        }

        CuttlefishDisplayFeatureRequest.Builder displayRequestBuilder =
                CuttlefishDisplayFeatureRequest.newBuilder();
        displayRequestBuilder.getRemoveDisplayBuilder().setDisplayNumber(displayNumber);
        CuttlefishDisplayFeatureRequest displayRequest = displayRequestBuilder.build();

        CuttlefishDisplayFeatureResponse displayResponse = execute(device, displayRequest);
        if (!displayResponse.hasRemoveDisplay()) {
            throw new RuntimeException(
                    "Unexpected display response missing remove display response?");
        }
    }

    /**
     * Calls `cvd display screenshot --display=<display number>` for on the given Cuttlefish device.
     *
     * @param device The device to use..
     * @param displayNumber The display to screenshot.
     */
    public BufferedImage screenshotDisplay(ITestDevice device, int displayNumber)
            throws DeviceNotAvailableException {
        if (device.getIDevice() instanceof StubDevice) {
            CLog.d(
                    "Device '%s' is a stub device. skipping list display.",
                    device.getSerialNumber());
            return null;
        }

        CuttlefishDisplayFeatureRequest.Builder displayRequestBuilder =
                CuttlefishDisplayFeatureRequest.newBuilder();
        displayRequestBuilder.getScreenshotDisplayBuilder().setDisplayNumber(displayNumber);
        CuttlefishDisplayFeatureRequest displayRequest = displayRequestBuilder.build();

        CuttlefishDisplayFeatureResponse displayResponse = execute(device, displayRequest);
        if (!displayResponse.hasScreenshotDisplay()) {
            throw new RuntimeException(
                    "Unexpected display response missing remove display response?");
        }

        CuttlefishDisplayScreenshotResponse screenshotResponse =
                displayResponse.getScreenshotDisplay();
        byte[] screenshotBytes =
                Base64.getDecoder().decode(screenshotResponse.getScreenshotBytes());

        BufferedImage screenshot;
        try {
            screenshot = ImageIO.read(new ByteArrayInputStream(screenshotBytes));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse display response screenshot.");
        }
        return screenshot;
    }
}
