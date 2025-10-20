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
package com.android.tradefed.targetprep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.List;

/** Unit tests for {@link CrosLsNexusPreparer}. */
@RunWith(JUnit4.class)
public class CrosLsNexusPreparerTest {

    private CrosLsNexusPreparer mPreparer;
    @Mock ITestDevice mMockDevice;
    @Mock IRunUtil mMockRunUtil;
    @Mock Process mMockProcess;
    @Mock ITestLogger mMockLogger;
    private TestInformation mTestInfo;
    private IBuildInfo mBuildInfo;
    private File mTempDir;
    private IInvocationContext mContext;

    @Captor ArgumentCaptor<List<String>> mCommandCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mPreparer = spy(new CrosLsNexusPreparer());
        doReturn(mMockRunUtil).when(mPreparer).getRunUtil();
        mPreparer.setTestLogger(mMockLogger);

        when(mMockDevice.getSerialNumber()).thenReturn("chromeos8-row1-rack1-host1:5555");
        when(mMockDevice.getDeviceDescriptor()).thenReturn(null);

        mBuildInfo = new BuildInfo();
        mTempDir = FileUtil.createTempDir("tf-test-logs");
        mBuildInfo.addBuildAttribute("LOG_DIR", mTempDir.getAbsolutePath());

        mContext = new InvocationContext();
        mContext.addDeviceBuildInfo("device", mBuildInfo);
        mContext.addAllocatedDevice("device", mMockDevice);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(mContext).build();

