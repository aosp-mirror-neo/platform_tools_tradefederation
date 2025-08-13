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
package com.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo.BuildInfoProperties;
import com.android.tradefed.build.proto.BuildInformation;
import com.android.tradefed.util.CasUtil;
import com.android.tradefed.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/** Unit tests for {@link DeviceBuildInfo}. */
@RunWith(MockitoJUnitRunner.class)
public class DeviceBuildInfoTest {

    @Rule public TemporaryFolder mTempFolder = new TemporaryFolder();

    @Mock private FuseMountManager mMockFuseManager;
    @Mock private CasConverterFactory mMockCasConverterFactory;

    private static final String VERSION = "1";
    private DeviceBuildInfo mBuildInfo;
    private File mImageFile;
    private File mTestsDir;
    private File mHostLinkedDir;

    @Before
    public void setUp() throws Exception {
        mBuildInfo = new DeviceBuildInfo("2", "target");
        mImageFile = mTempFolder.newFile("image.zip");
        FileUtil.writeToFile("filedata", mImageFile);
        mBuildInfo.setBasebandImage(mImageFile, VERSION);
        mTestsDir = mTempFolder.newFolder("device-info-tests-dir");
        mBuildInfo.setTestsDir(mTestsDir, VERSION);
        mHostLinkedDir = mTempFolder.newFolder("host-linked-dir");
    }

    @After
    public void tearDown() throws Exception {
        mBuildInfo.cleanUp();
    }

    /** Test method for {@link DeviceBuildInfo#clone()}. */
    @Test
    public void testClone() throws Exception {
        DeviceBuildInfo copy = (DeviceBuildInfo) mBuildInfo.clone();
        try {
            // ensure a copy of mImageFile was created
            assertEquals(VERSION, copy.getBasebandVersion());
            assertTrue(
                    !mImageFile
                            .getAbsolutePath()
                            .equals(copy.getBasebandImageFile().getAbsolutePath()));
            assertTrue(FileUtil.compareFileContents(mImageFile, copy.getBasebandImageFile()));
        } finally {
            if (copy.getBasebandImageFile() != null) {
                copy.getBasebandImageFile().delete();
            }
            copy.cleanUp();
        }
    }

    /** Test method for {@link DeviceBuildInfo#cleanUp()}. */
    @Test
    public void testCleanUp() {
        assertTrue(mBuildInfo.getBasebandImageFile().exists());
        mBuildInfo.cleanUp();
        assertNull(mBuildInfo.getBasebandImageFile());
        assertFalse(mImageFile.exists());
    }

    /**
     * When the {@link BuildInfoProperties} for no sharding copy is set, the tests dir will not be
     * copied.
     */
    @Test
    public void testCloneWithProperties() throws Exception {
        mBuildInfo.setFile(BuildInfoFileKey.HOST_LINKED_DIR, mHostLinkedDir, "v1");
        DeviceBuildInfo copy = (DeviceBuildInfo) mBuildInfo.clone();
        try {
            assertNotEquals(copy.getTestsDir(), mBuildInfo.getTestsDir());
            assertNotEquals(copy.getBasebandImageFile(), mBuildInfo.getBasebandImageFile());
        } finally {
            copy.cleanUp();
        }

        mBuildInfo.setProperties(BuildInfoProperties.DO_NOT_COPY_ON_SHARDING);
        copy = (DeviceBuildInfo) mBuildInfo.clone();
        try {
            assertEquals(mBuildInfo.getTestsDir(), copy.getTestsDir());
            assertEquals(mHostLinkedDir, copy.getFile(BuildInfoFileKey.HOST_LINKED_DIR));
            // Only the tests dir is not copied.
            assertNotEquals(mBuildInfo.getBasebandImageFile(), copy.getBasebandImageFile());
        } finally {
            copy.cleanUp();
        }
    }

    /**
     * Test that {@link BuildInfoProperties#DO_NOT_COPY_IMAGE_FILE} properly skip the copying when
     * copying.
     */
    @Test
    public void testProperty_skipCopy() {
        mBuildInfo.setProperties(BuildInfoProperties.DO_NOT_COPY_IMAGE_FILE);
        mBuildInfo.setDeviceImageFile(mHostLinkedDir, "1");
        DeviceBuildInfo copy = (DeviceBuildInfo) mBuildInfo.clone();
        try {
            assertNull(copy.getDeviceImageFile());
        } finally {
            copy.cleanUp();
        }
    }

