/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.result.skipped.AnalysisHeuristic;
import com.android.tradefed.util.FileUtil;

import build.bazel.remote.execution.v2.Digest;

import com.google.common.truth.Truth;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;

/** Unit tests for {@link DeviceMerkleTree}. */
@RunWith(JUnit4.class)
public class DeviceMerkleTreeTest {

    @Test
    public void testGenerateDigest() throws IOException {
        File baseJson = ArtifactDetailsTest.generateBaseContent();
        File currentJson = ArtifactDetailsTest.generateCurrentContent();

        try {
            ContentInformation baseInfo = new ContentInformation(null, null, baseJson, "8888");
            ContentAnalysisContext contextBase =
                    new ContentAnalysisContext(
                            "target_files-8888.zip", baseInfo, AnalysisMethod.DEVICE_IMAGE);
            Digest baseDigest =
                    DeviceMerkleTree.buildFromContext(
                            contextBase, AnalysisHeuristic.REMOVE_EXEMPTION);

            ContentInformation currentInfo =
                    new ContentInformation(null, null, currentJson, "8888");
            ContentAnalysisContext contextCurrent =
                    new ContentAnalysisContext(
                            "target_files-P9999.zip", currentInfo, AnalysisMethod.DEVICE_IMAGE);
            Digest currentDigest =
                    DeviceMerkleTree.buildFromContext(
                            contextCurrent, AnalysisHeuristic.REMOVE_EXEMPTION);
            Digest currentVendorDigest =
                    DeviceMerkleTree.buildFromContext(
                            contextCurrent,
                            AnalysisHeuristic.REMOVE_EXEMPTION,
                            DeviceImagePartition.VENDOR.getIsWithinPartition());

            Truth.assertThat(baseDigest.getHash()).isNotEqualTo(currentDigest.getHash());
            Truth.assertThat(currentDigest.getHash()).isNotEqualTo(currentVendorDigest.getHash());
            Truth.assertThat(baseDigest.getHash())
                    .isEqualTo("f7c379fdff3a2a1fe0579f4d8c8a805ce2d2a79cc626776c86be480cfdb0d2b1");
            Truth.assertThat(currentDigest.getHash())
                    .isEqualTo("0d8af894a413a8dfd034c0a5ff1f8149313b78ea06290c3c0f115f619aff76c7");
            Truth.assertThat(currentVendorDigest.getHash())
                    .isEqualTo("1561f82db8b5fa6b37d849c30fab7e084bff4ad4b8152fa2c9fb0e27ec901918");
        } finally {
            FileUtil.deleteFile(baseJson);
            FileUtil.deleteFile(currentJson);
        }
    }
}
