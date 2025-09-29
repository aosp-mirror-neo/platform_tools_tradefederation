/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may
 * obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.postprocessor;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.List;

/** Unit tests for {@link SimpleperfProfilePostProcessor}. */
@RunWith(MockitoJUnitRunner.class)
public class SimpleperfProfilePostProcessorTest {

    private SimpleperfProfilePostProcessor mProcessor;

    @Mock private IRunUtil mMockRunUtil;
    @Mock private ITestInvocationListener mMockListener;

    private static final TestDescription FAKE_TEST_DESCRIPTION =
            new TestDescription("class", "test");

    @Before
    public void setUp() throws Exception {
        mProcessor = spy(new SimpleperfProfilePostProcessor());
        mProcessor.init(mMockListener);
        doReturn(mMockRunUtil).when(mProcessor).getRunUtil();
    }

    /** Sets up the mock for the pprof_proto_generator binary. */
    private void setUpPprofGenerator() {
        File mockPprofGenerator = mock(File.class);
        when(mockPprofGenerator.exists()).thenReturn(true);
        when(mockPprofGenerator.getAbsolutePath()).thenReturn("/path/to/pprof_proto_generator");
        doReturn(mockPprofGenerator)
                .when(mProcessor)
                .getFileFromTestArtifacts(Mockito.eq("pprof_proto_generator"));
    }

    /** Sets up the mock for the simpleperf data copy file. */
    private File setUpSimpleperfDataCopy() throws Exception {
        File simpleperfDataCopy = File.createTempFile("simpleperf_data_copy", ".data");
        doReturn(simpleperfDataCopy)
                .when(mProcessor)
                .createTempFile("simpleperf_data_copy", ".data");
        return simpleperfDataCopy;
    }

    /** Sets up the command result for the mocked run util. */
    private void setUpCommandResult(CommandStatus status) {
        CommandResult mockCommandResult = new CommandResult(status);
        when(mMockRunUtil.runTimedCmd(Mockito.anyLong(), Mockito.any()))
                .thenReturn(mockCommandResult);
    }

    /** Creates a map of test logs containing a simpleperf profiling file. */
    private Map<String, LogFile> createTestLogs(File profilingFile, String logName) {
        Map<String, LogFile> testLogs = new HashMap<>();
        LogFile logFile =
                new LogFile(profilingFile.getAbsolutePath(), "url", false, LogDataType.DATA, 0L);
        testLogs.put(logName, logFile);
        return testLogs;
    }

    /** Test that processing fails if the pprof_proto_generator cannot be found. */
    @Test
    public void testProcess_noPprofProtoGenerator() throws Exception {
        // Arrange
        doReturn(null).when(mProcessor).getFileFromTestArtifacts("pprof_proto_generator");
        File profilingDataFile = File.createTempFile("simpleperf_profiling", ".data");
        try {
            Map<String, LogFile> testLogs =
                    createTestLogs(profilingDataFile, "simpleperf_profiling_data");
            // Act
            mProcessor.processTestMetricsAndLogs(FAKE_TEST_DESCRIPTION, new HashMap<>(), testLogs);
            // Assert
            verify(mMockRunUtil, never())
                    .runTimedCmd(Mockito.anyLong(), Mockito.any(String[].class));
            verify(mMockListener, never()).testLog(Mockito.anyString(), Mockito.any(), Mockito.any());
        } finally {
            FileUtil.deleteFile(profilingDataFile);
        }
    }

    /** Test that no processing occurs if no simpleperf profiling file is found. */
    @Test
    public void testProcess_noProfilingFile() throws Exception {
        // Arrange
        File otherFile = File.createTempFile("other", ".data");
        try {
            Map<String, LogFile> testLogs = createTestLogs(otherFile, "other_file");
            // Act
            mProcessor.processTestMetricsAndLogs(FAKE_TEST_DESCRIPTION, new HashMap<>(), testLogs);
            // Assert
            verify(mProcessor, never()).getFileFromTestArtifacts(Mockito.anyString());
            verify(mMockRunUtil, never())
                    .runTimedCmd(Mockito.anyLong(), Mockito.any(String[].class));
            verify(mMockListener, never())
                    .testLog(Mockito.anyString(), Mockito.any(), Mockito.any());
        } finally {
            FileUtil.deleteFile(otherFile);
        }
    }

