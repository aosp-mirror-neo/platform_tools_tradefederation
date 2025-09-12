/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.result.skipped;

import com.android.tradefed.build.content.DeviceImagePartition;
import com.android.tradefed.cache.DigestCalculator;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.skipped.SkipReason.DemotionTrigger;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link SkipManager}. */
@RunWith(JUnit4.class)
public class SkipManagerTest {

    private SkipManager mManager;
    private TestInformation mTestInformation;
    private IConfiguration mConfiguration;
    private IInvocationContext mContext;

    @Before
    public void setup() {
        mManager = new SkipManager();
        mManager.setSkipDecision(true);
        Directory defaultDirectory = Directory.getDefaultInstance();
        Digest defaultDigest = DigestCalculator.compute(defaultDirectory);
        Map<String, Digest> imageFileToDigest = new HashMap<>();
        for (DeviceImagePartition imagePartition : DeviceImagePartition.values()) {
            imageFileToDigest.put(
                    "DEVICE_IMAGE_" + imagePartition.getCacheKeyName(), defaultDigest);
            imageFileToDigest.put("BUILD_KEY_" + imagePartition.getCacheKeyName(), defaultDigest);
        }
        mManager.setImageToDigest(imageFileToDigest);
        mContext = new InvocationContext();
        mConfiguration = new Configuration("test", "name");
        mTestInformation = TestInformation.newBuilder().setInvocationContext(mContext).build();
    }

    @Test
    public void testSkipInvocation() {
        Truth.assertThat(mManager.shouldSkipInvocation(mTestInformation, null)).isFalse();
        mManager.reportDiscoveryWithNoTests();
        Truth.assertThat(mManager.shouldSkipInvocation(mTestInformation, null)).isTrue();
    }

    @Test
    public void testEmptySetup() {
        mManager.setup(mConfiguration, mContext);
        Truth.assertThat(mManager.getDemotedTests()).isEmpty();
    }

    @Test
    public void testSetupAndFilters() throws Exception {
        OptionSetter setter = new OptionSetter(mManager);
        setter.setOptionValue(
                "demotion-filters",
                "x86 module-name",
                new SkipReason("message", DemotionTrigger.ERROR_RATE).toString());
        mManager.setup(mConfiguration, mContext);
        Truth.assertThat(mManager.getDemotedTests().size()).isEqualTo(1);
    }

