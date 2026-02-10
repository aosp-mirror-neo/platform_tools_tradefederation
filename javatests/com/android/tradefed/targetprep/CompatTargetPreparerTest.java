/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link CompatTargetPreparer}. */
@RunWith(JUnit4.class)
public class CompatTargetPreparerTest {

    private static final String CMD_GET_DENSITY = "cmd car_service get-density-scale-factor %s";
    private static final String CMD_SET_DENSITY = "cmd car_service set-density-scale-factor %s %s";
    private static final String PACKAGE_NAME = "com.example.app";
    private static final String PACKAGE_NAME_2 = "com.example.second.app";
    private static final String AUTOMOTIVE_FEATURE = "android.hardware.type.automotive";
    private static final String COMPAT_SCALE = "0.7";

    private CompatTargetPreparer mPreparer;
    private TestInformation mTestInfo;
    @Mock private ITestDevice mMockDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mPreparer = new CompatTargetPreparer();
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
    }

    @Test
    public void testInstaller_automotive_success() throws Exception {
        when(mMockDevice.hasFeature(AUTOMOTIVE_FEATURE)).thenReturn(true);
        Map<File, String> appMap = new HashMap<>();
        appMap.put(new File("app.apk"), PACKAGE_NAME);

        mockGetDensityScaleFactorApi(PACKAGE_NAME, CommandStatus.SUCCESS, /* stdout= */ "1.0");
        mockSetDensityScaleFactorApi(PACKAGE_NAME, COMPAT_SCALE, CommandStatus.SUCCESS);

        mPreparer.installer(mTestInfo, appMap);

        verify(mMockDevice).executeShellV2Command(String.format(CMD_GET_DENSITY, PACKAGE_NAME));
        verify(mMockDevice)
                .executeShellV2Command(String.format(CMD_SET_DENSITY, PACKAGE_NAME, COMPAT_SCALE));
    }

    @Test
    public void testInstaller_multiplePackages() throws Exception {
        when(mMockDevice.hasFeature(AUTOMOTIVE_FEATURE)).thenReturn(true);
        Map<File, String> appMap = new HashMap<>();
        appMap.put(new File("app1.apk"), PACKAGE_NAME);
        appMap.put(new File("app2.apk"), PACKAGE_NAME_2);

        mockGetDensityScaleFactorApi(PACKAGE_NAME, CommandStatus.SUCCESS, /* stdout= */ "1.0");
        mockGetDensityScaleFactorApi(PACKAGE_NAME_2, CommandStatus.SUCCESS, /* stdout= */ "1.0");
        mockSetDensityScaleFactorApi(PACKAGE_NAME, COMPAT_SCALE, CommandStatus.SUCCESS);
        mockSetDensityScaleFactorApi(PACKAGE_NAME_2, COMPAT_SCALE, CommandStatus.SUCCESS);

        mPreparer.installer(mTestInfo, appMap);

        verify(mMockDevice).executeShellV2Command(String.format(CMD_GET_DENSITY, PACKAGE_NAME));
        verify(mMockDevice)
                .executeShellV2Command(String.format(CMD_SET_DENSITY, PACKAGE_NAME, COMPAT_SCALE));
        verify(mMockDevice).executeShellV2Command(String.format(CMD_GET_DENSITY, PACKAGE_NAME_2));
        verify(mMockDevice)
                .executeShellV2Command(
                        String.format(CMD_SET_DENSITY, PACKAGE_NAME_2, COMPAT_SCALE));
    }

    @Test
    public void testInstaller_duplicatePackage_skipsExtraSet() throws Exception {
        when(mMockDevice.hasFeature(AUTOMOTIVE_FEATURE)).thenReturn(true);
        Map<File, String> appMap = new HashMap<>();
        appMap.put(new File("app1.apk"), PACKAGE_NAME);
        appMap.put(new File("app2.apk"), PACKAGE_NAME);

        mockGetDensityScaleFactorApi(PACKAGE_NAME, CommandStatus.SUCCESS, /* stdout= */ "1.0");
        mockSetDensityScaleFactorApi(PACKAGE_NAME, COMPAT_SCALE, CommandStatus.SUCCESS);

        mPreparer.installer(mTestInfo, appMap);

        verify(mMockDevice).executeShellV2Command(String.format(CMD_GET_DENSITY, PACKAGE_NAME));
        verify(mMockDevice)
                .executeShellV2Command(String.format(CMD_SET_DENSITY, PACKAGE_NAME, COMPAT_SCALE));
    }

    @Test
    public void testInstaller_getDensityFailure_skipsSet() throws Exception {
        when(mMockDevice.hasFeature(AUTOMOTIVE_FEATURE)).thenReturn(true);
        Map<File, String> appMap = new HashMap<>();
        appMap.put(new File("app.apk"), PACKAGE_NAME);

        mockGetDensityScaleFactorApi(PACKAGE_NAME, CommandStatus.FAILED, /* stdout= */ "error");

        mPreparer.installer(mTestInfo, appMap);

        verify(mMockDevice).executeShellV2Command(String.format(CMD_GET_DENSITY, PACKAGE_NAME));
        verify(mMockDevice, never())
                .executeShellV2Command(String.format(CMD_SET_DENSITY, PACKAGE_NAME, COMPAT_SCALE));
    }

    @Test
    public void testInstaller_setDensityFailure_skipsTearDown() throws Exception {
        when(mMockDevice.hasFeature(AUTOMOTIVE_FEATURE)).thenReturn(true);
        Map<File, String> appMap = new HashMap<>();
        appMap.put(new File("app.apk"), PACKAGE_NAME);

        mockGetDensityScaleFactorApi(PACKAGE_NAME, CommandStatus.SUCCESS, /* stdout= */ "1.0");
        mockSetDensityScaleFactorApi(PACKAGE_NAME, COMPAT_SCALE, CommandStatus.FAILED);

        mPreparer.installer(mTestInfo, appMap);
        mPreparer.tearDown(mTestInfo, null);

        verify(mMockDevice)
                .executeShellV2Command(String.format(CMD_SET_DENSITY, PACKAGE_NAME, COMPAT_SCALE));
        verify(mMockDevice, never())
                .executeShellV2Command(String.format(CMD_SET_DENSITY, PACKAGE_NAME, "1.0"));
    }

    @Test
    public void testTearDown_restore_success() throws Exception {
        when(mMockDevice.hasFeature(AUTOMOTIVE_FEATURE)).thenReturn(true);
        Map<File, String> appMap = new HashMap<>();
        appMap.put(new File("app.apk"), PACKAGE_NAME);

        mockGetDensityScaleFactorApi(PACKAGE_NAME, CommandStatus.SUCCESS, /* stdout= */ "1.0");
        mockSetDensityScaleFactorApi(PACKAGE_NAME, COMPAT_SCALE, CommandStatus.SUCCESS);
        mockSetDensityScaleFactorApi(PACKAGE_NAME, "1.0", CommandStatus.SUCCESS);

        mPreparer.installer(mTestInfo, appMap);
        mPreparer.tearDown(mTestInfo, null);

        verify(mMockDevice)
                .executeShellV2Command(String.format(CMD_SET_DENSITY, PACKAGE_NAME, COMPAT_SCALE));
        verify(mMockDevice)
                .executeShellV2Command(String.format(CMD_SET_DENSITY, PACKAGE_NAME, "1.0"));
    }

    private void mockGetDensityScaleFactorApi(
            String packageName, CommandStatus status, String stdout) throws Exception {
        CommandResult result = new CommandResult(status);
        result.setStdout(stdout);
        result.setExitCode(status == CommandStatus.SUCCESS ? 0 : 1);
        when(mMockDevice.executeShellV2Command(eq(String.format(CMD_GET_DENSITY, packageName))))
                .thenReturn(result);
    }

    private void mockSetDensityScaleFactorApi(
            String packageName, String scale, CommandStatus status) throws Exception {
        CommandResult result = new CommandResult(status);
        result.setStdout("");
        result.setExitCode(status == CommandStatus.SUCCESS ? 0 : 1);
        when(mMockDevice.executeShellV2Command(
                        eq(String.format(CMD_SET_DENSITY, packageName, scale))))
                .thenReturn(result);
    }
}
