/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.device;

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.IDevice;
import com.android.tradefed.device.DeviceManager.FastbootDevice;
import com.android.tradefed.device.IDeviceSelection.BaseDeviceType;
import com.android.tradefed.device.cloud.ManagedRemoteDevice;
import com.android.tradefed.device.cloud.NestedDeviceStateMonitor;
import com.android.tradefed.device.cloud.NestedRemoteDevice;
import com.android.tradefed.device.cloud.RemoteAndroidVirtualDevice;
import com.android.tradefed.device.cloud.VmRemoteDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.SystemUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factory to create the different kind of devices that can be monitored by Tf
 */
public class ManagedTestDeviceFactory implements IManagedTestDeviceFactory {

    public static final String NOTIFY_AS_NATIVE = "NOTIFY_AS_NATIVE";
    public static final String IPADDRESS_PATTERN =
            "((^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
                    + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
                    + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
                    + "([01]?\\d\\d?|2[0-4]\\d|25[0-5]))|(localhost)){1}";

    protected boolean mFastbootEnabled;
    protected IDeviceManager mDeviceManager;
    protected IDeviceMonitor mAllocationMonitor;

    public ManagedTestDeviceFactory(boolean fastbootEnabled, IDeviceManager deviceManager,
            IDeviceMonitor allocationMonitor) {
        mFastbootEnabled = fastbootEnabled;
        mDeviceManager = deviceManager;
        mAllocationMonitor = allocationMonitor;
    }

    /** Creates a NativeDeviceStateMonitor. */
    private IDeviceStateMonitor createNativeDeviceStateMonitor(IDevice idevice) {
        return new NativeDeviceStateMonitor(mDeviceManager, idevice, mFastbootEnabled);
    }

    /** Checks if the device is an SDV device based on system properties. */
    private boolean isSdvDevice(IDevice idevice) {
        // Cannot check properties on a FastbootDevice or StubDevice
        if (idevice instanceof FastbootDevice || idevice instanceof StubDevice) {
            return false;
        }
        try {
            // Primary check: ro.boot.hardware
            String hardware = idevice.getProperty("ro.boot.hardware");
            CLog.d("get property of  ro.boot.hardware is %s", hardware);
            if ("SDV".equalsIgnoreCase(hardware)) {
                CLog.d("Device %s has ro.boot.hardware=SDV", idevice.getSerialNumber());
                return true;
            }

            // Fallback check: ro.build.product
            String product = idevice.getProperty("ro.build.product");
            CLog.d("get property of ro.build.product is %s", product);

            if (product != null && product.startsWith("sdv_")) {
                CLog.d(
                        "Device %s has ro.build.product starting with sdv_ (%s)",
                        idevice.getSerialNumber(), product);
                return true;
            }
        } catch (Exception e) {
            CLog.w(
                    "Error checking SDV properties on %s: %s. Assuming non-SDV for monitor"
                            + " selection.",
                    idevice.getSerialNumber(), e.getMessage());
        }
        return false;
    }

    @Override
    public IManagedTestDevice createRequestedDevice(IDevice idevice, IDeviceSelection options) {
        IManagedTestDevice testDevice;
        if (BaseDeviceType.NATIVE_DEVICE.equals(options.getBaseDeviceTypeRequested())) {
            testDevice =
                    new NativeDevice(
                            idevice, createNativeDeviceStateMonitor(idevice), mAllocationMonitor);
        } else {
            // Default to-go device is Android full stack device.
            IDeviceStateMonitor monitor = null;
            // Special SDV check only for RemoteAndroidDevice and its subclasses if not native
            if (idevice instanceof RemoteAndroidDevice
                    && isSdvDevice(idevice)) { // Includes RemoteAvdIDevice
                CLog.i(
                        "Requested %s %s identified as SDV by properties: Using"
                                + " NativeDeviceStateMonitor",
                        idevice.getClass().getSimpleName(), idevice.getSerialNumber());
                monitor = createNativeDeviceStateMonitor(idevice);
            }

            if (monitor == null) {
                CLog.i(
                        "Requested %s %s is Using" + " DeviceStateMonitor",
                        idevice.getClass().getSimpleName(), idevice.getSerialNumber());
                monitor = new DeviceStateMonitor(mDeviceManager, idevice, mFastbootEnabled);
            }
            testDevice = new TestDevice(idevice, monitor, mAllocationMonitor);
        }
        testDevice.setDeviceState(TestDeviceState.NOT_AVAILABLE);
        return testDevice;
    }

