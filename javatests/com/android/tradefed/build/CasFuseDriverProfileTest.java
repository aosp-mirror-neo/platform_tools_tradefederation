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

package com.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.util.CasUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CasFuseDriverProfile}. */
@RunWith(JUnit4.class)
public class CasFuseDriverProfileTest {

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    private CasFuseDriverProfile mProfile;
    private File mDriverFile;

    @Before
    public void setUp() throws IOException {
        mDriverFile = tempFolder.newFile("casviewer-driver");
        mProfile = new CasFuseDriverProfile(mDriverFile.toPath());
    }

    // validateSourceDataPath is a wrapper around CasUtil#validateChunkedDir, so we only need to
    // test a few basic cases here.

    @Test
    public void validSourceDataPath_indexFileInChunksDir_success() throws IOException {
        File sourceDir = tempFolder.newFolder("valid_source");
        tempFolder.newFolder("valid_source/" + CasUtil.CHUNKS_DIR_NAME);
        tempFolder.newFile(
                "valid_source/" + CasUtil.CHUNKS_DIR_NAME + "/" + CasUtil.CHUNKS_INDEX_FILENAME);

        mProfile.validateSourceDataPath(sourceDir.toPath());

        // No exception thrown
    }

    @Test
    public void validSourceDataPath_missingChunksDir() throws IOException {
        File sourceDir = tempFolder.newFolder("source_missing_chunks_dir");
        tempFolder.newFile("source_missing_chunks_dir/" + CasUtil.CHUNKS_INDEX_FILENAME);

        assertThrows(
                "Should throw IOException when the source directory is missing the chunks"
                        + " directory.",
                IOException.class,
                () -> mProfile.validateSourceDataPath(sourceDir.toPath()));
    }

    // isMountPointReady is a wrapper around CasUtil#isUncompressedDeviceImage, so we only need to
    // test a few basic cases here.

    @Test
    public void isMountPointReady_success() throws IOException {
        File mountPoint = tempFolder.newFolder("ready_mount_point");
        tempFolder.newFile("ready_mount_point/" + CasUtil.BOOT_IMAGE_FILENAME);

        assertTrue(
                "Should return true for a mount point containing boot.img.",
                mProfile.isMountPointReady(mountPoint.toPath()));
    }

    @Test
    public void isMountPointReady_missingBootImage() throws IOException {
        File mountPoint = tempFolder.newFolder("not_ready_mount_point");

        assertFalse(
                "Should return false if the mount point does not contain boot.img.",
                mProfile.isMountPointReady(mountPoint.toPath()));
    }

    @Test
    public void buildMountCommand_noLogDir_success() {
        Path sourceDir = new File("/fake/source/dir").toPath();
        Path mountPoint = new File("/fake/mount/point").toPath();
        List<String> expectedCommand =
                Arrays.asList(
                        mDriverFile.getAbsolutePath(),
                        "--chunks",
                        sourceDir.resolve("_chunks").toAbsolutePath().toString(),
                        "--mount",
                        mountPoint.toAbsolutePath().toString());

        List<String> actualCommand = mProfile.buildMountCommand(sourceDir, mountPoint);

        assertNotNull("Command list should not be null.", actualCommand);
        assertEquals(
                "The generated command should match the expected format.",
                expectedCommand,
                actualCommand);
    }

    @Test
    public void buildMountCommand_withLogDir_success() {
        Path sourceDir = new File("/fake/source/dir").toPath();
        Path mountPoint = new File("/fake/mount/point").toPath();
        Path logDir = new File("/fake/log/dir").toPath();
        List<String> expectedCommand =
                Arrays.asList(
                        mDriverFile.getAbsolutePath(),
                        "--chunks",
                        sourceDir.resolve("_chunks").toAbsolutePath().toString(),
                        "--mount",
                        mountPoint.toAbsolutePath().toString(),
                        "--log-dir",
                        logDir.toAbsolutePath().toString());

        List<String> actualCommand = mProfile.buildMountCommand(sourceDir, mountPoint, logDir);

        assertNotNull("Command list should not be null.", actualCommand);
        assertEquals(
                "The generated command should match the expected format.",
                expectedCommand,
                actualCommand);
    }

    @Test
    public void getDriverName() {
        assertEquals(
                "The driver name should be 'casviewer'.", "casviewer", mProfile.getDriverName());
    }

    @Test
    public void getMountTimeoutInMs() {
        assertEquals(
                "The default mount timeout should be 500 milliseconds.",
                500,
                mProfile.getMountTimeoutInMs());
    }

    @Test
    public void getMountTimeoutInMs_customTimeout() {
        mProfile = new CasFuseDriverProfile(mDriverFile.toPath(), 1000);
        assertEquals(
                "The custom mount timeout should be 1000 milliseconds.",
                1000,
                mProfile.getMountTimeoutInMs());
    }
}
