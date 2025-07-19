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

import com.android.tradefed.log.LogUtil.CLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public final class CasUtil {

    public static final String CHUNKS_DIR_NAME = "_chunks";
    public static final String CHUNKS_INDEX_FILENAME = "_chunks_index.json";
    public static final String BOOT_IMAGE_FILENAME = "boot.img";

    private static final IRunUtil mRunUtil = RunUtil.getDefault();

    private CasUtil() {
        throw new AssertionError("CasUtil is a utility class and should not be instantiated.");
    }

    /**
     * Validates the given path is a valid FastCDC-chunked directory.
     *
     * <pre>
     *   device_image.zip/
     *     _chunks/
     *       _chunks_index.json
     *       chunk1.img
     *     _chunks_index.json
     * </pre>
     *
     * Only contents of the source data path are checked for validity. The _chunks_index.json must
     * be present either in the _chunks directory or as a sibling of _chunks (for backwards
     * compatibility).
     *
     * @param path The path to the chunked directory.
     * @throws IOException If the path is not a valid FastCDC-chunked directory.
     */
    public static void validateChunkedDir(Path path) throws IOException {
        if (path == null) {
            throw new IOException("path is null");
        }
        if (!Files.isDirectory(path)) {
            throw new IOException(
                    String.format("'%s' is not a directory", path));
        }
        Path chunksDirPath = path.resolve(CHUNKS_DIR_NAME);
        if (!Files.isDirectory(chunksDirPath)) {
            throw new IOException(
                    String.format(
                            "subdirectory '%s' not found in directory: %s",
                            CHUNKS_DIR_NAME, chunksDirPath));
        }
        List<Path> chunksIndexFilePaths =
                Arrays.asList(
                        chunksDirPath.resolve(CHUNKS_INDEX_FILENAME),
                        path.resolve(CHUNKS_INDEX_FILENAME));
        if (chunksIndexFilePaths.stream().filter(Files::exists).count() == 0) {
            throw new IOException(
                    String.format(
                            "File '%s' not found in directory: '%s'",
                            CHUNKS_INDEX_FILENAME, path));
        }
    }

    /** Returns true if the given path is a valid FastCDC-chunked directory. */
    public static boolean isChunkedDir(Path path) {
        try {
            validateChunkedDir(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns true if the given path is a valid uncompressed device image.
     *
     * @param path The absolute path to the device image.
     * @return True if the path is a valid uncompressed device image, false otherwise.
     */
    public static boolean isUncompressedDeviceImage(Path path) {
        // All device images are expected to have a boot image file.
        return Files.exists(path.resolve(BOOT_IMAGE_FILENAME));
    }

    public static void logCasClientVersion(
            Path clientPath, Consumer<String> versionHandlerFn) {
        if (clientPath == null) {
            return;
        }

        String clientName = clientPath.getFileName().toString();
        CommandResult cr =
                mRunUtil.runTimedCmd(5000, clientPath.toString(), "--version");
        if (cr.getStatus() != CommandStatus.SUCCESS) {
            CLog.e(
                    "Failed to extract '%s' version. Status: %s\nStdout: %s\nStderr: %s",
                    clientName, cr.getStatus(), cr.getStdout(), cr.getStderr());
            return;
        }

        String prefix = "version: ";
        String version = cr.getStdout().trim();
        if (version.startsWith(prefix)) {
            version = version.substring(prefix.length());
        } else {
            version = "unknown";
        }
        CLog.i("%s, version: %s", clientName, version);

        if (versionHandlerFn != null) {
            versionHandlerFn.accept(version);
        }
    }

    public static void logCasClientVersion(Path clientPath) {
        logCasClientVersion(clientPath, null);
    }

}
