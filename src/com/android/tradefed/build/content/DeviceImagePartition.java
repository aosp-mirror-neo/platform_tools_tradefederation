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

import com.android.tradefed.build.content.ArtifactDetails.ArtifactFileDescriptor;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;

import java.util.function.Predicate;

/** Defines the partitions for a device image. */
public enum DeviceImagePartition {
    SYSTEM(
            "device_image_system_partition",
            InvocationMetricKey.DEVICE_IMAGE_SYSTEM_HASH,
            descriptor -> isWithinCoreSystemLayerPartitions(descriptor)),
    VENDOR(
            "device_image_vendor_partition",
            InvocationMetricKey.DEVICE_IMAGE_VENDOR_HASH,
            descriptor -> isWithinCoreVendorLayerPartitions(descriptor)),
    OTHER(
            "device_image_other_partition",
            InvocationMetricKey.DEVICE_IMAGE_OTHER_HASH,
            descriptor ->
                    !(isWithinCoreVendorLayerPartitions(descriptor)
                            || isWithinCoreSystemLayerPartitions(descriptor)));

    private static boolean isWithinCoreSystemLayerPartitions(ArtifactFileDescriptor descriptor) {
        return descriptor.path.startsWith("SYSTEM/")
                || descriptor.path.startsWith("SYSTEM_DLKM/")
                || descriptor.path.startsWith("SYSTEM_EXT/");
    }

    private static boolean isWithinCoreVendorLayerPartitions(ArtifactFileDescriptor descriptor) {
        return descriptor.path.startsWith("BOOT/")
                || descriptor.path.startsWith("INIT_BOOT/")
                || descriptor.path.startsWith("ODM/")
                || descriptor.path.startsWith("ODM_DLKM/")
                || descriptor.path.startsWith("VENDOR/")
                || descriptor.path.startsWith("VENDOR_BOOT/")
                || descriptor.path.startsWith("VENDOR_DLKM/");
    }

    private final String mCacheKeyName;
    private final InvocationMetricKey mInvocationMetricKey;
    private final Predicate<ArtifactFileDescriptor> mIsWithinPartition;

    DeviceImagePartition(
            String cacheKeyName,
            InvocationMetricKey invocationMetricKey,
            Predicate<ArtifactFileDescriptor> isWithinPartition) {
        mCacheKeyName = cacheKeyName;
        mInvocationMetricKey = invocationMetricKey;
        mIsWithinPartition = isWithinPartition;
    }

    public String getCacheKeyName() {
        return mCacheKeyName;
    }

    public InvocationMetricKey getInvocationMetricKey() {
        return mInvocationMetricKey;
    }

    public Predicate<ArtifactFileDescriptor> getIsWithinPartition() {
        return mIsWithinPartition;
    }
}
