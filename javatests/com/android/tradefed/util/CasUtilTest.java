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
import java.util.HashSet;
import java.util.Set;
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
        tempFolder.newFile("uncompressed_device_image/" + CasUtil.SYSTEM_IMAGE_FILENAME);
        tempFolder.newFile("uncompressed_device_image/" + CasUtil.ANDROID_INFO_FILENAME);

        assertTrue(
                "Should return true for an uncompressed device image containing system.img and"
                        + " android-info.txt.",
                CasUtil.isUncompressedDeviceImage(mountPoint.toPath()));
    }

    @Test
    public void isUncompressedDeviceImage_withSuperImage_success() throws IOException {
        File mountPoint = tempFolder.newFolder("uncompressed_device_image_with_super");
        tempFolder.newFile("uncompressed_device_image_with_super/" + CasUtil.SUPER_IMAGE_FILENAME);
        tempFolder.newFile(
                "uncompressed_device_image_with_super/" + CasUtil.ANDROID_INFO_FILENAME);

        assertTrue(
                "Should return true for an uncompressed device image containing super.img and"
                        + " android-info.txt.",
                CasUtil.isUncompressedDeviceImage(mountPoint.toPath()));
    }

    @Test
    public void isUncompressedDeviceImage_missingSystemAndSuperImage() throws IOException {
        File mountPoint = tempFolder.newFolder("not_uncompressed_device_image");
        tempFolder.newFile("not_uncompressed_device_image/" + CasUtil.ANDROID_INFO_FILENAME);

        assertFalse(
                "Should return false if the uncompressed device image does not contain system.img"
                        + " or super.img.",
                CasUtil.isUncompressedDeviceImage(mountPoint.toPath()));
    }

    @Test
    public void isUncompressedDeviceImage_missingAndroidInfo() throws IOException {
        File mountPoint = tempFolder.newFolder("not_uncompressed_device_image_2");
        tempFolder.newFile("not_uncompressed_device_image_2/" + CasUtil.SYSTEM_IMAGE_FILENAME);
        tempFolder.newFile("not_uncompressed_device_image_2/" + CasUtil.SUPER_IMAGE_FILENAME);

        assertFalse(
                "Should return false if the uncompressed device image does not contain"
                        + " android-info.txt.",
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

    @Test
    public void checkCasClientFlags_success() throws IOException {
        String usageOutput =
                "Usage of /usr/local/google/home/frankfeng/cas/tools/downloader:\n"
                        + "  -batch-read-blobs-timeout=1m0s: RPC timeout for BatchReadBlobs, like"
                        + " 2m, 5m etc.\n"
                        + "  -batch-update-blobs-timeout=1m0s: RPC timeout for BatchUpdateBlobs,"
                        + " like 2m, 5m etc.\n"
                        + "  -cache-dir=\"\": Cache directory to store downloaded files.\n"
                        + "  -cache-lock=false: Enable cache lock. When using local cache"
                        + " (-cache-dir is set) and enable cache lock, the downloader will add lock"
                        + " when it changes cache, so you can safely run multiple downloader"
                        + " instances simultaneously.\n"
                        + "  -cache-max-size=0: Cache is trimmed if the cache gets larger than this"
                        + " value. If 0, the cache is effectively a leak.\n"
                        + "  -cas-addr=\"remotebuildexecution.googleapis.com:443\": RBE server"
                        + " addr\n"
                        + "  -cas-concurrency=25: the maximum number of concurrent download"
                        + " operations.\n"
                        + "  -cas-instance=\"\": RBE instance\n"
                        + "  -chunks-only=false: Only download chunk files and the index file (skip"
                        + " file restoration) for chunked version of artifacts.\n"
                        + "  -digest=\"\": Digest of root directory proto \"<digest hash>/<size"
                        + " bytes>\".\n"
                        + "  -dir=\"\": Directory to download the tree. Files in this directory"
                        + " will be overwritten and will not be restored on errors.\n"
                        + "  -disable-cache=false: Disable local cache.\n"
                        + "  -download-timeout=0s: Fixed timeout for the entire download process."
                        + " If set, the download will fail if it takes longer than this duration,"
                        + " but stats will still be dumped.\n"
                        + "  -dump-json=\"\": Dump download stats to json file.\n"
                        + "  -exclude-filters=[]: Regular expression of paths to be excluded from"
                        + " uploading.\n"
                        + "  -get-capabilities-timeout=5s: RPC timeout for GetCapabilities, like"
                        + " 20s, 1m etc.\n"
                        + "  -get-tree-timeout=1m0s: RPC timeout for GetTree, like 2m, 5m etc.\n"
                        + "  -include-filters=[]: Regular expression of paths to be excluded from"
                        + " uploading.\n"
                        + "  -invocation-id=\"\": comma-separated list of key-value pairs, like"
                        + " 'bid=<build-id>,branch=<branch>,flavor=<flavor>'.\n"
                        + "  -keep-chunks=false: Keep chunk files and the index file around for"
                        + " chunked version of artifacts.\n"
                        + "  -memory-limit=0: Memory limit in MiB.\n"
                        + "  -min-download-mbps=0: Minimum download speed in megabytes per second."
                        + " If set, an effective timeout will be calculated and applied to the"
                        + " download.\n"
                        + "  -rpc-timeout=1m0s: Default RPC timeout as duration, like 20s, 1m"
                        + " etc.\n"
                        + "  -service-account-json=\"\": Path to JSON file with service account"
                        + " credentials to use.\n"
                        + "  -use-adc=false: True to use Application Default Credentials (ADC).\n"
                        + "  -use-hardlink=true: By default local cache will use hardlink when push"
                        + " and pull files.\n"
                        + "  -version=false: Print version information\n";

        File fakeCasDownloader = tempFolder.newFile("casdownloader");
        fakeCasDownloader.setExecutable(true);
        String scriptContent =
                "#!/bin/bash\n"
                        + "if [[ \"$1\" == \"--help\" ]]; then\n"
                        + "  echo \""
                        + usageOutput.replace("\"", "\\\"")
                        + "\"\n"
                        + "fi\n";
        Files.writeString(fakeCasDownloader.toPath(), scriptContent);

        Set<String> flags = new HashSet<>();
        CasUtil.checkCasClientFlags(fakeCasDownloader.toPath(), flags::addAll);

        assertThat(flags).contains("download-timeout");
        assertThat(flags).contains("cache-dir");
        assertThat(flags).contains("digest");
        assertThat(flags).contains("version");
        assertThat(flags).contains("service-account-json");
        assertThat(flags.size()).isAtLeast(20);
    }
}
