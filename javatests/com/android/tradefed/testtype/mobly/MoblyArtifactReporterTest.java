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

package com.android.tradefed.testtype.mobly;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.FileUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;

/** Unit tests for {@link MoblyYamlResultParser}. */
@RunWith(JUnit4.class)
public class MoblyArtifactReporterTest {
    private MoblyArtifactReporter mReporter;
    private ITestInvocationListener mMockListener;
    private File mRootDir;

    private File createTempFiles() throws Exception {
        File rootDir = FileUtil.createTempDir("root_dir");
        File classDir = FileUtil.createTempDir("class_dir", rootDir);
        File testDir = FileUtil.createTempDir("test_dir", classDir);
        File run_artifact = FileUtil.createTempFile("run_artifact", ".txt", rootDir);
        File class_artifact = FileUtil.createTempFile("class_artifact", ".txt", classDir);
        File test_artifact = FileUtil.createTempFile("test_artifact", ".txt", testDir);
        return rootDir;
    }

    @Before
    public void setUp() throws Exception {
        mMockListener = Mockito.mock(ITestInvocationListener.class);
        mRootDir = createTempFiles();
    }

    @After
    public void tearDown() {
        FileUtil.recursiveDelete(mRootDir);
    }

    @Test
    public void testReportAllRunArtifacts() {
        MoblyArtifactReporter reporter = new MoblyArtifactReporter(mRootDir);

        reporter.reportRunArtifacts(mMockListener);
        verify(mMockListener, times(3)).testLog(any(), any(), any());
        reporter.reportRunArtifacts(mMockListener);
        verify(mMockListener, times(3)).testLog(any(), any(), any());
    }

    private MoblyYamlResultRecordHandler.Record createRecord(String testName, String testClass) {
        return MoblyYamlResultRecordHandler.Record.builder()
                .setTestName(testName)
                .setTestClass(testClass)
                .setBeginTime("null")
                .setEndTime("null")
                .build();
    }

    @Test
    public void testReportOnlyTestArtifact() {
        MoblyArtifactReporter reporter = new MoblyArtifactReporter(mRootDir);
        MoblyYamlResultRecordHandler.Record record = createRecord("test_dir", "class_dir");
        reporter.reportArtifactsForRecord(mMockListener, record);
        verify(mMockListener, times(1)).testLog(any(), any(), any());
    }

    @Test
    public void testReportTestArtifact_ThenRemainingArtifacts() {
        MoblyArtifactReporter reporter = new MoblyArtifactReporter(mRootDir);
        MoblyYamlResultRecordHandler.Record record = createRecord("test_dir", "class_dir");
        reporter.reportArtifactsForRecord(mMockListener, record);
        verify(mMockListener, times(1)).testLog(any(), any(), any());
        reporter.reportRunArtifacts(mMockListener);
        verify(mMockListener, times(3)).testLog(any(), any(), any());
    }
}
