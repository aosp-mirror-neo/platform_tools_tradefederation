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

package com.google.android.tradefed.targetprep;

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.IFlashingResourcesRetriever;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;

import com.google.android.tradefed.build.AndroidBuildFetcher;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * A {@link IFlashingResourcesRetriever} that retrieves auxiliary image files from android build
 * apiary, fallback to nfs if the apiary is not available.
 */
public class BuildApiFlashingResourcesRetriever implements IFlashingResourcesRetriever {
    private final String mDeviceType;
    private final String mBuildEnvironment;
    private AndroidBuildFetcher mBuildFetcher;

    /**
     * Constructor
     *
     * @param deviceProductType type of the device
     * @param buildEnvironment the Build API environment to download from
     */
    public BuildApiFlashingResourcesRetriever(String deviceProductType, String buildEnvironment) {
        mDeviceType = deviceProductType;
        mBuildEnvironment = buildEnvironment;
    }

    /** {@inheritDoc} */
    @Override
    public File retrieveFile(String imageName, String version) throws TargetSetupError {
        File outputFile = null;
        boolean deleteFile = true;

        try {
            outputFile =
                    FileUtil.createTempFile(String.format("%s.%s", imageName, version), ".img");
            CLog.v("Fetch device image file to %s", outputFile.getAbsolutePath());
            getBuildFetcher().fetchDeviceBlob(mDeviceType, imageName, version, outputFile);
            deleteFile = false;
            return outputFile;
        } catch (GeneralSecurityException | IOException e) {
            String errorMsg =
                    String.format(
                            "Can't fetch device image (deviceType: %s, imageName: %s, version: %s)"
                                    + " file from android build apiary.",
                            mDeviceType, imageName, version);
            CLog.w(errorMsg);
            CLog.e(e);
            DeviceDescriptor nullDescriptor = null;
            throw new TargetSetupError(errorMsg, nullDescriptor);
        } finally {
            if (deleteFile) {
                outputFile.delete();
            }
        }
    }

    /** Retrieves a build fetcher, creating a new one if one hasn't been instantiated. */
    AndroidBuildFetcher getBuildFetcher() throws IOException, GeneralSecurityException {
        if (mBuildFetcher == null) {
            mBuildFetcher = new AndroidBuildFetcher(mBuildEnvironment);
        }
        return mBuildFetcher;
    }
}
