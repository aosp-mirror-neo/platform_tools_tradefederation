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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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

    @Test
    public void testValidSourceDataPath_success() throws IOException {
        File sourceDir = tempFolder.newFolder("valid_source");
        tempFolder.newFile("valid_source/_chunks_index.json");

        mProfile.validateSourceDataPath(sourceDir.toPath());

        // No exception thrown
    }

    @Test
    public void testValidSourceDataPath_directoryDoesNotExist() {
        File nonExistentDir = new File(tempFolder.getRoot(), "non_existent");

        assertThrows(
                "Should throw IOException when the source directory does not exist.",
                IOException.class,
                () -> mProfile.validateSourceDataPath(nonExistentDir.toPath()));
    }

    @Test
    public void testValidSourceDataPath_isAFileNotDirectory() throws IOException {
        File sourceFile = tempFolder.newFile("source_as_file.txt");

        assertThrows(
                "Should throw IOException when the source path is a file, not a"
                    + " directory.",
                IOException.class,
                () -> mProfile.validateSourceDataPath(sourceFile.toPath()));
    }

    @Test
    public void testValidSourceDataPath_directoryMissingIndexFile() throws IOException {
        File sourceDir = tempFolder.newFolder("source_missing_index");

        assertThrows(
                "Should throw IOException when the source directory is missing the chunks"
                    + " index file.",
                IOException.class,
                () -> mProfile.validateSourceDataPath(sourceDir.toPath()));
    }

    @Test
    public void testIsMountPointReady_success() throws IOException {
        File mountPoint = tempFolder.newFolder("ready_mount_point");
        tempFolder.newFile("ready_mount_point/boot.img");

        assertTrue(
                "Should return true for a mount point containing boot.img.",
                mProfile.isMountPointReady(mountPoint.toPath()));
    }

    @Test
    public void testIsMountPointReady_missingBootImage() throws IOException {
        File mountPoint = tempFolder.newFolder("not_ready_mount_point");

        assertFalse(
                "Should return false if the mount point does not contain boot.img.",
                mProfile.isMountPointReady(mountPoint.toPath()));
    }

    @Test
    public void testIsMountPointReady_mountPointDoesNotExist() {
        File nonExistentDir = new File(tempFolder.getRoot(), "non_existent_mount_point");

        assertFalse(
                "Should return false if the mount point directory itself does not exist.",
                mProfile.isMountPointReady(nonExistentDir.toPath()));
    }

    @Test
    public void testBuildMountCommand() {
        Path sourceDir = new File("/fake/source/dir").toPath();
        Path mountPoint = new File("/fake/mount/point").toPath();
        List<String> expectedCommand =
                Arrays.asList(
                        mDriverFile.getAbsolutePath(),
                        "--chunks",
                        sourceDir.toAbsolutePath().toString(),
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
    public void testGetDriverName() {
        assertEquals(
                "The driver name should be 'casviewer'.", "casviewer", mProfile.getDriverName());
    }

    @Test
    public void testGetMountTimeoutInMs() {
        assertEquals(
                "The default mount timeout should be 500 milliseconds.",
                500,
                mProfile.getMountTimeoutInMs());
    }

    @Test
    public void testGetMountTimeoutInMs_customTimeout() {
        mProfile = new CasFuseDriverProfile(mDriverFile.toPath(), 1000);
        assertEquals(
                "The custom mount timeout should be 1000 milliseconds.",
                1000,
                mProfile.getMountTimeoutInMs());
    }
}
