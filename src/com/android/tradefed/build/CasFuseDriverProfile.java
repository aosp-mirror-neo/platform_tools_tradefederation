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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * CasDriverProfile is a specific implementation of IFuseDriverProfile for device images which are
 * FastCDC chunked directories.
 */
public class CasFuseDriverProfile implements IFuseDriverProfile {

    private final Path mDriverPath;
    private static final String CHUNKS_DIR_NAME = "_chunks";
    private static final String CHUNKS_INDEX_FILENAME = "_chunks_index.json";
    // All device images are expected to have a boot image file.
    private static final String BOOT_IMAGE_FILENAME = "boot.img";
    private long mMountTimeoutInMs = 500;

    /**
     * Creates a new CasFuseDriverProfile with the given driver path.
     *
     * @param driverPath The path to the driver binary.
     */
    public CasFuseDriverProfile(Path driverPath) {
        this.mDriverPath = driverPath;
    }

    /**
     * Creates a new CasFuseDriverProfile with the given driver path and mount timeout.
     *
     * @param driverPath The path to the driver binary.
     * @param mountTimeoutInMs The timeout in milliseconds to wait for the mount point to be ready.
     */
    public CasFuseDriverProfile(Path driverPath, long mountTimeoutInMs) {
        this.mDriverPath = driverPath;
        this.mMountTimeoutInMs = mountTimeoutInMs;
    }

    /**
     * Validates the given source data path for CAS FUSE mounting. that are structured as:
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
     * @param sourceDataPath The path to the source data.
     * @throws IOException If the source data path is invalid.
     */
    @Override
    public void validateSourceDataPath(Path sourceDataPath) throws IOException {
        if (!Files.exists(sourceDataPath) || !Files.isDirectory(sourceDataPath)) {
            throw new IOException(
                    String.format(
                            "Source data path \'%s\' is not a directory",
                            sourceDataPath.toAbsolutePath()));
        }
        Path chunksDirPath = sourceDataPath.resolve(CHUNKS_DIR_NAME);
        if (!Files.exists(chunksDirPath) || !Files.isDirectory(chunksDirPath)) {
            throw new IOException(
                    String.format(
                            "subdirectory \'_chunks\' not found in source data path: %s",
                            chunksDirPath.toAbsolutePath()));
        }
        List<Path> chunksIndexFilePaths =
                Arrays.asList(
                        chunksDirPath.resolve(CHUNKS_INDEX_FILENAME),
                        sourceDataPath.resolve(CHUNKS_INDEX_FILENAME));
        if (chunksIndexFilePaths.stream().filter(Files::exists).count() == 0) {
            throw new IOException(
                    String.format(
                            "File \'_chunks_index.json\' not found in source data path: %s",
                            sourceDataPath.toAbsolutePath()));
        }
    }

    @Override
    public boolean isMountPointReady(Path mountPoint) {
        return Files.exists(mountPoint.resolve(BOOT_IMAGE_FILENAME));
    }

    @Override
    public List<String> buildMountCommand(Path sourceDataPath, Path mountPoint) {
        return Arrays.asList(
                mDriverPath.toAbsolutePath().toString(),
                "--chunks",
                sourceDataPath.resolve(CHUNKS_DIR_NAME).toAbsolutePath().toString(),
                "--mount",
                mountPoint.toAbsolutePath().toString());
    }

    @Override
    public long getMountTimeoutInMs() {
        return mMountTimeoutInMs;
    }

    @Override
    public String getDriverName() {
        return "casviewer";
    }
}
