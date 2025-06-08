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

    @Override
    public void validateSourceDataPath(Path sourceDataPath) throws IOException {
        if (!Files.exists(sourceDataPath) || !Files.isDirectory(sourceDataPath)) {
            throw new IOException(
                    String.format(
                            "Source data path %s is not a directory",
                            sourceDataPath.toAbsolutePath()));
        }
        Path chunksIndexFilePath = sourceDataPath.resolve(CHUNKS_INDEX_FILENAME);
        if (!Files.exists(chunksIndexFilePath)) {
            throw new IOException(
                    String.format(
                            "Source data path %s does not have a chunks index file",
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
                sourceDataPath.toAbsolutePath().toString(),
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
