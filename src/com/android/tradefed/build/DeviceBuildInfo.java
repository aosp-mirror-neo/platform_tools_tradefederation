/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.tradefed.build;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * A {@link IBuildInfo} that represents a complete Android device build and (optionally) its tests.
 */
public class DeviceBuildInfo extends BuildInfo implements IDeviceBuildInfo {

    private static final long serialVersionUID = BuildSerializedVersion.VERSION;
    private String mDeviceBuildFlavor = null;
    private transient Optional<FuseMountManager> mOptionalFuseMountManager = null;

    public DeviceBuildInfo() {
        super();
    }

    public DeviceBuildInfo(String buildId, String buildTargetName) {
        super(buildId, buildTargetName);
    }

    public DeviceBuildInfo(BuildInfo buildInfo) {
        super(buildInfo);
    }

    @VisibleForTesting
    Optional<FuseMountManager> getFuseMountManager() {
        // Lazy-initialize the manager by calling the factory.
        if (mOptionalFuseMountManager == null) {
            try {
                mOptionalFuseMountManager =
                        Optional.of(
                                FuseMountManagerFactory.getInstance().createCasFuseMountManager());
            } catch (IOException e) {
                CLog.w(
                        "Failed to get FUSE mount manager, FUSE mounting will be disabled. Error:"
                            + " %s",
                        e);
                mOptionalFuseMountManager = Optional.empty();
            }
        }
        return mOptionalFuseMountManager;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link #getDeviceImageVersion()} if not {@code null}, else
     * {@link IBuildInfo#UNKNOWN_BUILD_ID}
     *
     * @see #getDeviceImageVersion()
     */
    @Override
    public String getDeviceBuildId() {
        String buildId = getDeviceImageVersion();
        return buildId == null ? UNKNOWN_BUILD_ID : buildId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDeviceBuildFlavor() {
        return mDeviceBuildFlavor == null ? getBuildFlavor() : mDeviceBuildFlavor;
    }

    /** {@inheritDoc} */
    @Override
    public void setDeviceBuildFlavor(String deviceBuildFlavor) {
        mDeviceBuildFlavor = deviceBuildFlavor;
    }

    /**
     * Get the local device image zip file. The return value can be a directory which is the device
     * image zip file uncompressed. Note that in the case of a directory, it can be a mounted
     * read-only virtual filesystem which limits the operations that can be applied to the directory
     * or its contents.
     */
    @Override
    public File getDeviceImageFile() {
        File deviceImage = getFile(BuildInfoFileKey.DEVICE_IMAGE);
        if (deviceImage == null) {
            return null;
        }

        return getFuseMountManager()
                .map(manager -> mountIfPossible(manager, deviceImage))
                .orElse(deviceImage);
    }

    /**
     * Mount the device image if it is a chunked uncompressed device image.
     *
     * <p>IMPORTANT: No attempt is made to mount a compressed device image.
     *
     * @param manager the fuse mount manager to use
     * @param deviceImage the device image file to mount
     * @return the mounted file or the original file if it is not a CAS file or mounting fails
     */
    private File mountIfPossible(FuseMountManager manager, File deviceImage) {
        // TODO(frankfeng): If source data is valid but mounting fails, it is critical to convert
        // the chunks back to device image files when only chunks are available. Otherwise, it would
        // be an infra error.
        if (manager.isValidSourceData(deviceImage.toPath())) {
            try {
                // For rollout phase 1, collect metrics for mounting without returning the mounted
                // path to avoid breaking.
                manager.mount(deviceImage.toPath());
                manager.unmount(deviceImage.toPath());
                // TODO(frankfeng): Return the mounted path after rollout phase 1.
                return deviceImage;

            } catch (IOException | InterruptedException e) {
                CLog.i("Failed to mount %s", deviceImage);
                CLog.e(e);
                // No re-throw as this is infra implementation detail and should be transparent to
                // getDeviceImageFile() callers.
            }
        }
        return deviceImage;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDeviceImageVersion() {
        return getVersion(BuildInfoFileKey.DEVICE_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDeviceImageFile(File deviceImageFile, String version) {
        setFile(BuildInfoFileKey.DEVICE_IMAGE, deviceImageFile, version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getUserDataImageFile() {
        return getFile(BuildInfoFileKey.USERDATA_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getUserDataImageVersion() {
        return getVersion(BuildInfoFileKey.USERDATA_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUserDataImageFile(File userDataFile, String version) {
        setFile(BuildInfoFileKey.USERDATA_IMAGE, userDataFile, version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getTestsDir() {
        return getFile(BuildInfoFileKey.TESTDIR_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTestsDirVersion() {
        return getVersion(BuildInfoFileKey.TESTDIR_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setTestsDir(File testsDir, String version) {
        setFile(BuildInfoFileKey.TESTDIR_IMAGE, testsDir, version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getBasebandImageFile() {
        return getFile(BuildInfoFileKey.BASEBAND_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getBasebandVersion() {
        return getVersion(BuildInfoFileKey.BASEBAND_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBasebandImage(File basebandFile, String version) {
        setFile(BuildInfoFileKey.BASEBAND_IMAGE, basebandFile, version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getBootloaderImageFile() {
        return getFile(BuildInfoFileKey.BOOTLOADER_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getBootloaderVersion() {
        return getVersion(BuildInfoFileKey.BOOTLOADER_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBootloaderImageFile(File bootloaderImgFile, String version) {
        setFile(BuildInfoFileKey.BOOTLOADER_IMAGE, bootloaderImgFile, version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getOtaPackageFile() {
        return getFile(BuildInfoFileKey.OTA_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getOtaPackageVersion() {
        return getVersion(BuildInfoFileKey.OTA_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setOtaPackageFile(File otaFile, String version) {
        setFile(BuildInfoFileKey.OTA_IMAGE, otaFile, version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getMkbootimgFile() {
        return getFile(BuildInfoFileKey.MKBOOTIMG_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMkbootimgVersion() {
        return getVersion(BuildInfoFileKey.MKBOOTIMG_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMkbootimgFile(File mkbootimg, String version) {
        setFile(BuildInfoFileKey.MKBOOTIMG_IMAGE, mkbootimg, version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getRamdiskFile() {
        return getFile(BuildInfoFileKey.RAMDISK_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRamdiskVersion() {
        return getVersion(BuildInfoFileKey.RAMDISK_IMAGE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRamdiskFile(File ramdisk, String version) {
        setFile(BuildInfoFileKey.RAMDISK_IMAGE, ramdisk, version);
    }

    /** {@inheritDoc} */
    @Override
    public void cleanUp() {
        // It is not adequate to call manager.unMount(getDeviceImageFile()) as setDeviceImageFile()
        // could have been called multiple times with different image files.
        getFuseMountManager().ifPresent(manager -> manager.unmountAll());
        super.cleanUp();
    }

 @Override
    public void cleanUp(List<File> doNotClean) {
        File deviceImage = getFile(BuildInfoFileKey.DEVICE_IMAGE);
        if (deviceImage != null && doNotClean != null && !doNotClean.contains(deviceImage)) {
            getFuseMountManager().ifPresent(manager -> manager.unmount(deviceImage.toPath()));
        }
        super.cleanUp(doNotClean);
    }
}
