/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.FileListingService;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.InstallReceiver;
import com.android.ddmlib.RawImage;
import com.android.ddmlib.ScreenRecorderOptions;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.SyncException;
import com.android.ddmlib.TimeoutException;
import com.android.tradefed.device.server.SyncService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** AdbShimDevice placeholder implementation of a {@link IDevice}. */
public class AdbShimDevice implements IDevice {
    private static final String RE_EMULATOR_SN = "emulator-(\\d+)"; // $NON-NLS-1$
    private String mSerialNumber;
    private DeviceState mState;

    public AdbShimDevice(String serial, DeviceState initState) {
        mSerialNumber = serial;
        mState = initState;
    }

    public void setSerial(String serial) {
        mSerialNumber = serial;
    }

    /** {@inheritDoc} */
    @Override
    public void createForward(int localPort, int remotePort)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public void createForward(
            int localPort, String remoteSocketName, DeviceUnixSocketNamespace namespace)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public String getAvdName() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public String getAvdPath() {
        return null;
    }


    /** {@inheritDoc} */
    @Override
    public FileListingService getFileListingService() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public String getMountPoint(String name) {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link #getSystemProperty(String)} instead.
     */
    @Override
    @Deprecated
    public Map<String, String> getProperties() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public String getProperty(String name) {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated deprecated in ddmlib with "implementation detail" as reason.
     */
    @Override
    @Deprecated
    public int getPropertyCount() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public RawImage getScreenshot()
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }

    /* (not javadoc)
     * The parent method has no javadoc, so it's invalid for us to attempt to inherit
     */
    @Override
    public RawImage getScreenshot(long timeout, TimeUnit unit)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public String getSerialNumber() {
        return mSerialNumber;
    }

    /** {@inheritDoc} */
    @Override
    public DeviceState getState() {
        return mState;
    }

    /** Changes the state of the device. */
    void setState(DeviceState state) {
        mState = state;
    }

    /** {@inheritDoc} */
    @Override
    public SyncService getSyncService()
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }



