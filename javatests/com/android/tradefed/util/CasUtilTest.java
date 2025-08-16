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

package com.android.tradefed.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CasFuseDriverProfile}. */
@RunWith(JUnit4.class)
public class CasUtilTest {

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void validateChunkedDir_indexFileInChunksDir_success() throws IOException {
        File chunkedDir = tempFolder.newFolder("valid_chunked_dir");
        tempFolder.newFolder("valid_chunked_dir/_chunks");
        tempFolder.newFile("valid_chunked_dir/_chunks/_chunks_index.json");

        assertTrue(
                "Should return true for a valid chunked directory.",
                CasUtil.isChunkedDir(chunkedDir.toPath()));
        CasUtil.validateChunkedDir(chunkedDir.toPath());

        // No exception thrown
    }

    @Test
    public void validateChunkedDir_indexFileAsSiblingOfChunksDir_success() throws IOException {
        File chunkedDir = tempFolder.newFolder("valid_chunked_dir");
        tempFolder.newFolder("valid_chunked_dir/_chunks");
        tempFolder.newFile("valid_chunked_dir/_chunks_index.json");

        assertTrue(
                "Should return true for a valid chunked directory.",
                CasUtil.isChunkedDir(chunkedDir.toPath()));
        CasUtil.validateChunkedDir(chunkedDir.toPath());

        // No exception thrown
    }

    @Test
    public void validateChunkedDir_doesNotExist() {
        File nonExistentDir = new File(tempFolder.getRoot(), "non_existent");

        assertFalse(
                "Should return false for a non-existent chunked directory.",
                CasUtil.isChunkedDir(nonExistentDir.toPath()));
        assertThrows(
                "Should throw IOException when the chunked directory does not exist.",
                IOException.class,
                () -> CasUtil.validateChunkedDir(nonExistentDir.toPath()));
    }

    @Test
    public void validateChunkedDir_isAFileNotDirectory() throws IOException {
        File sourceFile = tempFolder.newFile("argument_as_file.txt");

        assertFalse(
                "Should return false for a file, not a directory.",
                CasUtil.isChunkedDir(sourceFile.toPath()));
        assertThrows(
                "Should throw IOException when the argument is a file, not a" + " directory.",
                IOException.class,
                () -> CasUtil.validateChunkedDir(sourceFile.toPath()));
    }

    @Test
    public void validateChunkedDir_missingChunksDir() throws IOException {
        File chunkedDir = tempFolder.newFolder("chunked_dir_missing_chunks_subdir");
        tempFolder.newFile("chunked_dir_missing_chunks_subdir/_chunks_index.json");

        assertFalse(
                "Should return false for a chunked directory missing the chunks sub-directory.",
                CasUtil.isChunkedDir(chunkedDir.toPath()));
        assertThrows(
                "Should throw IOException when the chunked directory is missing the chunks"
                        + " sub-directory.",
                IOException.class,
                () -> CasUtil.validateChunkedDir(chunkedDir.toPath()));
    }

    @Test
    public void validateChunkedDir_missingIndexFile() throws IOException {
        File chunkedDir = tempFolder.newFolder("chunked_dir_missing_index");
        tempFolder.newFolder("chunked_dir_missing_index/_chunks");

        assertFalse(
                "Should return false for a chunked directory missing the chunks index file.",
                CasUtil.isChunkedDir(chunkedDir.toPath()));
        assertThrows(
                "Should throw IOException when the chunked directory is missing the chunks"
                        + " index file.",
                IOException.class,
                () -> CasUtil.validateChunkedDir(chunkedDir.toPath()));
    }

    @Test
    public void isUncompressedDeviceImage_success() throws IOException {
        File mountPoint = tempFolder.newFolder("uncompressed_device_image");
        tempFolder.newFile("uncompressed_device_image/boot.img");

        assertTrue(
                "Should return true for an uncompressed device image containing boot.img.",
                CasUtil.isUncompressedDeviceImage(mountPoint.toPath()));
    }

    @Test
    public void isUncompressedDeviceImage_missingBootImage() throws IOException {
        File mountPoint = tempFolder.newFolder("not_uncompressed_device_image");

        assertFalse(
                "Should return false if the uncompressed device image does not contain boot.img.",
                CasUtil.isUncompressedDeviceImage(mountPoint.toPath()));
    }

    @Test
    public void isUncompressedDeviceImage_notExist() {
        File nonExistentDir = new File(tempFolder.getRoot(), "non_existent_directory");

        assertFalse(
                "Should return false if the uncompressed device image directory itself does not"
                        + " exist.",
                CasUtil.isUncompressedDeviceImage(nonExistentDir.toPath()));
    }