    /** {@inheritDoc} */
    @Override
    public IManagedTestDevice createDevice(IDevice idevice) {
        IManagedTestDevice testDevice;
        if (idevice instanceof VmRemoteDevice) {
            testDevice =
                    new ManagedRemoteDevice(
                            idevice,
                            new DeviceStateMonitor(mDeviceManager, idevice, mFastbootEnabled),
                            mAllocationMonitor);
        } else if (idevice instanceof RemoteAvdIDevice) {
            IDeviceStateMonitor monitor;
            if (isSdvDevice(idevice)) {
                CLog.i(
                        "RemoteAvdIDevice %s identified as SDV by properties: Using"
                                + " NativeDeviceStateMonitor",
                        idevice.getSerialNumber());
                monitor = createNativeDeviceStateMonitor(idevice);
            } else {
                CLog.d(
                        "RemoteAvdIDevice %s: Using default DeviceStateMonitor",
                        idevice.getSerialNumber());
                monitor = new DeviceStateMonitor(mDeviceManager, idevice, mFastbootEnabled);
            }
            testDevice = new RemoteAndroidVirtualDevice(idevice, monitor, mAllocationMonitor);
        } else if (idevice instanceof StubLocalAndroidVirtualDevice) {
            // Virtual device to be launched by TradeFed locally.
            testDevice =
                    new LocalAndroidVirtualDevice(
                            idevice,
                            new DeviceStateMonitor(mDeviceManager, idevice, mFastbootEnabled),
                            mAllocationMonitor);
        } else if (isTcpDeviceSerial(idevice.getSerialNumber())) {
            if (isRemoteEnvironment()) {
                // If we are in a remote environment, treat the device as such
                testDevice =
                        new NestedRemoteDevice(
                                idevice,
                                new NestedDeviceStateMonitor(
                                        mDeviceManager, idevice, mFastbootEnabled),
                                mAllocationMonitor);
            } else {
                Set<String> nativeSerials = getNativeDeviceSerials();
                if (nativeSerials.contains(idevice.getSerialNumber())) {
                    testDevice =
                            new NativeDevice(
                                    idevice,
                                    createNativeDeviceStateMonitor(idevice),
                                    mAllocationMonitor);
                } else {
                    // Handle device connected via 'adb connect'
                    // Check if it's an SDV device
                    IDeviceStateMonitor monitor;
                    if (isSdvDevice(idevice)) {
                        CLog.i(
                                "TCP Device %s identified as SDV by properties: Using"
                                        + " NativeDeviceStateMonitor",
                                idevice.getSerialNumber());
                        monitor = createNativeDeviceStateMonitor(idevice);
                    } else {
                        monitor = new DeviceStateMonitor(mDeviceManager, idevice, mFastbootEnabled);
                    }
                    testDevice = new RemoteAndroidDevice(idevice, monitor, mAllocationMonitor);
                }
            }
        } else {
            Set<String> nativeSerials = getNativeDeviceSerials();
            if (nativeSerials.contains(idevice.getSerialNumber())) {
                testDevice =
                        new NativeDevice(
                                idevice,
                                createNativeDeviceStateMonitor(idevice),
                                mAllocationMonitor);
            } else {
                // Default to-go device is Android full stack device.
                testDevice =
                        new TestDevice(
                                idevice,
                                new DeviceStateMonitor(mDeviceManager, idevice, mFastbootEnabled),
                                mAllocationMonitor);
            }
        }

        // Set initial state
        if (idevice instanceof FastbootDevice) {
            if (((FastbootDevice) idevice).isFastbootD()) {
                testDevice.setDeviceState(TestDeviceState.FASTBOOTD);
            } else {
                testDevice.setDeviceState(TestDeviceState.FASTBOOT);
            }
        } else if (idevice instanceof StubDevice) {
            testDevice.setDeviceState(TestDeviceState.NOT_AVAILABLE);
        } else if (testDevice.getDeviceState() == null) {
            testDevice.setDeviceState(TestDeviceState.NOT_AVAILABLE);
        }

        testDevice.setFastbootEnabled(mFastbootEnabled);
        testDevice.setFastbootPath(mDeviceManager.getFastbootPath());
        testDevice.setAdbPath(mDeviceManager.getAdbPath());
        return testDevice;
    }

    /** Return the default {@link IRunUtil} instance. */
    @VisibleForTesting
    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /**
     * Return true if we are currently running in a remote environment. This will alter the device
     * behavior.
     */
    @VisibleForTesting
    protected boolean isRemoteEnvironment() {
        return SystemUtil.isRemoteEnvironment();
    }

    /** Create a {@link CollectingOutputReceiver}. */
    @VisibleForTesting
    protected CollectingOutputReceiver createOutputReceiver() {
        return new CollectingOutputReceiver();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFastbootEnabled(boolean enable) {
        mFastbootEnabled = enable;
    }

    private Set<String> getNativeDeviceSerials() {
        Set<String> nativeSerials = new HashSet<>();
        String nativeEnv = System.getenv(NOTIFY_AS_NATIVE);
        if (nativeEnv != null) {
            nativeSerials.addAll(Arrays.asList(nativeEnv.split(",")));
        }
        return nativeSerials;
    }

    /**
     * Helper to device if it's a serial from a remotely connected device. serial format of tcp
     * device is <ip or locahost>:<port>
     */
    public static boolean isTcpDeviceSerial(String serial) {
        final String remotePattern = IPADDRESS_PATTERN + "(:)([0-9]{2,5})(\\b)";
        Pattern pattern = Pattern.compile(remotePattern);
        Matcher match = pattern.matcher(serial.trim());
        return match.find();
    }
}