        when(mMockRunUtil.runCmdInBackground(anyList())).thenReturn(mMockProcess);
    }

    @After
    public void tearDownTest() throws Exception {
        FileUtil.recursiveDelete(mTempDir);
    }

    @Test
    public void testSetUpAndTearDown() throws Exception {
        // Simulate port file creation for findUsedPort
        File logDir = FileUtil.createTempDir("test_setup", mTempDir);
        File portFile = new File(logDir, "lsnexus_port");
        FileUtil.writeToFile("12345", portFile);
        doReturn(logDir)
                .when(mPreparer)
                .getServiceLogDir(mTestInfo, "chromeos8-row1-rack1-host1", mMockDevice);

        mPreparer.setUp(mTestInfo);

        verify(mMockRunUtil).runCmdInBackground(mCommandCaptor.capture());

        List<String> command = mCommandCaptor.getValue();
        assertNotNull(command);
        assertTrue("Command should start with lsnexus path", command.get(0).endsWith("lsnexus"));
        assertTrue("Command should contain server", command.contains("server"));
        assertTrue("Command should contain -port", command.contains("-port"));
        assertTrue("Command should contain -logs", command.contains("-logs"));
        assertTrue("Command should contain -dut", command.contains("-dut"));
        assertEquals("chromeos8-row1-rack1-host1", command.get(command.indexOf("-dut") + 1));
        assertTrue("Command should contain -labservice", command.contains("-labservice"));
        assertEquals("localhost:1485", command.get(command.indexOf("-labservice") + 1));

        assertEquals("0", command.get(command.indexOf("-port") + 1));

        String logsPath = command.get(command.indexOf("-logs") + 1);
        assertEquals(logDir.getAbsolutePath(), logsPath);

        String propertyKey = "lsnexus_chromeos8-row1-rack1-host1";
        assertEquals("localhost:12345", mTestInfo.properties().get(propertyKey));

        // Test TearDown
        mPreparer.tearDown(mTestInfo, null);
        verify(mMockProcess).destroy();
        FileUtil.recursiveDelete(logDir);
    }

    @Test
    public void testSetUp_serialWithoutPort() throws Exception {
        when(mMockDevice.getSerialNumber()).thenReturn("cros_device_1");
        // Simulate port file creation for findUsedPort
        File logDir = FileUtil.createTempDir("test_setup_noport", mTempDir);
        File portFile = new File(logDir, "lsnexus_port");
        FileUtil.writeToFile("54321", portFile);
        doReturn(logDir).when(mPreparer).getServiceLogDir(mTestInfo, "cros_device_1", mMockDevice);

        mPreparer.setUp(mTestInfo);

        verify(mMockRunUtil).runCmdInBackground(mCommandCaptor.capture());
        List<String> command = mCommandCaptor.getValue();
        assertEquals("cros_device_1", command.get(command.indexOf("-dut") + 1));

        String propertyKey = "lsnexus_cros_device_1";
        assertEquals("localhost:54321", mTestInfo.properties().get(propertyKey));

        mPreparer.tearDown(mTestInfo, null);

        verify(mMockProcess).destroy();
        FileUtil.recursiveDelete(logDir);
    }

    @Test
    public void testGetDutName_withPort() {
        when(mMockDevice.getSerialNumber()).thenReturn("chromeos8-row1-rack1-host1:5555");
        assertEquals("chromeos8-row1-rack1-host1", mPreparer.getDutName(mMockDevice));
    }

    @Test
    public void testGetDutName_withoutPort() {
        when(mMockDevice.getSerialNumber()).thenReturn("cros_device_1");
        assertEquals("cros_device_1", mPreparer.getDutName(mMockDevice));
    }

    @Test
    public void testGetServiceLogDir_withBuildAttribute() throws Exception {
        File logDir = mPreparer.getServiceLogDir(mTestInfo, "test_dut", mMockDevice);
        assertTrue(
                "Log dir should be under LOG_DIR build attribute",
                logDir.getAbsolutePath().startsWith(mTempDir.getAbsolutePath()));
        assertTrue(
                "Log dir should contain dut name",
                logDir.getAbsolutePath().contains("lsnexus_test_dut"));
        FileUtil.recursiveDelete(logDir);
    }

    @Test
    public void testGetServiceLogDir_withoutBuildAttribute() throws Exception {
        mBuildInfo.removeBuildAttribute("LOG_DIR");
        File logDir = mPreparer.getServiceLogDir(mTestInfo, "test_dut", mMockDevice);
        String tmpDir = System.getProperty("java.io.tmpdir");
        assertTrue(
                "Log dir should be under java.io.tmpdir",
                logDir.getAbsolutePath().startsWith(tmpDir));
        assertTrue(
                "Log dir should contain dut name",
                logDir.getAbsolutePath().contains("lsnexus_test_dut"));
        FileUtil.recursiveDelete(logDir);
    }

    @Test
    public void testFindUsedPort_success() throws Exception {
        File logDir = FileUtil.createTempDir("test_find_port", mTempDir);
        File portFile = new File(logDir, "lsnexus_port");
        FileUtil.writeToFile("12345", portFile);

        int port = mPreparer.findUsedPort(mMockDevice, "test_dut", logDir, 1000);
        assertEquals(12345, port);
        FileUtil.recursiveDelete(logDir);
    }

    @Test(expected = TargetSetupError.class)
    public void testFindUsedPort_timeout() throws Exception {
        File logDir = FileUtil.createTempDir("test_find_port_timeout", mTempDir);
        mPreparer.findUsedPort(mMockDevice, "test_dut", logDir, 100);
        FileUtil.recursiveDelete(logDir);
    }

    @Test(expected = TargetSetupError.class)
    public void testFindUsedPort_invalidContent() throws Exception {
        File logDir = FileUtil.createTempDir("test_find_port_invalid", mTempDir);
        File portFile = new File(logDir, "lsnexus_port");
        FileUtil.writeToFile("not-a-number", portFile);
        mPreparer.findUsedPort(mMockDevice, "test_dut", logDir, 1000);
        FileUtil.recursiveDelete(logDir);
    }

    @Test(expected = TargetSetupError.class)
    public void testSetUp_runCmdFails() throws Exception {
        when(mMockRunUtil.runCmdInBackground(anyList()))
                .thenThrow(new java.io.IOException("Failed to run"));
        mPreparer.setUp(mTestInfo);
    }

    @Test
    public void testTearDown_callexpectedFunctions() throws Exception {
        // Simulate setUp to populate a process
        File logDir = FileUtil.createTempDir("test_teardown_graceful", mTempDir);
        File portFile = new File(logDir, "lsnexus_port");
        FileUtil.writeToFile("12345", portFile);
        // Create a dummy log file
        File dummyLog = new File(logDir, "lsnexus.log");
        FileUtil.writeToFile("test content", dummyLog);

        doReturn(logDir)
                .when(mPreparer)
                .getServiceLogDir(mTestInfo, "chromeos8-row1-rack1-host1", mMockDevice);

        mPreparer.setUp(mTestInfo);
        mPreparer.setTestLogger(mMockLogger);

        mPreparer.tearDown(mTestInfo, null);
        verify(mPreparer).stopProcess(mMockProcess, "chromeos8-row1-rack1-host1", 12345);
        verify(mPreparer).uploadLogs(logDir, true, "");
        FileUtil.recursiveDelete(logDir);
    }

    @Test
    public void testStopProcess_gracefulShutdown() throws Exception {
        when(mMockProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        mPreparer.stopProcess(mMockProcess, "test_dut", 12345);
        verify(mMockProcess).destroy();
        verify(mMockProcess).waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    public void testStopProcess_forcedShutdown() throws Exception {
        when(mMockProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);
        mPreparer.stopProcess(mMockProcess, "test_dut", 12345);
        verify(mMockProcess).destroy();
        verify(mMockProcess).waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        verify(mMockProcess).destroyForcibly();
    }

    @Test
    public void testStopProcess_interrupted() throws Exception {
        when(mMockProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("Interrupted"));
        mPreparer.stopProcess(mMockProcess, "test_dut", 12345);
        verify(mMockProcess).destroy();
        verify(mMockProcess).waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        verify(mMockProcess).destroyForcibly();
    }

    @Test
    public void testUploadLogs() throws Exception {
        File logDir = FileUtil.createTempDir("test_upload_logs", mTempDir);
        File logFile = new File(logDir, "lsnexus.log");
        FileUtil.writeToFile("test content", logFile);

        mPreparer.uploadLogs(logDir, true, "");

        verify(mMockLogger).testLog(eq(logFile.getName()), eq(LogDataType.TEXT), any());
        assertFalse("Log directory should be deleted", logDir.exists());
        FileUtil.recursiveDelete(logDir);
    }

    @Test
    public void testUploadLogs_recursive() throws Exception {
        File logDir = FileUtil.createTempDir("test_upload_logs_recursive", mTempDir);
        File subDir = new File(logDir, "subdir");
        subDir.mkdirs();
        File logFile1 = new File(logDir, "lsnexus.log");
        FileUtil.writeToFile("test content 1", logFile1);
        File logFile2 = new File(subDir, "lsnexus.log");
        FileUtil.writeToFile("test content 2", logFile2);

        mPreparer.uploadLogs(logDir, true, "");

        verify(mMockLogger).testLog(eq(logFile1.getName()), eq(LogDataType.TEXT), any());
        verify(mMockLogger).testLog(eq("subdir_lsnexus.log"), eq(LogDataType.TEXT), any());
        assertFalse("Log directory should be deleted", logDir.exists());
        FileUtil.recursiveDelete(logDir);
    }

    @Test
    public void testUploadLogs_noRecursive() throws Exception {
        File logDir = FileUtil.createTempDir("test_upload_logs_norecursive", mTempDir);
        File subDir = new File(logDir, "subdir");
        subDir.mkdirs();
        File logFile1 = new File(logDir, "lsnexus.log");
        FileUtil.writeToFile("test content 1", logFile1);
        File logFile2 = new File(subDir, "lsnexus.log");
        FileUtil.writeToFile("test content 2", logFile2);

        mPreparer.uploadLogs(logDir, false, "");

        verify(mMockLogger).testLog(eq(logFile1.getName()), eq(LogDataType.TEXT), any());
        assertFalse("Log directory should be deleted", logDir.exists());
        FileUtil.recursiveDelete(logDir);
    }

    @Test
    public void testUploadLogs_nullDir() throws Exception {
        mPreparer.uploadLogs(null, true, "");
    }

    @Test
    public void testUploadLogs_nonExistentDir() throws Exception {
        File logDir = new File(mTempDir, "non_existent");
        mPreparer.uploadLogs(logDir, true, "");
    }

    @Test
    public void testUploadLogs_notDirectory() throws Exception {
        File logFile = new File(mTempDir, "not_a_directory");
        FileUtil.writeToFile("test content", logFile);
        mPreparer.uploadLogs(logFile, true, "");
        assertTrue("Log file should not be deleted", logFile.exists());
    }

    @Test
    public void testUploadLogs_emptyDir() throws Exception {
        File logDir = FileUtil.createTempDir("test_upload_logs_empty", mTempDir);
        mPreparer.uploadLogs(logDir, true, "");
        assertFalse("Log directory should be deleted", logDir.exists());
        FileUtil.recursiveDelete(logDir);
    }
}
