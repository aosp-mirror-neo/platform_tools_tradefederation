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
package com.android.tradefed.targetprep;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.VersionedFile;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** A {@link ITargetPreparer} that allows to mix a radio image with the device image. */
@OptionClass(alias = "mix-radio-target-preparer")
public class MixRadioTargetPreparer extends BaseTargetPreparer
        implements ILabPreparer, IConfigurationReceiver {

    @Option(name = "radio-file-key", description = "The key of the downloaded radio image file.")
    private String radioFileKey = "radio.img.gz";

    @Option(name = "radio-build-id", description = "The build id of the radio image.")
    private String radioBuildId;

    private IConfiguration mConfig;

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfig = configuration;
    }

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        IInvocationContext context = testInfo.getContext();
        ITestDevice device = testInfo.getDevice();
        IBuildInfo buildInfo = testInfo.getBuildInfo();
        IDeviceBuildInfo deviceBuild = (IDeviceBuildInfo) buildInfo;

        // Add check for radioBuildId
        if (radioBuildId == null) {
            throw new TargetSetupError(
                    "radio-build-id must be specified in the gcl file.",
                    device.getDeviceDescriptor());
        }

        File tmpRadioDir = null;
        try {
            tmpRadioDir = FileUtil.createTempDir("radio_dir");
            File downloadedRadioFile = new File(tmpRadioDir, "radio.img");
            File srcFile;
            if (buildInfo.getFile(radioFileKey) == null) {
                throw new TargetSetupError(
                        radioFileKey
                                + " is not provided in the gcl file. Can not generate radio image.",
                        device.getDeviceDescriptor());
            } else {
                srcFile = buildInfo.getFile(radioFileKey);
            }

            String suffix = FileUtil.getExtension(srcFile.getName());
            if (suffix.contains("gz")) {
                CLog.i("File was requested to be ungzipped.");
                // File was requested to be ungzipped.
                try (FileInputStream stream = new FileInputStream(srcFile)) {
                    try (GZIPInputStream gzip = new GZIPInputStream(stream)) {
                        FileUtil.writeToFile(gzip, downloadedRadioFile, true);
                        FileUtil.deleteFile(srcFile);
                    }
                }
            } else {
                FileUtil.hardlinkFile(srcFile, downloadedRadioFile);
                FileUtil.deleteFile(srcFile);
            }

            if (tmpRadioDir.listFiles().length == 0) {
                throw new TargetSetupError(
                        "Could not find radio images", device.getDeviceDescriptor());
            }
            mixRadio(device, deviceBuild, radioBuildId, downloadedRadioFile);
        } catch (IOException e) {
            throw new TargetSetupError(
                    "Could not mix device and radio images", e, device.getDeviceDescriptor());
        } finally {
            FileUtil.recursiveDelete(tmpRadioDir);
        }
    }

    /**
     * Create new device image by mixing new radio image to device image.
     *
     * @param device the test device
     * @param deviceBuildInfo the device build info
     * @param downloadedRadioImage the downloaded radio image
     */
    private void mixRadio(
            ITestDevice device,
            IDeviceBuildInfo deviceBuildInfo,
            String radioBuildId,
            File downloadedRadioImage)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        try {
            try {
                mConfig.injectOptionValue("skip-baseband-version-check", "true");
                CLog.i("Skip baseband version check");
            } catch (ConfigurationException e) {
                CLog.e(e);
            }
            // Clean up radio image.
            String radioImageFilePath = deviceBuildInfo.getBasebandImageFile().getAbsolutePath();
            File radioImageFile = deviceBuildInfo.getBasebandImageFile();
            String basebandVersion = deviceBuildInfo.getBasebandVersion();

            ArrayList<File> doNotClean = new ArrayList<File>();
            for (VersionedFile fileRecord : deviceBuildInfo.getFiles()) {
                if (!radioImageFile.equals(fileRecord.getFile()))
                    doNotClean.add(fileRecord.getFile());
            }
            deviceBuildInfo.cleanUp(doNotClean);

            // Replace build ID in baseband version with `basebandVersion`.
            // This is a fake version to make sure the radio image is flashed.
            Matcher versionMatcher = Pattern.compile("(.*-)[^-]+").matcher(basebandVersion);
            String versionReplacement = "$1" + radioBuildId;
            CLog.i("versionReplacement: " + versionReplacement);

            // Replace radio image of deviceBuildInfo w/ the new one and update version.
            radioImageFile.delete();

            File testRadioImageFile = new File(radioImageFilePath);
            FileUtil.hardlinkFile(downloadedRadioImage, testRadioImageFile);

            deviceBuildInfo.setBasebandImage(
                    testRadioImageFile, versionMatcher.replaceAll(versionReplacement));
            CLog.i(
                    "Successfully mixed device %s and radio image %s",
                    deviceBuildInfo, radioBuildId);
        } catch (IOException e) {
            throw new TargetSetupError(
                    "Could not mix device and radio images", e, device.getDeviceDescriptor());
        }
    }
}
