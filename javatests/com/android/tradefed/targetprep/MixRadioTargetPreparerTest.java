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

import static org.mockito.Mockito.when;

import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.FileUtil;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Unit tests for {@link MixRadioTargetPreparer} */
@RunWith(JUnit4.class)
public class MixRadioTargetPreparerTest {
    private static final int RANDOM_BYTES_MIN = 4 << 12;
    private static final int RANDOM_BYTES_MAX = 6 << 20;

    private IInvocationContext mContext;
    private TestInformation mTestInfo;
    private IDeviceBuildInfo mBuildInfo;

    @Before
    public void setUp() throws Exception {
        mContext = new InvocationContext();
        mBuildInfo = new DeviceBuildInfo();
        mBuildInfo.setBuildFlavor("flavor");
        mContext.addDeviceBuildInfo("device", mBuildInfo);
        mContext.addAllocatedDevice("device", Mockito.mock(ITestDevice.class));
        mTestInfo = TestInformation.newBuilder().setInvocationContext(mContext).build();
    }

    @Test
    public void testFailsOnMissingDeviceImage() throws Exception {
        MixRadioTargetPreparer mk = new MixRadioTargetPreparer();
        File radioImage = FileUtil.createTempFile("device-img-12345", "zip");
        mBuildInfo.setFile("{radio}radio.img.gz", radioImage, "0");
        try {
            mk.setUp(mTestInfo);
            Assert.fail("Expected TargetSetupError");
        } catch (TargetSetupError e) {
            // expected.
        } finally {
            FileUtil.recursiveDelete(radioImage);
        }
    }

    @Test
    public void testFailsOnMissingRadioImage() throws Exception {
        MixRadioTargetPreparer mk = new MixRadioTargetPreparer();
        File deviceImage = FileUtil.createTempFile("device-img-12345", "zip");
        mBuildInfo.setFile("device-img", deviceImage, "0");

        String radioImageName = "radio-img-9432383";
        File srcFile = FileUtil.createTempFile(radioImageName + "_", ".gz");
        FileUtil.writeToFile(radioImageName, srcFile);
        mBuildInfo.setFile("baseband", srcFile, "0");

        when(mBuildInfo.getFile(srcFile.getName())).thenReturn(null);
        try {
            mContext.addDeviceBuildInfo("device", new DeviceBuildInfo());
            mk.setUp(mTestInfo);
            Assert.fail("Expected TargetSetupError");
        } catch (TargetSetupError e) {
            // expected.
        } finally {
            FileUtil.recursiveDelete(deviceImage);
            FileUtil.recursiveDelete(srcFile);
        }
    }

    private static byte[] getRandomBytes(int size) {
        byte[] nonce = new byte[size];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    private static byte[] hashFile(File file) throws NoSuchAlgorithmException, IOException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.reset();
        digest.update(Files.readAllBytes(file.toPath()));
        return digest.digest();
    }

    private static int getRandomInt() {
        return ThreadLocalRandom.current().nextInt(RANDOM_BYTES_MIN, RANDOM_BYTES_MAX + 1);
    }

    static class MixRadioPreparerResource implements AutoCloseable {
        DeviceBuildInfo deviceBuildInfo;
        String basebandBuildInfo;

        File deviceImage;
        File radioImage;
        File newRadioImage;

        File deviceImageHardLink;
        File radioImageHardLink;
        File newRadioImageHardLink;

        byte[] deviceImageDigest;
        byte[] radioImageDigest;
        byte[] newRadioImageDigest;

