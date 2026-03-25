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
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.util.CasUtil;
import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A {@link IBuildInfo} that represents a complete Android device build and (optionally) its tests.
 */
public class DeviceBuildInfo extends BuildInfo implements IDeviceBuildInfo {

    private static final long serialVersionUID = BuildSerializedVersion.VERSION;
    private String mDeviceBuildFlavor = null;
    private transient Optional<FuseMountManager> mOptionalFuseMountManager = null;
    private transient Optional<CasConverter> mOptionalCasConverter = null;
    private static final String CAS_FUSE_MOUNT = "cas_fuse_mount";
    private static final String CAS_CONVERT = "cas_convert";

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
            if (!FuseMountManagerFactory.isFuseAvailable()) {
                CLog.w("FUSE is not available, FUSE mounting will be disabled.");
                mOptionalFuseMountManager = Optional.empty();
                return mOptionalFuseMountManager;
            }

            try {
                mOptionalFuseMountManager =
                        Optional.of(
                                FuseMountManagerFactory.getInstance().createCasFuseMountManager());
                CLog.i(
                        "Created FuseMountManager for DeviceBuildInfo(0x%s).",
                        Integer.toHexString(System.identityHashCode(this)));
            } catch (IOException | ExceptionInInitializerError | NoClassDefFoundError e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                CLog.w(
                        "Failed to get FUSE mount manager, can't fuse mount device image. Error:"
                                + " %s",
                        cause.getMessage());
                mOptionalFuseMountManager = Optional.empty();
            }
        } else {
            CLog.d(
                    "Reusing FuseMountManager for DeviceBuildInfo(0x%s).",
                    Integer.toHexString(System.identityHashCode(this)));
        }
        return mOptionalFuseMountManager;
    }

    Optional<CasConverter> getCasConverter() {
        if (mOptionalCasConverter == null) {
            try {
                mOptionalCasConverter = Optional.of(CasConverter.getInstance());
            } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                CLog.w(
                        "Failed to get CAS converter, can't convert chunked device image to"
                                + " uncompressed device image. Error: %s",
                        cause.getMessage());
                mOptionalCasConverter = Optional.empty();
            }
        }
        return mOptionalCasConverter;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link #getDeviceImageVersion()} if not {@code null}, else {@link
     *     IBuildInfo#UNKNOWN_BUILD_ID}
     * @see #getDeviceImageVersion()
     */
    @Override
    public String getDeviceBuildId() {
        String buildId = getDeviceImageVersion();
        return buildId == null ? UNKNOWN_BUILD_ID : buildId;
    }

    /** {@inheritDoc} */
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

        if (CasUtil.isUncompressedDeviceImage(deviceImage.toPath())) {
            CLog.i("Device image is uncompressed: '%s'.", deviceImage);
            return deviceImage;
        }

        if (!CasUtil.isChunkedDir(deviceImage.toPath())) {
            CLog.i("Device image is not chunked: '%s'.", deviceImage);
            return deviceImage; // This can be a compressed image zip file.
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
        if (CasUtil.isChunkedDir(deviceImage.toPath())) {
            try (CloseableTraceScope ignore = new CloseableTraceScope(CAS_FUSE_MOUNT)) {
                return manager.mount(deviceImage.toPath()).toFile();

            } catch (IOException | InterruptedException e) {
                CLog.i("Failed to mount '%s'.", deviceImage);
                CLog.e(e);
                // No re-throw as this is infra implementation detail and should be transparent to
                // getDeviceImageFile() callers.

                // Fallback: convert to an uncompressed device image.
                convertImageIfNeeded(deviceImage.toPath());
            }
        }
        return deviceImage;
    }

    /**
     * Restore the device image if the image is not usable as-is. This is a fallback for mounting
     * failure.
     *
     * @param image the device image to restore
     * @param convertedImage the converted device image. If null, the conversion is in-place.
     */
    private void convertImageIfNeeded(Path image, Path convertedImage) {
        if (convertedImage == null && CasUtil.isUncompressedDeviceImage(image)) {
            CLog.e("Device image is already uncompressed: '%s', skip conversion.", image);
            return;
        }
        if (!CasUtil.isChunkedDir(image)) {
            CLog.e("Device image is not chunked: `%s`, can't be converted.", image);
            return;
        }

        getCasConverter()
                .ifPresentOrElse(
                        converter -> convertNoThrow(converter, image, convertedImage),
                        () -> CLog.e("UNEXPECTED: cas converter is not available."));
    }

    private void convertImageIfNeeded(Path image) {
        convertImageIfNeeded(image, null);
    }

    /**
     * Converts a chunked device image to an uncompressed device image. Log errors but do not throw.
     *
     * @param image the device image to convert
     * @param convertedImage the converted device image. If null, the conversion is in-place.
     */
    private void convertNoThrow(CasConverter converter, Path image, Path convertedImage) {
        try (CloseableTraceScope ignore = new CloseableTraceScope(CAS_CONVERT)) {
            converter.convert(image, convertedImage);
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String getDeviceImageVersion() {
        return getVersion(BuildInfoFileKey.DEVICE_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public void setDeviceImageFile(File deviceImageFile, String version) {
        setFile(BuildInfoFileKey.DEVICE_IMAGE, deviceImageFile, version);
    }

    /** {@inheritDoc} */
    @Override
    public File getUserDataImageFile() {
        return getFile(BuildInfoFileKey.USERDATA_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getUserDataImageVersion() {
        return getVersion(BuildInfoFileKey.USERDATA_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public void setUserDataImageFile(File userDataFile, String version) {
        setFile(BuildInfoFileKey.USERDATA_IMAGE, userDataFile, version);
    }

    /** {@inheritDoc} */
    @Override
    public File getTestsDir() {
        return getFile(BuildInfoFileKey.TESTDIR_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getTestsDirVersion() {
        return getVersion(BuildInfoFileKey.TESTDIR_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public void setTestsDir(File testsDir, String version) {
        setFile(BuildInfoFileKey.TESTDIR_IMAGE, testsDir, version);
    }

    /** {@inheritDoc} */
    @Override
    public File getBasebandImageFile() {
        return getFile(BuildInfoFileKey.BASEBAND_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getBasebandVersion() {
        return getVersion(BuildInfoFileKey.BASEBAND_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public void setBasebandImage(File basebandFile, String version) {
        setFile(BuildInfoFileKey.BASEBAND_IMAGE, basebandFile, version);
    }

    /** {@inheritDoc} */
    @Override
    public File getBootloaderImageFile() {
        return getFile(BuildInfoFileKey.BOOTLOADER_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getBootloaderVersion() {
        return getVersion(BuildInfoFileKey.BOOTLOADER_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public void setBootloaderImageFile(File bootloaderImgFile, String version) {
        setFile(BuildInfoFileKey.BOOTLOADER_IMAGE, bootloaderImgFile, version);
    }

    /** {@inheritDoc} */
    @Override
    public File getTrustyImageFile() {
        return getFile(BuildInfoFileKey.TRUSTY_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getTrustyVersion() {
        return getVersion(BuildInfoFileKey.TRUSTY_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public File getOtaPackageFile() {
        return getFile(BuildInfoFileKey.OTA_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getOtaPackageVersion() {
        return getVersion(BuildInfoFileKey.OTA_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public void setOtaPackageFile(File otaFile, String version) {
        setFile(BuildInfoFileKey.OTA_IMAGE, otaFile, version);
    }

    /** {@inheritDoc} */
    @Override
    public File getMkbootimgFile() {
        return getFile(BuildInfoFileKey.MKBOOTIMG_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getMkbootimgVersion() {
        return getVersion(BuildInfoFileKey.MKBOOTIMG_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public void setMkbootimgFile(File mkbootimg, String version) {
        setFile(BuildInfoFileKey.MKBOOTIMG_IMAGE, mkbootimg, version);
    }

    /** {@inheritDoc} */
    @Override
    public File getRamdiskFile() {
        return getFile(BuildInfoFileKey.RAMDISK_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getRamdiskVersion() {
        return getVersion(BuildInfoFileKey.RAMDISK_IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public void setRamdiskFile(File ramdisk, String version) {
        setFile(BuildInfoFileKey.RAMDISK_IMAGE, ramdisk, version);
    }

    /** {@inheritDoc} */
    @Override
    public final void unmountAll() {
        CLog.i(
                "DeviceBuildInfo.unmountAll() for 0x%s",
                Integer.toHexString(System.identityHashCode(this)));
        getFuseMountManager().ifPresent(manager -> manager.unmountAll());
    }

    /** {@inheritDoc} */
    @Override
    public void cleanUp() {
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
