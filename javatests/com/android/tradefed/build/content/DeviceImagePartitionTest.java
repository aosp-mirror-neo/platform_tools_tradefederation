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
package com.android.tradefed.build.content;

import static org.junit.Assert.assertEquals;

import com.android.tradefed.build.content.ArtifactDetails.ArtifactFileDescriptor;
import com.android.tradefed.util.FileUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.List;

/** Unit tests for {@link DeviceImagePartition}. */
@RunWith(JUnit4.class)
public class DeviceImagePartitionTest {

    @Test
    public void testIsWithinPartition() throws Exception {
        File currentJson = ArtifactDetailsTest.generateCurrentContent();
        try {
            ArtifactDetails artifactDetails =
                    ArtifactDetails.parseFile(currentJson, "target_files-P9999.zip");
            List<ArtifactFileDescriptor> systemPartitionDescriptors =
                    getDescriptorsWithinPartitions(artifactDetails, DeviceImagePartition.SYSTEM);
            List<ArtifactFileDescriptor> vendorPartitionDescriptors =
                    getDescriptorsWithinPartitions(artifactDetails, DeviceImagePartition.VENDOR);
            List<ArtifactFileDescriptor> otherPartitionDescriptors =
                    getDescriptorsWithinPartitions(artifactDetails, DeviceImagePartition.OTHER);
            assertEquals(1, systemPartitionDescriptors.size());
            assertEquals("SYSTEM/bin/abb", systemPartitionDescriptors.get(0).path);
            assertEquals(1, vendorPartitionDescriptors.size());
            assertEquals(
                    "VENDOR/apex/com.android.hardware.usb.apex",
                    vendorPartitionDescriptors.get(0).path);
            assertEquals(1, otherPartitionDescriptors.size());
            assertEquals(
                    "PRODUCT/overlay/SystemUIGXOverlay.apk", otherPartitionDescriptors.get(0).path);
        } finally {
            FileUtil.deleteFile(currentJson);
        }
    }

    private static List<ArtifactFileDescriptor> getDescriptorsWithinPartitions(
            ArtifactDetails artifactDetails, DeviceImagePartition deviceImagePartition) {
        return artifactDetails.details.stream()
                .filter(deviceImagePartition.getIsWithinPartition())
                .toList();
    }
}