        MixRadioPreparerResource(
                IInvocationContext context,
                String deviceBuildId,
                String basebandBuildId,
                String newBasebandBuildId)
                throws Exception {
            deviceBuildInfo = new DeviceBuildInfo();
            context.addDeviceBuildInfo("device", deviceBuildInfo);
            context.addAllocatedDevice("device", Mockito.mock(ITestDevice.class));

            deviceImage = FileUtil.createTempFile("device-img", ".zip");
            radioImage = FileUtil.createTempFile("radio", ".img");
            newRadioImage = FileUtil.createTempFile("radio", ".img");

            deviceImageHardLink = FileUtil.createTempFile("device-img-hard-link", ".zip");
            radioImageHardLink = FileUtil.createTempFile("baseband-hard-link", ".img");
            newRadioImageHardLink = FileUtil.createTempFile("baseband-hard-link", ".img");

            deviceImageHardLink.delete();
            radioImageHardLink.delete();
            newRadioImageHardLink.delete();

            FileUtil.hardlinkFile(deviceImage, deviceImageHardLink);
            FileUtil.hardlinkFile(radioImage, radioImageHardLink);
            FileUtil.hardlinkFile(newRadioImage, newRadioImageHardLink);

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(deviceImage))) {
                zos.putNextEntry(new ZipEntry("android-info.txt"));
                zos.write("\n#unused-line\nrequire version-baseband=oldver\nOK\n".getBytes());
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("nonce.img"));
                zos.write(getRandomBytes(getRandomInt()));
                zos.closeEntry();
            }

            Files.write(radioImage.toPath(), getRandomBytes(getRandomInt()));
            Files.write(newRadioImage.toPath(), getRandomBytes(getRandomInt()));

            if (deviceBuildId != null) {
                deviceBuildInfo.setDeviceImageFile(deviceImageHardLink, deviceBuildId);
            }
            if (basebandBuildId != null) {
                deviceBuildInfo.setBasebandImage(radioImageHardLink, basebandBuildId);
            }
            if (newBasebandBuildId != null) {
                deviceBuildInfo.setBasebandImage(newRadioImageHardLink, newBasebandBuildId);
            }

            deviceImageDigest = hashFile(deviceImage);
            radioImageDigest = hashFile(radioImage);
            newRadioImageDigest = hashFile(newRadioImage);
        }

        @Override
        public void close() throws IOException {
            try {
                Assert.assertArrayEquals(deviceImageDigest, hashFile(deviceImage));
                Assert.assertArrayEquals(radioImageDigest, hashFile(radioImage));
                Assert.assertArrayEquals(newRadioImageDigest, hashFile(newRadioImage));
            } catch (NoSuchAlgorithmException e) {
                Assert.fail("Hash algorithm is not supported: " + e.toString());
            }

            FileUtil.deleteFile(deviceImage);
            FileUtil.deleteFile(radioImage);
            FileUtil.deleteFile(newRadioImage);
            FileUtil.deleteFile(deviceImageHardLink);
            FileUtil.deleteFile(radioImageHardLink);
            FileUtil.deleteFile(newRadioImageHardLink);
        }

        @Override
        public String toString() {
            return String.format(
                    "buildinfo(%s,%s),images(%s,%s,%s)->(%s,%s,%s)",
                    deviceBuildInfo,
                    basebandBuildInfo,
                    deviceImage,
                    radioImage,
                    newRadioImage,
                    deviceImageHardLink,
                    radioImageHardLink,
                    newRadioImageHardLink);
        }
    }

    @Test
    public void testSuccessfulMixRadio() throws Exception {
        MixRadioTargetPreparer mk = new MixRadioTargetPreparer();
        OptionSetter setter = new OptionSetter(mk);
        setter.setOptionValue("radio-file-key", "123456");
        File testsDir = FileUtil.createTempDir("testsdir");
        File radioTool = FileUtil.createTempFile("radio_img", null, testsDir);
        radioTool.renameTo(new File(testsDir, "radio_img"));

        String radioImageName = "radio-img-9432383";
        File srcFile = FileUtil.createTempFile(radioImageName + "_", ".gz");
        FileUtil.writeToFile(radioImageName, srcFile);
        mBuildInfo.setFile("baseband", srcFile, "0");

        try (MixRadioPreparerResource res =
                new MixRadioPreparerResource(mContext, "666123", "zzz-1.0-YU9P9034__#", "P33145")) {
            ITestDevice mockDevice = Mockito.mock(ITestDevice.class);
            when(mockDevice.getSerialNumber()).thenReturn("serial");
            mContext.addAllocatedDevice("device", mockDevice);
            mk.setUp(mTestInfo);

            try (FileSystem zip =
                    FileSystems.newFileSystem(
                            res.deviceBuildInfo.getDeviceImageFile().toPath(),
                            (ClassLoader) null)) {
                Path aInfoPath = zip.getPath("android-info.txt");
                Path noncePath = zip.getPath("nonce.img");
                String aInfo = new String(Files.readAllBytes(aInfoPath), StandardCharsets.UTF_8);

                // Check android-info.txt is patched correctly.
                Assert.assertTrue(
                        "Processed android-info.txt:\n" + aInfo,
                        Arrays.stream(aInfo.split("\\r?\\n"))
                                .allMatch(line -> !line.startsWith("require version-baseband=")));
                Assert.assertTrue(
                        "Processed android-info.txt:\n" + aInfo,
                        Arrays.stream(aInfo.split("\\r?\\n"))
                                .anyMatch(line -> line.startsWith("#unused-line")));

                // Check both files are regular files.
                Assert.assertTrue(
                        zip.provider()
                                .getFileAttributeView(aInfoPath, BasicFileAttributeView.class)
                                .readAttributes()
                                .isRegularFile());
                Assert.assertTrue(
                        zip.provider()
                                .getFileAttributeView(noncePath, BasicFileAttributeView.class)
                                .readAttributes()
                                .isRegularFile());
            }

            Assert.assertEquals(
                    "radio.img in device is not updated",
                    new String(
                            Files.readAllBytes(res.deviceBuildInfo.getBasebandImageFile().toPath()),
                            StandardCharsets.UTF_8),
                    new String(
                            Files.readAllBytes(res.newRadioImage.toPath()),
                            StandardCharsets.UTF_8));

            Assert.assertEquals(
                    "baseband version is not updated",
                    "zzz-1.0-P33145",
                    res.deviceBuildInfo.getBasebandVersion());
        } catch (TargetSetupError e) {
            // expected.
        } finally {
            FileUtil.recursiveDelete(radioTool);
            FileUtil.recursiveDelete(testsDir);
            FileUtil.recursiveDelete(srcFile);
        }
    }
}