    @Test
    public void createUniqueCasLogDir_success() throws IOException {
        Path logRoot = tempFolder.newFolder("cas_log_root").toPath();
        Path logDir1 = CasUtil.createUniqueCasLogDir(logRoot);
        Path logDir2 = CasUtil.createUniqueCasLogDir(logRoot);
        assertThat(Files.exists(logDir1)).isTrue();
        assertThat(Files.exists(logDir2)).isTrue();
        assertThat(logDir1).isNotEqualTo(logDir2);
    }

    @Test
    public void getCasLogFromDir_success() throws IOException {
        String header =
                "Log file created at: 2025/07/22 09:20:29\n"
                    + "Running on machine: hostname\n"
                    + "Binary: Built on Jul 22 2025 09:14:44 -0700 (1753200884)\n"
                    + "Binary: Built at"
                    + " username@hostname.c.googlers.com:/google/src/cloud/username/clientname\n"
                    + "Binary: Built with gc go1.25-20250720-RC00 cl/785045014 +2363897932"
                    + " X:fieldtrack,boringcrypto for linux/amd64\n"
                    + "Previous log: <none>\n"
                    + "Log line format: [IWEF]mmdd hh:mm:ss.uuuuuu threadid file:line] msg\n";
        String expectedlog =
                "I0722 09:20:29.592478      1 main.go:79] Log directory: /tmp/logs/inv_2\n"
                    + "I0722 09:20:29.592910      1 main.go:45] Use default index file:"
                    + " /usr/local/google/home/username/cas/download/d29199/_chunks/_chunks_index.json\n"
                    + "I0722 09:20:29.683595      1 main.go:104] FastCDC-FUSE mounted at"
                    + " /usr/local/google/home/username/mnt\n"
                    + "I0722 09:20:29.683654      1 main.go:105] To unmount, press Ctrl-C or run"
                    + " 'fusermount -u <mount_point>'\n"
                    + "I0722 09:21:59.925268      51 main.go:113] Received signal, unmounting...\n"
                    + "I0722 09:21:59.929269      1 main.go:128] Successfully unmounted"
                    + " FastCDC-FUSE at: /usr/local/google/home/username/mnt";
        Path logDir = tempFolder.newFolder("cas_log_dir").toPath();
        Files.writeString(logDir.resolve("cas_client.INFO"), header + expectedlog);
        String log = CasUtil.getCasLogFromDir(logDir, "cas_client");
        assertThat(log).isEqualTo(expectedlog);
        assertThat(Files.exists(logDir)).isFalse();
    }

    @Test
    public void getCasLogFromDir_noClientName_success() throws IOException {
        String header =
                "Log file created at: 2025/07/22 09:20:29\n"
                    + "Running on machine: hostname\n"
                    + "Binary: Built on Jul 22 2025 09:14:44 -0700 (1753200884)\n"
                    + "Binary: Built at"
                    + " username@hostname.c.googlers.com:/google/src/cloud/username/clientname\n"
                    + "Binary: Built with gc go1.25-20250720-RC00 cl/785045014 +2363897932"
                    + " X:fieldtrack,boringcrypto for linux/amd64\n"
                    + "Previous log: <none>\n"
                    + "Log line format: [IWEF]mmdd hh:mm:ss.uuuuuu threadid file:line] msg\n";
        String expectedlog =
                "I0722 09:20:29.592478      1 main.go:79] Log directory: /tmp/logs/inv_2\n"
                    + "I0722 09:20:29.592910      1 main.go:45] Use default index file:"
                    + " /usr/local/google/home/username/cas/download/d29199/_chunks/_chunks_index.json\n"
                    + "I0722 09:20:29.683595      1 main.go:104] FastCDC-FUSE mounted at"
                    + " /usr/local/google/home/username/mnt\n"
                    + "I0722 09:20:29.683654      1 main.go:105] To unmount, press Ctrl-C or run"
                    + " 'fusermount -u <mount_point>'\n"
                    + "I0722 09:21:59.925268      51 main.go:113] Received signal, unmounting...\n"
                    + "I0722 09:21:59.929269      1 main.go:128] Successfully unmounted"
                    + " FastCDC-FUSE at: /usr/local/google/home/username/mnt";
        Path logDir = tempFolder.newFolder("cas_log_dir").toPath();
        Files.writeString(logDir.resolve("cas_client.INFO"), header + expectedlog);
        String log = CasUtil.getCasLogFromDir(logDir);
        assertThat(log).isEqualTo(expectedlog);
        assertThat(Files.exists(logDir)).isFalse();
    }

}
