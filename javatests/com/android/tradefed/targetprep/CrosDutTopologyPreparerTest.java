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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;

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

/** Unit tests for {@link CrosDutTopologyPreparer}. */
@RunWith(JUnit4.class)
public class CrosDutTopologyPreparerTest {

    private CrosDutTopologyPreparer mPreparer;
    @Mock ITestDevice mMockDevice;
    @Mock IRunUtil mMockRunUtil;
    @Mock CommandResult mMockResult;
    private TestInformation mTestInfo;
    private IBuildInfo mBuildInfo;
    private IInvocationContext mContext;

    @Captor ArgumentCaptor<String[]> mCommandCaptor;
    @Captor ArgumentCaptor<List<String>> mListCommandCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mPreparer = spy(new CrosDutTopologyPreparer());
        doReturn(mMockRunUtil).when(mPreparer).getRunUtil();

        when(mMockDevice.getSerialNumber()).thenReturn("dut1:5555");
        when(mMockDevice.getDeviceDescriptor()).thenReturn(null);

        mBuildInfo = new BuildInfo();
        mBuildInfo.addBuildAttribute("LOG_DIR", System.getProperty("java.io.tmpdir"));

        mContext = new InvocationContext();
        mContext.addDeviceBuildInfo("device", mBuildInfo);
        mContext.addAllocatedDevice("device", mMockDevice);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(mContext).build();
    }

    private void setupDtConverterOutput(CommandStatus status, String stdout, String stderr) {
        when(mMockResult.getStatus()).thenReturn(status);
        when(mMockResult.getStdout()).thenReturn(stdout);
        when(mMockResult.getStderr()).thenReturn(stderr);
        doReturn(mMockResult).when(mPreparer).runCommand(any());
    }

    private void setupSuccessfulDtConverterOutput() {
        String output =
                "-s dut1:5555\n"
                        + "--invocation-data lsnexus_primary.host=localhost\n"
                        + "--invocation-data lsnexus_primary.port=9100\n"
                        + "-c /tmp/mobly_config.yaml\n"
                        + "extra_prop value1";
        setupDtConverterOutput(CommandStatus.SUCCESS, output, "");
    }

    @Test
    public void testSetUp_Success() throws Exception {
        setupSuccessfulDtConverterOutput();

        mPreparer.setUp(mTestInfo);

        verify(mPreparer).runCommand(mListCommandCaptor.capture());
        List<String> command = mListCommandCaptor.getValue();

        assertTrue(command.get(0).endsWith("dt-converter"));
        assertTrue(command.contains("-unit"));
        assertEquals("dut1", command.get(command.indexOf("-unit") + 1));
        assertTrue(command.contains("-labservice"));
        assertTrue(command.contains("-logdir"));
        assertTrue(command.contains("-config_name"));
        assertTrue(command.contains("-mh"));
        assertTrue(command.contains("-tf"));
        assertEquals("/tmp/mobly_config.yaml", mTestInfo.properties().get("-c"));
        assertEquals("dut1:5555", mTestInfo.properties().get("-s"));
        assertEquals("value1", mTestInfo.properties().get("extra_prop"));
        assertEquals(
                "localhost", mTestInfo.properties().get("invocation_data.lsnexus_primary.host"));
        assertEquals("9100", mTestInfo.properties().get("invocation_data.lsnexus_primary.port"));
    }

    @Test
    public void testSetUp_WithLsNexusAddr() throws Exception {
        setupSuccessfulDtConverterOutput();
        mTestInfo.properties().put("lsnexus_dut1", "localhost:12345");

        mPreparer.setUp(mTestInfo);

        verify(mPreparer).runCommand(mListCommandCaptor.capture());
        List<String> command = mListCommandCaptor.getValue();

        assertTrue(command.contains("-lsnexus"));
        assertEquals("dut1=localhost:12345", command.get(command.indexOf("-lsnexus") + 1));
    }

    @Test
    public void testSetUp_DtConverterFailure() throws Exception {
        setupDtConverterOutput(CommandStatus.FAILED, "", "Error occurred");

        assertThrows(TargetSetupError.class, () -> mPreparer.setUp(mTestInfo));
    }

    @Test
    public void testSetUp_MalformedOutput() throws Exception {
        String output = "-s onlykey=1\n" + "-c /tmp/mobly_config.yaml";
        setupDtConverterOutput(CommandStatus.SUCCESS, output, "");

        mPreparer.setUp(mTestInfo);
        // Should not crash, malformed line is skipped
        assertEquals("/tmp/mobly_config.yaml", mTestInfo.properties().get("-c"));
        assertEquals("onlykey=1", mTestInfo.properties().get("-s"));
    }

    @Test
    public void testBuildCommand() throws Exception {
        String dutName = "test-device";
        File logDir = new File("/tmp/logs");
        List<String> command = mPreparer.buildCommand(mTestInfo, mMockDevice, dutName, logDir);

        assertTrue(command.get(0).endsWith("dt-converter"));
        assertTrue(command.contains("-unit"));
        assertEquals(dutName, command.get(command.indexOf("-unit") + 1));
        assertTrue(command.contains("-labservice"));
        assertEquals("localhost:1485", command.get(command.indexOf("-labservice") + 1));
        assertTrue(command.contains("-logdir"));
        assertEquals("/tmp/logs", command.get(command.indexOf("-logdir") + 1));
        assertTrue(command.contains("-config_name"));
        assertEquals("config_test-device", command.get(command.indexOf("-config_name") + 1));
        assertTrue(command.contains("-mh"));
        assertTrue(command.contains("-tf"));
        assertTrue(!command.contains("-lsnexus"));
    }

    @Test
    public void testBuildCommand_WithLsNexusAddr() throws Exception {
        String dutName = "test-device";
        File logDir = new File("/tmp/logs");
        mTestInfo.properties().put("lsnexus_test-device", "localhost:12345");
        List<String> command = mPreparer.buildCommand(mTestInfo, mMockDevice, dutName, logDir);

        assertTrue(command.contains("-lsnexus"));
        assertEquals("test-device=localhost:12345", command.get(command.indexOf("-lsnexus") + 1));
    }

    @Test
    public void testGetDutName() throws Exception {
        when(mMockDevice.getSerialNumber()).thenReturn("device1:1234");
        assertEquals("device1", mPreparer.getDutName(mMockDevice));

        when(mMockDevice.getSerialNumber()).thenReturn("device2");
        assertEquals("device2", mPreparer.getDutName(mMockDevice));
    }

    @Test
    public void testParseOutput() throws Exception {
        String output =
                "-s dut1:5555\n"
                        + "--invocation-data lsnexus_primary.host=localhost\n"
                        + "--invocation-data lsnexus_primary.port=9100\n"
                        + "-c /tmp/mobly_config.yaml\n"
                        + "extra_prop value1\n"
                        + "malformed\n"
                        + "";

        mPreparer.parseOutput(mTestInfo, output);

        assertEquals("/tmp/mobly_config.yaml", mTestInfo.properties().get("-c"));
        assertEquals("dut1:5555", mTestInfo.properties().get("-s"));
        assertEquals("value1", mTestInfo.properties().get("extra_prop"));
        assertEquals(
                "localhost", mTestInfo.properties().get("invocation_data.lsnexus_primary.host"));
        assertEquals("9100", mTestInfo.properties().get("invocation_data.lsnexus_primary.port"));
    }

    @Test
    public void testParseOutput_MalformedInvocationData() throws Exception {
        String output =
                "--invocation-data lsnexus_primary.host=localhost\n"
                        + "--invocation-data malformed_invocation_data\n"
                        + "--invocation-data lsnexus_primary.port=9100";

        mPreparer.parseOutput(mTestInfo, output);

        assertEquals(
                "localhost", mTestInfo.properties().get("invocation_data.lsnexus_primary.host"));
        assertEquals("9100", mTestInfo.properties().get("invocation_data.lsnexus_primary.port"));
        assertTrue(mTestInfo.properties().get("invocation_data.malformed_invocation_data") == null);
    }

    @Test
    public void testParseOutput_Empty() throws Exception {
        String output = "";
        mPreparer.parseOutput(mTestInfo, output);
        assertTrue(mTestInfo.properties().isEmpty());
    }

    @Test
    public void testParseOutput_KeysOnly() throws Exception {
        String output = "-s\n--invocation-data\n-c";
        mPreparer.parseOutput(mTestInfo, output);
        assertTrue(mTestInfo.properties().isEmpty());
    }

    @Test
    public void testGetLogRoot_FromBuildInfo() throws Exception {
        File logRoot = mPreparer.getLogRoot(mTestInfo, mMockDevice);
        assertEquals(System.getProperty("java.io.tmpdir"), logRoot.getAbsolutePath());
    }

    @Test
    public void testGetLogRoot_FallbackToTmpDir() throws Exception {
        mBuildInfo.getBuildAttributes().remove("LOG_DIR");
        File logRoot = mPreparer.getLogRoot(mTestInfo, mMockDevice);
        assertEquals(System.getProperty("java.io.tmpdir"), logRoot.getAbsolutePath());
    }

    @Test
    public void testGetLogRoot_BuildInfoThrows() throws Exception {
        IInvocationContext mockContext = org.mockito.Mockito.mock(IInvocationContext.class);
        when(mockContext.getBuildInfos()).thenThrow(new RuntimeException("Test Exception"));
        TestInformation testInfo =
                TestInformation.newBuilder().setInvocationContext(mockContext).build();

        assertThrows(TargetSetupError.class, () -> mPreparer.getLogRoot(testInfo, mMockDevice));
    }
}