    /** Test the successful processing of a simpleperf profiling file. */
    @Test
    public void testProcess_success() throws Exception {
        // Arrange
        setUpPprofGenerator();
        setUpCommandResult(CommandStatus.SUCCESS);
        File profilingDataFile = File.createTempFile("simpleperf_profiling", ".data");
        File pprofOutputFile = File.createTempFile("pprof_class_test", ".pb");
        doReturn(pprofOutputFile).when(mProcessor).createTempFile("pprof_class_test", "");
        File simpleperfDataCopy = setUpSimpleperfDataCopy();
        try {
            Map<String, LogFile> testLogs =
                    createTestLogs(profilingDataFile, "simpleperf_profiling_12345");
            // Act
            mProcessor.processTestMetricsAndLogs(FAKE_TEST_DESCRIPTION, new HashMap<>(), testLogs);
            // Assert
            ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
            verify(mMockRunUtil).runTimedCmd(Mockito.anyLong(), commandCaptor.capture());
            List<String> expected =
                    Arrays.asList(
                            "/path/to/pprof_proto_generator",
                            "-i",
                            simpleperfDataCopy.getAbsolutePath(),
                            "-o",
                            pprofOutputFile.getAbsolutePath());
            Assert.assertEquals(expected, commandCaptor.getAllValues());
            verify(mMockListener)
                    .testLog(
                            Mockito.eq(pprofOutputFile.getName()),
                            Mockito.eq(LogDataType.PB),
                            Mockito.any());
        } finally {
            FileUtil.deleteFile(profilingDataFile);
            FileUtil.deleteFile(pprofOutputFile);
            FileUtil.deleteFile(simpleperfDataCopy);
        }
    }

    /** Test that the temporary output file is deleted when the command fails. */
    @Test
    public void testProcess_commandFails() throws Exception {
        // Arrange
        setUpPprofGenerator();
        setUpCommandResult(CommandStatus.FAILED);
        File profilingDataFile = File.createTempFile("simpleperf_profiling", ".data");
        File pprofOutputFile = File.createTempFile("pprof_class_test", ".pb");
        doReturn(pprofOutputFile).when(mProcessor).createTempFile("pprof_class_test", "");
        File simpleperfDataCopy = setUpSimpleperfDataCopy();
        try {
            Map<String, LogFile> testLogs =
                    createTestLogs(profilingDataFile, "simpleperf_profiling_12345");
            // Act
            mProcessor.processTestMetricsAndLogs(FAKE_TEST_DESCRIPTION, new HashMap<>(), testLogs);
            // Assert
            ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
            verify(mMockRunUtil).runTimedCmd(Mockito.anyLong(), commandCaptor.capture());
            verify(mMockListener, never())
                    .testLog(Mockito.anyString(), Mockito.any(), Mockito.any());
            verify(mProcessor).deleteFile(pprofOutputFile);
        } finally {
            FileUtil.deleteFile(profilingDataFile);
            FileUtil.deleteFile(simpleperfDataCopy);
        }
    }

    /** Test successful processing for run-level logs. */
    @Test
    public void testProcessRunMetricsAndLogs_success() throws Exception {
        // Arrange
        setUpPprofGenerator();
        setUpCommandResult(CommandStatus.SUCCESS);
        File pprofOutputFile = File.createTempFile("pprof_run", ".pb");
        doReturn(pprofOutputFile).when(mProcessor).createTempFile("pprof_run", "");
        File simpleperfDataCopy = setUpSimpleperfDataCopy();
        File profilingDataFile = File.createTempFile("simpleperf_profiling", ".data");
        try {
            Map<String, LogFile> runLogs =
                    createTestLogs(profilingDataFile, "simpleperf_profiling_run");

            // Act
            mProcessor.processRunMetricsAndLogs(new HashMap<>(), runLogs);

            // Assert
              ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
            verify(mMockRunUtil).runTimedCmd(Mockito.anyLong(), commandCaptor.capture());
            List<String> expected =
                    Arrays.asList(
                            "/path/to/pprof_proto_generator",
                            "-i",
                            simpleperfDataCopy.getAbsolutePath(),
                            "-o",
                            pprofOutputFile.getAbsolutePath());
            Assert.assertEquals(expected, commandCaptor.getAllValues());

            verify(mMockListener)
                    .testLog(
                            Mockito.eq(pprofOutputFile.getName()),
                            Mockito.eq(LogDataType.PB),
                            Mockito.any());
        } finally {
            FileUtil.deleteFile(profilingDataFile);
            FileUtil.deleteFile(pprofOutputFile);
            FileUtil.deleteFile(simpleperfDataCopy);
        }
    }
}