    @Test
    public void testImageToDigest_vendorOnlyStrategyForDeviceImage() throws Exception {
        OptionSetter setter = new OptionSetter(mManager);
        setter.setOptionValue("enable-image-granularity-strategy", "true");
        mConfiguration.setCommandLine(
                new String[] {
                    "google/cts/google-cts-launcher-for-aosp",
                    "--config-name",
                    "vts",
                    "--cts-package-name",
                    "android-vts.zip"
                });
        mManager.setup(mConfiguration, mContext);

        Map<String, Digest> imageFileToDigest = mManager.getImageToDigest();
        Truth.assertThat(imageFileToDigest.size()).isEqualTo(4);
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "DEVICE_IMAGE_" + DeviceImagePartition.VENDOR.getCacheKeyName()))
                .isTrue();
    }

    @Test
    public void testImageToDigest_systemVendorStrategyForDeviceImage() throws Exception {
        OptionSetter setter = new OptionSetter(mManager);
        setter.setOptionValue("enable-image-granularity-strategy", "true");
        mConfiguration.setCommandLine(
                new String[] {
                    "google/cts/google-cts-launcher-for-aosp",
                    "--config-name",
                    "cts-automated",
                    "--cts-package-name",
                    "android-cts.zip"
                });
        mManager.setup(mConfiguration, mContext);

        Map<String, Digest> imageFileToDigest = mManager.getImageToDigest();
        Truth.assertThat(imageFileToDigest.size()).isEqualTo(5);
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "DEVICE_IMAGE_" + DeviceImagePartition.VENDOR.getCacheKeyName()))
                .isTrue();
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "DEVICE_IMAGE_" + DeviceImagePartition.SYSTEM.getCacheKeyName()))
                .isTrue();
    }

    @Test
    public void testImageToDigest_fullImageStrategyForDeviceImage() throws Exception {
        mConfiguration.setCommandLine(
                new String[] {
                    "google/cts/google-cts-launcher-for-aosp",
                    "--config-name",
                    "gts-automated",
                    "--cts-package-name",
                    "android-gts.zip"
                });
        mManager.setup(mConfiguration, mContext);

        Map<String, Digest> imageFileToDigest = mManager.getImageToDigest();
        Truth.assertThat(imageFileToDigest.size()).isEqualTo(6);
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "DEVICE_IMAGE_" + DeviceImagePartition.VENDOR.getCacheKeyName()))
                .isTrue();
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "DEVICE_IMAGE_" + DeviceImagePartition.SYSTEM.getCacheKeyName()))
                .isTrue();
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "DEVICE_IMAGE_" + DeviceImagePartition.OTHER.getCacheKeyName()))
                .isTrue();
    }

    @Test
    public void testImageToDigest_noPartitionStrategyForBuildKey() throws Exception {
        OptionSetter setter = new OptionSetter(mManager);
        setter.setOptionValue("enable-image-granularity-strategy", "true");
        setter.setOptionValue("enable-build-key-granularity-strategy", "true");
        mConfiguration.setCommandLine(
                new String[] {
                    "google/cts/google-cts-launcher-for-aosp",
                    "--config-name",
                    "vts",
                    "--cts-package-name",
                    "android-vts.zip"
                });
        mManager.setup(mConfiguration, mContext);

        Map<String, Digest> imageFileToDigest = mManager.getImageToDigest();
        Truth.assertThat(imageFileToDigest.size()).isEqualTo(1);
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "DEVICE_IMAGE_" + DeviceImagePartition.VENDOR.getCacheKeyName()))
                .isTrue();
    }

    @Test
    public void testImageToDigest_systemOnlyStrategyForBuildKey() throws Exception {
        OptionSetter setter = new OptionSetter(mManager);
        setter.setOptionValue("enable-image-granularity-strategy", "true");
        setter.setOptionValue("enable-build-key-granularity-strategy", "true");
        mConfiguration.setCommandLine(
                new String[] {
                    "google/cts/google-cts-launcher-for-aosp",
                    "--config-name",
                    "cts-on-gsi",
                    "--cts-package-name",
                    "android-cts.zip"
                });
        mManager.setup(mConfiguration, mContext);

        Map<String, Digest> imageFileToDigest = mManager.getImageToDigest();
        Truth.assertThat(imageFileToDigest.size()).isEqualTo(2);
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "DEVICE_IMAGE_" + DeviceImagePartition.VENDOR.getCacheKeyName()))
                .isTrue();
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "BUILD_KEY_" + DeviceImagePartition.SYSTEM.getCacheKeyName()))
                .isTrue();
    }

    @Test
    public void testImageToDigest_fullImageStrategyForBuildKey() throws Exception {
        mConfiguration.setCommandLine(
                new String[] {
                    "google/cts/google-cts-launcher-for-aosp",
                    "--config-name",
                    "gts-automated",
                    "--cts-package-name",
                    "android-gts.zip"
                });
        mManager.setup(mConfiguration, mContext);

        Map<String, Digest> imageFileToDigest = mManager.getImageToDigest();
        Truth.assertThat(imageFileToDigest.size()).isEqualTo(6);
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "BUILD_KEY_" + DeviceImagePartition.VENDOR.getCacheKeyName()))
                .isTrue();
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "BUILD_KEY_" + DeviceImagePartition.SYSTEM.getCacheKeyName()))
                .isTrue();
        Truth.assertThat(
                        imageFileToDigest.containsKey(
                                "BUILD_KEY_" + DeviceImagePartition.OTHER.getCacheKeyName()))
                .isTrue();
    }
}
