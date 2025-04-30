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
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** A {@link ITargetPreparer} that allows to mix a radio image with the device image. */
@OptionClass(alias = "mix-radio-target-preparer")
public class MixRadioTargetPreparer extends BaseTargetPreparer implements ILabPreparer {

    @Option(name = "radio-file-key", description = "The key of the downloaded radio image file.")
    private String radioFileKey = "radio.img.gz";

    private static final String ANDROID_INFO_FILE_NAME = "android-info.txt";

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        IInvocationContext context = testInfo.getContext();
        ITestDevice device = testInfo.getDevice();
        IBuildInfo buildInfo = testInfo.getBuildInfo();
        IDeviceBuildInfo deviceBuild = (IDeviceBuildInfo) buildInfo;
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
            mixRadio(device, deviceBuild, downloadedRadioFile);
        } catch (IOException e) {
            throw new TargetSetupError(
                    "Could not mix device and radio images", e, device.getDeviceDescriptor());
        } finally {
            FileUtil.recursiveDelete(tmpRadioDir);
        }
    }

    /**
     * Functional interface to update a zip entry. The update method should return true if the name
     * passed to it is a file you want to scan.
     */
    @FunctionalInterface
    interface PatchEntryFn {
        boolean update(String name, InputStream in, OutputStream out) throws IOException;
    }

    /**
     * Copy zip entries from src to dst with a PatchEntryFn.
     *
     * <p>Entries in the zip are processed by patchFn. If the patchFn returns false, this function
     * will copy the entry by transferTo(), otherwise do nothing.
     *
     * @param src the source file
     * @param dst the destination file
     * @param patchFn the PatchEntryFn to be applied on each entry
     */
    private static void copyZipEntries(File src, File dst, PatchEntryFn patchFn)
            throws IOException {
        // Fallbacks to ZipOutputStream to update the zip entry; the latest jdk.zipfs has an
        // issue that the jdk.zipfs doesn't write a valid UNX_IFMT in "external file attributes"
        // for the entries it's updating (see 'writeCEN()' for details), so some zip tools
        // (e.g.: fuse-zip) couldn't handle such entries well. Replaces the updating procedure
        // with ZipOutputStream to workaround it.

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(src));
                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dst))) {
            ZipEntry sourceFile;

            while ((sourceFile = zis.getNextEntry()) != null) {
                zos.putNextEntry(new ZipEntry(sourceFile.getName()));
                if (!patchFn.update(sourceFile.getName(), zis, zos)) {
                    zis.transferTo(zos);
                }
                zos.closeEntry();
            }
        }
    }

    private static void uncheckedWrite(OutputStream out, String data) {
        try {
            out.write(data.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isAndroidInfo(String name) {
        return name.equals(ANDROID_INFO_FILE_NAME);
    }

    private static void processAndroidInfo(InputStream in, OutputStream out) throws IOException {
        InputStreamReader inr = new InputStreamReader(in, StandardCharsets.UTF_8);
        try (Stream<String> lines = new BufferedReader(inr).lines()) {
            lines.filter(line -> !line.startsWith("require version-baseband="))
                    .forEach(line -> uncheckedWrite(out, line + "\n"));
        }
    }

    private static boolean patchAndroidInfoFn(String name, InputStream in, OutputStream out)
            throws IOException {
        if (!isAndroidInfo(name)) {
            return false;
        }

        processAndroidInfo(in, out);
        return true;
    }

    /**
     * Create new device image by mixing new radio image to device image.
     *
     * @param device the test device
     * @param deviceBuildInfo the device build info
     * @param downloadedRadioImage the downloaded radio image
     */
    private void mixRadio(
            ITestDevice device, IDeviceBuildInfo deviceBuildInfo, File downloadedRadioImage)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        File newDeviceImage = null;

        try {
            File deviceImage = deviceBuildInfo.getDeviceImageFile();
            int linkCount =
                    ((Integer) Files.getAttribute(deviceImage.toPath(), "unix:nlink")).intValue();
            CLog.d(
                    "The link count is %s for device image: %s",
                    linkCount, deviceImage.getAbsolutePath());
            if (deviceImage.isDirectory()) {
                CLog.i("deviceImage is a directory");
                Path androidInfoPath = deviceImage.toPath().resolve(ANDROID_INFO_FILE_NAME);
                if (!Files.exists(androidInfoPath)) {
                    throw new TargetSetupError(
                            "Failed to find " + ANDROID_INFO_FILE_NAME,
                            device.getDeviceDescriptor());
                }
                File newAndroidInfoFile = FileUtil.createTempFile("android-info", ".txt");
                try (FileInputStream fis = new FileInputStream(androidInfoPath.toFile());
                        FileOutputStream fos = new FileOutputStream(newAndroidInfoFile)) {
                    processAndroidInfo(fis, fos);
                }
                androidInfoPath.toFile().delete();
                FileUtil.hardlinkFile(newAndroidInfoFile, androidInfoPath.toFile());
            } else {
                CLog.i("deviceImage is a file");
                newDeviceImage = FileUtil.createTempFile("device-img", ".zip");

                // Clone deviceImage to newDeviceImage, and remove lines
                // /^require version-baseband=/ within 'android-info.txt' of newDeviceImage.
                copyZipEntries(
                        deviceImage,
                        newDeviceImage,
                        (name, in, out) -> patchAndroidInfoFn(name, in, out));

                // The deviceBuildInfo ImageFiles are files hardlink'd to cached Android build
                // files. Replace them for this device and without polluting the cached files,
                // delete() the hardlink file first, then copy the new file to original hardlink
                // file path.
                // Replace device image of deviceBuildInfo w/ the android-info.txt-patched one.
                String deviceImagePath = deviceImage.getAbsolutePath();
                deviceImage.delete();
                FileUtil.hardlinkFile(newDeviceImage, new File(deviceImagePath));
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
            String versionReplacement = "$1" + basebandVersion;

            // Replace radio image of deviceBuildInfo w/ the new one and update version.
            radioImageFile.delete();

            File testRadioImageFile = new File(radioImageFilePath);
            FileUtil.hardlinkFile(downloadedRadioImage, testRadioImageFile);

            deviceBuildInfo.setBasebandImage(
                    testRadioImageFile, versionMatcher.replaceAll(versionReplacement));
            CLog.i(
                    "Successfully generated new device image %s out of device %s and radio image"
                            + " %s",
                    newDeviceImage, deviceBuildInfo, basebandVersion);
        } catch (IOException e) {
            throw new TargetSetupError(
                    "Could not mix device and radio images", e, device.getDeviceDescriptor());
        } finally {
            FileUtil.deleteFile(newDeviceImage);
        }
    }
}
