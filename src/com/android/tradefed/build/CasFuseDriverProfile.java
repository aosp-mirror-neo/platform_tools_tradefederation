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

import com.android.tradefed.util.CasUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * CasDriverProfile is a specific implementation of IFuseDriverProfile for device images which are
 * FastCDC chunked directories.
 */
public class CasFuseDriverProfile implements IFuseDriverProfile {

    private final Path mDriverPath;
    private static final String DRIVER_NAME = "casviewer";
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
     * @param sourceDataPath The path to the source data.
     * @throws IOException If the source data path is invalid.
     */
    @Override
    public void validateSourceDataPath(Path sourceDataPath) throws IOException {
        CasUtil.validateChunkedDir(sourceDataPath);
    }

    @Override
    public boolean isMountPointReady(Path mountPoint) {
        return CasUtil.isUncompressedDeviceImage(mountPoint);
    }

    @Override
    public List<String> buildMountCommand(Path sourceDataPath, Path mountPoint) {
        return Arrays.asList(
                mDriverPath.toAbsolutePath().toString(),
                "--chunks",
                sourceDataPath.resolve(CasUtil.CHUNKS_DIR_NAME).toAbsolutePath().toString(),
                "--mount",
                mountPoint.toAbsolutePath().toString());
    }

    @Override
    public long getMountTimeoutInMs() {
        return mMountTimeoutInMs;
    }

    @Override
    public String getDriverName() {
        return DRIVER_NAME;
    }
}