    /**
     * Test that the build info can be described in its proto format with its class properly
     * populated.
     */
    @Test
    public void testProtoSerialization() throws Exception {
        BuildInformation.BuildInfo proto = mBuildInfo.toProto();
        assertEquals("2", proto.getBuildId());
        assertEquals(DeviceBuildInfo.class.getCanonicalName(), proto.getBuildInfoClass());
    }

    /** Test that {@link IDeviceBuildInfo#setDeviceBuildFlavor(String)} behaves as expected. */
    @Test
    public void testDeviceBuildFlavor() {
        assertNull(mBuildInfo.getDeviceBuildFlavor());
        mBuildInfo.setBuildFlavor("build-flavor");
        assertEquals("build-flavor", mBuildInfo.getDeviceBuildFlavor());
        assertEquals("build-flavor", mBuildInfo.getBuildFlavor());
        mBuildInfo.setDeviceBuildFlavor("device-build-flavor");
        assertEquals("device-build-flavor", mBuildInfo.getDeviceBuildFlavor());
        assertEquals("build-flavor", mBuildInfo.getBuildFlavor());
    }

    // FuseMountManager specific tests

    @Test
    public void getDeviceImageFile_mountSuccessful() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);
        File sourceDir = mTempFolder.newFolder("source-dir");
        mTempFolder.newFolder("source-dir/" + CasUtil.CHUNKS_DIR_NAME);
        mTempFolder.newFile(
                "source-dir/" + CasUtil.CHUNKS_DIR_NAME + "/" + CasUtil.CHUNKS_INDEX_FILENAME);
        buildInfoSpy.setDeviceImageFile(sourceDir, VERSION);

        File mountPointDir = mTempFolder.newFolder("mount-point");
        mTempFolder.newFile("mount-point/" + CasUtil.BOOT_IMAGE_FILENAME);

        doReturn(Optional.of(mMockFuseManager)).when(buildInfoSpy).getFuseMountManager();
        doReturn(Optional.of(mMockCasConverterFactory)).when(buildInfoSpy).getCasConverterFactory();
        when(mMockFuseManager.mount(sourceDir.toPath())).thenReturn(mountPointDir.toPath());

        File result = buildInfoSpy.getDeviceImageFile();

        verify(mMockFuseManager).mount(sourceDir.toPath());
        verify(mMockCasConverterFactory, never()).convert(any(Path.class), any(Path.class));
    }

    @Test
    public void getDeviceImageFile_mountFails_covertToUncompressedImage() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);
        File sourceDir = mTempFolder.newFolder("source-dir-fail");
        mTempFolder.newFolder("source-dir-fail/" + CasUtil.CHUNKS_DIR_NAME);
        mTempFolder.newFile(
                "source-dir-fail/" + CasUtil.CHUNKS_DIR_NAME + "/" + CasUtil.CHUNKS_INDEX_FILENAME);
        buildInfoSpy.setDeviceImageFile(sourceDir, VERSION);

        doReturn(Optional.of(mMockFuseManager)).when(buildInfoSpy).getFuseMountManager();
        doReturn(Optional.of(mMockCasConverterFactory)).when(buildInfoSpy).getCasConverterFactory();
        doThrow(new IOException("FUSE mount failed!"))
                .when(mMockFuseManager)
                .mount(sourceDir.toPath());

        File result = buildInfoSpy.getDeviceImageFile();

        verify(mMockCasConverterFactory).convert(sourceDir.toPath(), null);
        assertEquals(
                "Should return the original source directory (converted) on mount failure.",
                sourceDir,
                result);
    }

    @Test
    public void getDeviceImageFile_sourceNotValid_returnsOriginalDir() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);
        File sourceDir = mTempFolder.newFolder("source-dir-invalid");
        buildInfoSpy.setDeviceImageFile(sourceDir, VERSION);

        doReturn(Optional.of(mMockFuseManager)).when(buildInfoSpy).getFuseMountManager();

        File result = buildInfoSpy.getDeviceImageFile();

        assertEquals(
                "Should return the original source directory if it's not a valid FUSE source.",
                sourceDir,
                result);
        // Verify that mount() was never even attempted.
        verify(mMockFuseManager, never()).mount(any(Path.class));
    }

    @Test
    public void getDeviceImageFile_noFuseManager_returnsOriginalDir() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);
        File sourceDir = mTempFolder.newFolder("source-dir");
        buildInfoSpy.setDeviceImageFile(sourceDir, VERSION);

        // Simulate the factory failing to create a manager
        doReturn(Optional.empty()).when(buildInfoSpy).getFuseMountManager();

        File result = buildInfoSpy.getDeviceImageFile();

        assertEquals(
                "Should return the original source directory if the FUSE manager is not available.",
                sourceDir,
                result);
    }

    @Test
    public void getDeviceImageFile_noImageSet_returnsNull() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);

        // No device image is set on the build info object.
        File result = buildInfoSpy.getDeviceImageFile();

        assertNull("Should return null if no device image file was ever set.", result);
    }

    @Test
    public void cleanUp_unmountsAllIfManagerExists() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);
        File sourceDir = mTempFolder.newFolder("source-dir");
        buildInfoSpy.setDeviceImageFile(sourceDir, VERSION);

        doReturn(Optional.of(mMockFuseManager)).when(buildInfoSpy).getFuseMountManager();

        buildInfoSpy.cleanUp();

        // Verify that unmountAll was called.
        verify(mMockFuseManager).unmountAll();
    }

    @Test
    public void cleanUp_doesNothingIfNoManager() {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);

        doReturn(Optional.empty()).when(buildInfoSpy).getFuseMountManager();

        buildInfoSpy.cleanUp();

        // Verify that unmountAll was never called.
        verify(mMockFuseManager, never()).unmountAll();
    }

    @Test
    public void cleanUp_deviceImageNotUnmountedIfInDoNotCleanList() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);
        File sourceDir = mTempFolder.newFolder("source-dir");
        buildInfoSpy.setDeviceImageFile(sourceDir, VERSION);

        doReturn(Optional.of(mMockFuseManager)).when(buildInfoSpy).getFuseMountManager();

        List<File> doNotClean = Arrays.asList(sourceDir);
        buildInfoSpy.cleanUp(doNotClean);

        // Verify that the device image was not unmounted.
        verify(mMockFuseManager, never()).unmount(sourceDir.toPath());
        verify(mMockFuseManager, never()).unmountAll();
    }

    @Test
    public void cleanUp_deviceImageUnmountedIfNotInDoNotCleanList() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);
        File sourceDir = mTempFolder.newFolder("source-dir");
        buildInfoSpy.setDeviceImageFile(sourceDir, VERSION);

        doReturn(Optional.of(mMockFuseManager)).when(buildInfoSpy).getFuseMountManager();

        List<File> doNotClean = Arrays.asList(new File("some-other-file"));
        buildInfoSpy.cleanUp(doNotClean);

        // Verify that unmount was never called on the device image.
        verify(mMockFuseManager).unmount(sourceDir.toPath());
    }

    @Test
    public void cleanUp_unmountAllIsCalledForEmptyDoNotCleanList() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);
        File sourceDir = mTempFolder.newFolder("source-dir");
        buildInfoSpy.setDeviceImageFile(sourceDir, VERSION);

        doReturn(Optional.of(mMockFuseManager)).when(buildInfoSpy).getFuseMountManager();

        List<File> doNotClean = Arrays.asList();
        buildInfoSpy.cleanUp(doNotClean);

        // Verify that unmount was called on the device image.
        verify(mMockFuseManager).unmount(sourceDir.toPath());
    }

    @Test
    public void cleanUp_unmountAllIsCalledForNullDoNotCleanList() throws Exception {
        DeviceBuildInfo buildInfoSpy = spy(mBuildInfo);
        File sourceDir = mTempFolder.newFolder("source-dir");
        buildInfoSpy.setDeviceImageFile(sourceDir, VERSION);

        doReturn(Optional.of(mMockFuseManager)).when(buildInfoSpy).getFuseMountManager();

        buildInfoSpy.cleanUp(null);

        // Verify that unmountAll was called.
        verify(mMockFuseManager).unmountAll();
    }
}
