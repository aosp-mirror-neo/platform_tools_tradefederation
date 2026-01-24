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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IDevice.DeviceState;
import com.android.tradefed.device.DeviceManager.FastbootDevice;
import com.android.tradefed.device.cloud.NestedRemoteDevice;
import com.android.tradefed.device.cloud.RemoteAndroidVirtualDevice;
import com.android.tradefed.util.IRunUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit Tests for {@link ManagedTestDeviceFactory} */
@RunWith(JUnit4.class)
public class ManagedTestDeviceFactoryTest {

    private ManagedTestDeviceFactory mFactory;
    @Mock IDeviceManager mMockDeviceManager;
    @Mock IDeviceMonitor mMockDeviceMonitor;
    @Mock IRunUtil mMockRunUtil;
    @Mock IDevice mMockIDevice;
    @Mock FastbootDevice mMockFastbootDevice;
    @Mock RemoteAvdIDevice mMockRemoteAvdIDevice;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mFactory = new ManagedTestDeviceFactory(true, mMockDeviceManager, mMockDeviceMonitor);
        when(mMockDeviceManager.getFastbootPath()).thenReturn("fastboot");
        when(mMockDeviceManager.getAdbPath()).thenReturn("adb");
    }

    @Test
    public void testIsSerialTcpDevice() {
        String input = "127.0.0.1:5555";
        assertTrue(ManagedTestDeviceFactory.isTcpDeviceSerial(input));
    }

    @Test
    public void testIsSerialTcpDevice_localhost() {
        String input = "localhost:54014";
        assertTrue(ManagedTestDeviceFactory.isTcpDeviceSerial(input));
    }

    @Test
    public void testIsSerialTcpDevice_notTcp() {
        String input = "00bf84d7d084cc84";
        assertFalse(ManagedTestDeviceFactory.isTcpDeviceSerial(input));
    }

    @Test
    public void testIsSerialTcpDevice_malformedPort() {
        String input = "127.0.0.1:999989";
        assertFalse(ManagedTestDeviceFactory.isTcpDeviceSerial(input));
    }

    @Test
    public void testIsSerialTcpDevice_nohost() {
        String input = ":5555";
        assertFalse(ManagedTestDeviceFactory.isTcpDeviceSerial(input));
    }

    @Test
    public void testNestedDevice() throws Exception {
        when(mMockDeviceManager.isFileSystemMountCheckEnabled()).thenReturn(false);
        mFactory =
                new ManagedTestDeviceFactory(true, mMockDeviceManager, mMockDeviceMonitor) {
                    @Override
                    protected boolean isRemoteEnvironment() {
                        return true;
                    }
                };
        when(mMockIDevice.getSerialNumber()).thenReturn("127.0.0.1:6520");
        when(mMockIDevice.getState()).thenReturn(DeviceState.ONLINE);
        when(mMockIDevice.getProperty(anyString())).thenReturn(null);

        IManagedTestDevice result = mFactory.createDevice(mMockIDevice);
        assertTrue(result instanceof NestedRemoteDevice);
    }

    private void setUpSdvProperties(
            IDevice mockDevice, boolean isSdvByHardware, boolean isSdvByProduct) {
        when(mockDevice.getProperty("ro.boot.hardware"))
                .thenReturn(isSdvByHardware ? "SDV" : "not_sdv");
        when(mockDevice.getProperty("ro.build.product"))
                .thenReturn(isSdvByProduct ? "sdv_core_cf" : "other_product");
    }

    @Test
    public void testCreateDevice_RemoteAvd_SdvDevice_ByHardware() throws Exception {
        when(mMockRemoteAvdIDevice.getSerialNumber()).thenReturn("sdv_serial_hw");
        setUpSdvProperties(mMockRemoteAvdIDevice, true, false);

        IManagedTestDevice result = mFactory.createDevice(mMockRemoteAvdIDevice);
        assertTrue(result instanceof RemoteAndroidVirtualDevice);
        assertTrue(result.getMonitor() instanceof NativeDeviceStateMonitor);
    }

    @Test
    public void testCreateDevice_RemoteAvd_SdvDevice_ByProduct() throws Exception {
        when(mMockRemoteAvdIDevice.getSerialNumber()).thenReturn("sdv_serial_prod");
        setUpSdvProperties(mMockRemoteAvdIDevice, false, true);

        IManagedTestDevice result = mFactory.createDevice(mMockRemoteAvdIDevice);
        assertTrue(result instanceof RemoteAndroidVirtualDevice);
        assertTrue(result.getMonitor() instanceof NativeDeviceStateMonitor);
    }

    @Test
    public void testCreateDevice_RemoteAvd_NonSdvDevice() throws Exception {
        when(mMockRemoteAvdIDevice.getSerialNumber()).thenReturn("regular_remote_avd");
        setUpSdvProperties(mMockRemoteAvdIDevice, false, false);

        IManagedTestDevice result = mFactory.createDevice(mMockRemoteAvdIDevice);
        assertTrue(result instanceof RemoteAndroidVirtualDevice);
        assertTrue(result.getMonitor() instanceof DeviceStateMonitor);
    }

    @Test
    public void testCreateDevice_RemoteAvd_SdvCheckError() throws Exception {
        when(mMockRemoteAvdIDevice.getSerialNumber()).thenReturn("error_serial");
        when(mMockRemoteAvdIDevice.getProperty(anyString()))
                .thenThrow(new RuntimeException("Simulated error fetching property"));

        IManagedTestDevice result = mFactory.createDevice(mMockRemoteAvdIDevice);
        assertTrue(result instanceof RemoteAndroidVirtualDevice);
        assertTrue(result.getMonitor() instanceof DeviceStateMonitor);
    }

    @Test
    public void testCreateDevice_Physical_SdvPropertiesIgnored() throws Exception {
        when(mMockIDevice.getSerialNumber()).thenReturn("physical_serial");
        setUpSdvProperties(mMockIDevice, true, true); // Has SDV properties

        IManagedTestDevice result = mFactory.createDevice(mMockIDevice);
        assertTrue(result instanceof TestDevice);
        assertTrue(result.getMonitor() instanceof DeviceStateMonitor);
    }

    @Test
    public void testCreateDevice_SdvDevice_Fastboot() throws Exception {
        when(mMockFastbootDevice.getSerialNumber()).thenReturn("fb_serial");
        // Fastboot devices cannot have properties checked, so should not be seen as SDV
        IManagedTestDevice result = mFactory.createDevice(mMockFastbootDevice);
        assertTrue(result instanceof TestDevice);
        assertTrue(result.getMonitor() instanceof DeviceStateMonitor);
    }
}
