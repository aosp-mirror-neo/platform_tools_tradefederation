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
import static org.junit.Assert.assertTrue;

import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.result.skipped.AnalysisHeuristic;
import com.android.tradefed.util.FileUtil;

import build.bazel.remote.execution.v2.Digest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.Arrays;
import java.util.Map;

/** Unit tests for {@link ImageContentAnalyzer}. */
@RunWith(JUnit4.class)
public class ImageContentAnalyzerTest {
    private File mBaseJson;
    private File mCurrentJson;
    private ContentAnalysisContext mContext;

    @Before
    public void setUp() throws Exception {
        mBaseJson = ArtifactDetailsTest.generateBaseContent();
        mCurrentJson = ArtifactDetailsTest.generateCurrentContent();
        ContentInformation currentInfo =
                new ContentInformation(mBaseJson, "7777", mCurrentJson, "8888");
        mContext =
                new ContentAnalysisContext(
                        "aosp-target_files-8888.zip", currentInfo, AnalysisMethod.DEVICE_IMAGE);
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.deleteFile(mBaseJson);
        FileUtil.deleteFile(mCurrentJson);
    }

    @Test
    public void testEvaluate() {
        ImageContentAnalyzer analyze =
                new ImageContentAnalyzer(
                        false, Arrays.asList(mContext), AnalysisHeuristic.REMOVE_EXEMPTION);

        ContentAnalysisResults results = analyze.evaluate();
        assertTrue(results.hasDeviceImageChanges());
        Map<String, Digest> imageFileToDigest = results.getImageToDigest();
        assertEquals(3, imageFileToDigest.size());
        assertTrue(imageFileToDigest.containsKey("DEVICE_IMAGE_system_partition"));
        assertTrue(imageFileToDigest.containsKey("DEVICE_IMAGE_vendor_partition"));
        assertTrue(imageFileToDigest.containsKey("DEVICE_IMAGE_other_partition"));
    }

    @Test
    public void testEvaluate_presubmitMode() {
        ContentInformation currentInfo =
                new ContentInformation(mBaseJson, "7777", mCurrentJson, "P9999");
        ContentAnalysisContext contextCurrent =
                new ContentAnalysisContext(
                        "aosp-target_files-P9999.zip", currentInfo, AnalysisMethod.BUILD_KEY);
        ImageContentAnalyzer analyze =
                new ImageContentAnalyzer(
                        true,
                        Arrays.asList(contextCurrent, mContext),
                        AnalysisHeuristic.REMOVE_EXEMPTION);

        ContentAnalysisResults results = analyze.evaluate();
        assertTrue(results.hasAnyTestsChange());
        Map<String, Digest> imageFileToDigest = results.getImageToDigest();
        assertEquals(3, imageFileToDigest.size());
        assertTrue(
                imageFileToDigest.containsKey(
                        "BUILD_KEY_aosp-target_files-ABCDEF.zip_system_partition"));
        assertTrue(
                imageFileToDigest.containsKey(
                        "BUILD_KEY_aosp-target_files-ABCDEF.zip_vendor_partition"));
        assertTrue(
                imageFileToDigest.containsKey(
                        "BUILD_KEY_aosp-target_files-ABCDEF.zip_other_partition"));
    }

    @Test
    public void testEvaluate_contentInformationNotAvailable() {
        ContentAnalysisContext context =
                new ContentAnalysisContext(
                        "aosp-target_files-8888.zip", null, AnalysisMethod.BUILD_KEY);
        context.invalidateAnalysis();
        ImageContentAnalyzer analyze =
                new ImageContentAnalyzer(
                        false, Arrays.asList(context), AnalysisHeuristic.REMOVE_EXEMPTION);

        ContentAnalysisResults results = analyze.evaluate();
        assertTrue(results.hasAnyTestsChange());
        Map<String, Digest> imageFileToDigest = results.getImageToDigest();
        assertEquals(3, imageFileToDigest.size());
        assertTrue(
                imageFileToDigest.containsKey(
                        "BUILD_KEY_aosp-target_files-8888.zip_system_partition"));
        assertTrue(
                imageFileToDigest.containsKey(
                        "BUILD_KEY_aosp-target_files-8888.zip_vendor_partition"));
        assertTrue(
                imageFileToDigest.containsKey(
                        "BUILD_KEY_aosp-target_files-8888.zip_other_partition"));
    }
}
