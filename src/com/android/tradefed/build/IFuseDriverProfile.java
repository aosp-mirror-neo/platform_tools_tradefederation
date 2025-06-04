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
import java.nio.file.Path;
import java.util.List;

/**
 * Defines the specific behavior of a particular FUSE driver, allowing the MountManager to be
 * generic.
 */
public interface IFuseDriverProfile {

    /**
     * The default timeout to wait for the mount point to be ready.
     *
     * @return The default timeout in milliseconds.
     */
    default long getMountTimeoutInMs() {
        return 500;
    }

    /**
     * Validates that the source data path is suitable for this specific FUSE driver.
     *
     * @param sourceDataPath The source data path to check.
     * @throws IOException If the source data path is not valid.
     */
    void validateSourceDataPath(Path sourceDataPath) throws IOException;

    /**
     * Checks if the mount point is ready to be used.
     *
     * @param mountPoint The mount point to check.
     * @return True if the mount point is ready, false otherwise.
     */
    default boolean isMountPointReady(Path mountPoint) {
        return true;
    }

    /**
     * Creates the command line to execute the mount command.
     *
     * @param sourceDataPath The source data path.
     * @param mountPoint The target mount point.
     * @return A list of string representing the command line to execute.
     */
    List<String> buildMountCommand(Path sourceDataPath, Path mountPoint);

    /**
     * A descriptive name for this driver type.
     *
     * @return The driver's name (e.g., "FastCDC", "sshfs").
     */
    String getDriverName();
}