    /** {@inheritDoc} */
    @Override
    public void installPackage(String packageFilePath, boolean reinstall, String... extraArgs)
            throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public void installPackage(
            String packageFilePath,
            boolean reinstall,
            InstallReceiver receiver,
            String... extraArgs)
            throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public void installPackage(
            String packageFilePath,
            boolean reinstall,
            InstallReceiver receiver,
            long maxTimeout,
            long maxTimeToOutputResponse,
            TimeUnit maxTimeUnits,
            String... extraArgs)
            throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public void installPackages(
            List<File> apkFilePaths,
            boolean reinstall,
            List<String> extraArgs,
            long timeOutInMs,
            TimeUnit timeunit)
            throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public String uninstallApp(String applicationID, String... extraArgs) throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public void installRemotePackage(String remoteFilePath, boolean reinstall, String... extraArgs)
            throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public void installRemotePackage(
            String remoteFilePath, boolean reinstall, InstallReceiver receiver, String... extraArgs)
            throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public void installRemotePackage(
            String remoteFilePath,
            boolean reinstall,
            InstallReceiver receiver,
            long maxTimeout,
            long maxTimeToOutputResponse,
            TimeUnit maxTimeUnits,
            String... extraArgs)
            throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public boolean isBootLoader() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isEmulator() {
        return mSerialNumber.matches(RE_EMULATOR_SN);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isOffline() {
        return mState == DeviceState.OFFLINE;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isOnline() {
        return mState == DeviceState.ONLINE;
    }

    /** {@inheritDoc} */
    @Override
    public void reboot(String into)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public void removeForward(int localPort, int remotePort)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public void removeForward(
            int localPort, String remoteSocketName, DeviceUnixSocketNamespace namespace)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public void removeForward(int localPort)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public void createReverse(int remotePort, int localPort)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public void removeReverse(int remotePort)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public void removeRemotePackage(String remoteFilePath) throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public String syncPackageToDevice(String localFilePath)
            throws TimeoutException, AdbCommandRejectedException, IOException, SyncException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public String uninstallPackage(String packageName) throws InstallException {
        throw new InstallException(new IOException("shim"));
    }

    /** {@inheritDoc} */
    @Override
    public void pushFile(String local, String remote)
            throws IOException, AdbCommandRejectedException, TimeoutException, SyncException {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void pullFile(String remote, String local)
            throws IOException, AdbCommandRejectedException, TimeoutException, SyncException {
        // ignore
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link #getProperty(String)} instead.
     */
    @Override
    @Deprecated
    public String getPropertySync(String name)
            throws TimeoutException,
                    AdbCommandRejectedException,
                    ShellCommandUnresponsiveException,
                    IOException {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean arePropertiesSet() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link #getProperty(String)} instead.
     */
    @Override
    @Deprecated
    public String getPropertyCacheOrSync(String name)
            throws TimeoutException,
                    AdbCommandRejectedException,
                    ShellCommandUnresponsiveException,
                    IOException {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link #getBattery()} instead.
     */
    @Override
    @Deprecated
    public Integer getBatteryLevel()
            throws TimeoutException,
                    AdbCommandRejectedException,
                    IOException,
                    ShellCommandUnresponsiveException {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link #getBattery(long, TimeUnit)} instead.
     */
    @Override
    @Deprecated
    public Integer getBatteryLevel(long freshnessMs)
            throws TimeoutException,
                    AdbCommandRejectedException,
                    IOException,
                    ShellCommandUnresponsiveException {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void executeShellCommand(
            String command,
            IShellOutputReceiver receiver,
            long maxTimeToOutputResponse,
            TimeUnit maxTimeUnits)
            throws TimeoutException,
                    AdbCommandRejectedException,
                    ShellCommandUnresponsiveException,
                    IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public void executeShellCommand(
            String command,
            IShellOutputReceiver receiver,
            long maxTimeout,
            long maxTimeToOutputResponse,
            TimeUnit maxTimeUnits)
            throws TimeoutException,
                    AdbCommandRejectedException,
                    ShellCommandUnresponsiveException,
                    IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public void executeShellCommand(String command, IShellOutputReceiver receiver)
            throws TimeoutException,
                    AdbCommandRejectedException,
                    ShellCommandUnresponsiveException,
                    IOException {
        throw new IOException("shim");
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link #executeShellCommand(String, IShellOutputReceiver, long, TimeUnit)}.
     */
    @Deprecated
    @Override
    public void executeShellCommand(
            String command, IShellOutputReceiver receiver, int maxTimeToOutputResponse)
            throws TimeoutException,
                    AdbCommandRejectedException,
                    ShellCommandUnresponsiveException,
                    IOException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public boolean supportsFeature(Feature feature) {
        return false;
    }

    /* (non-Javadoc)
     * @see com.android.ddmlib.IDevice#supportsFeature(com.android.ddmlib.IDevice.HardwareFeature)
     */
    @Override
    public boolean supportsFeature(HardwareFeature arg0) {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void startScreenRecorder(
            String remoteFilePath, ScreenRecorderOptions options, IShellOutputReceiver receiver)
            throws TimeoutException,
                    AdbCommandRejectedException,
                    IOException,
                    ShellCommandUnresponsiveException {
        // no-op
    }

    /** {@inheritDoc} */
    @Override
    public ListenableFuture<String> getSystemProperty(String name) {
        SettableFuture<String> f = SettableFuture.create();
        f.set(null);
        return f;
    }

    /** {@inheritDoc} */
    @Override
    public Future<Integer> getBattery() {
        SettableFuture<Integer> f = SettableFuture.create();
        f.set(0);
        return f;
    }

    /** {@inheritDoc} */
    @Override
    public Future<Integer> getBattery(long freshnessTime, TimeUnit timeUnit) {
        return getBattery();
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getAbis() {
        return Collections.emptyList();
    }

    /** {@inheritDoc} */
    @Override
    public int getDensity() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public String getLanguage() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public String getRegion() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRoot()
            throws TimeoutException,
                    AdbCommandRejectedException,
                    IOException,
                    ShellCommandUnresponsiveException {
        throw new IOException("shim");
    }

    /** {@inheritDoc} */
    @Override
    public boolean root()
            throws TimeoutException,
                    AdbCommandRejectedException,
                    IOException,
                    ShellCommandUnresponsiveException {
        throw new IOException("shim");
    }
}
