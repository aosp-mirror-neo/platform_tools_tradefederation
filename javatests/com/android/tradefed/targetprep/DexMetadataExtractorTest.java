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

import static com.android.tradefed.targetprep.DexMetadataExtractor.CHECK_MIN_SDK_OPTION;
import static com.android.tradefed.targetprep.DexMetadataExtractor.THROW_IF_NOT_FOUND_OPTION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.AaptParser;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;

/** Unit tests for {@link DexMetadataExtractor} */
@RunWith(JUnit4.class)
public class DexMetadataExtractorTest {
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    private static final String SERIAL = "SERIAL";
    private static final String PACKAGE_NAME = "PACKAGE_NAME";
    private static final String APK_NAME = "fakeApk.apk";
    private static final String PROFGEN_NAME = "profgen";

    @Mock IDeviceBuildInfo mMockBuildInfo;
    @Mock ITestDevice mMockTestDevice;
    @Mock private IRunUtil mRunUtil;
    @Mock private AaptParser mMockAaptParser;

    private File mFakeApk;
    private File mFakeProfgen;
    private DexMetadataExtractor mPrep;
    private TestInformation mTestInfo;
    private File mTestDir;
    private File mBuildTestDir;
    private OptionSetter mSetter;

    @Before
    public void setUp() throws Exception {
        mTestDir = FileUtil.createTempDir("DexMetadataExtractor");
        mBuildTestDir = FileUtil.createTempDir("TestAppBuildTestDir");
        mFakeApk = FileUtil.createTempFile("fakeApk", ".apk", mTestDir);
        FileUtil.copyFile(mFakeApk, new File(mTestDir, APK_NAME));
        mFakeApk = new File(mTestDir, APK_NAME);
        mFakeProfgen = FileUtil.createTempFile(PROFGEN_NAME, "", mBuildTestDir);
        FileUtil.copyFile(mFakeProfgen, new File(mBuildTestDir, PROFGEN_NAME));
        mFakeProfgen = new File(mBuildTestDir, PROFGEN_NAME);

        mPrep =
                new DexMetadataExtractor(mRunUtil) {
                    @Override
                    protected File getLocalPathForFilename(
                            TestInformation testInfo, String apkFileName) throws TargetSetupError {
                        if (mFakeApk != null && apkFileName.equals(mFakeApk.getName())) {
                            return mFakeApk;
                        }
                        return null;
                    }

                    @Override
                    protected String parsePackageName(File testAppFile) {
                        return PACKAGE_NAME;
                    }
                };
        mSetter = new OptionSetter(mPrep);
        mSetter.setOptionValue("install-multiple-apks", APK_NAME);

        when(mMockTestDevice.getSerialNumber()).thenReturn(SERIAL);
        when(mMockTestDevice.getOptions()).thenReturn(new TestDeviceOptions());
        when(mMockTestDevice.executeAdbV2Command(ArgumentMatchers.<String>any()))
                .thenReturn(new CommandResult(CommandStatus.SUCCESS));
        when(mMockBuildInfo.getFile(PROFGEN_NAME)).thenReturn(mFakeProfgen);
        when(mRunUtil.runTimedCmd(anyLong(), ArgumentMatchers.<String>any()))
                .thenReturn(new CommandResult(CommandStatus.SUCCESS));

        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockTestDevice);
        context.addDeviceBuildInfo("device", mMockBuildInfo);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mTestDir);
        FileUtil.recursiveDelete(mBuildTestDir);
    }

    @Test
    public void testSetupAndTeardown() throws Exception {
        mPrep.setUp(mTestInfo);

        verify(mRunUtil, times(2)).runTimedCmd(anyLong(), ArgumentMatchers.<String>any());
        verify(mMockTestDevice, times(1)).executeAdbV2Command(ArgumentMatchers.<String>any());
    }

    @Test
    public void testSetupAndTeardownWithUserId() throws Exception {
        mPrep.setUserId(10);
        mPrep.setUp(mTestInfo);

        verify(mRunUtil, times(2)).runTimedCmd(anyLong(), ArgumentMatchers.<String>any());
        verify(mMockTestDevice, times(1)).executeAdbV2Command(ArgumentMatchers.<String>any());
    }

    /**
     * Test {@link DexMetadataExtractor#setUp(TestInformation)} with a missing apk.
     *
     * @throws Exception the expected exception
     */
    @Test
    public void testMissingApk() throws Exception {
        mFakeApk = null; // Apk doesn't exist

        TargetSetupError thrown =
                assertThrows(TargetSetupError.class, () -> mPrep.setUp(mTestInfo));

        assertTrue(
                "DexMetadataExtractor#setUp() did not raise TargetSetupError with missing apk.",
                thrown.getMessage().contains("not found"));
    }

    /**
     * Test {@link DexMetadataExtractor#setUp(TestInformation)} with an unreadable apk.
     *
     * @throws Exception the expected exception
     */
    @Test
    public void testUnreadableApk() throws Exception {
        mFakeApk.delete(); // Apk cannot be read

        TargetSetupError thrown =
                assertThrows(TargetSetupError.class, () -> mPrep.setUp(mTestInfo));

        assertTrue(
                "DexMetadataExtractor#setUp() did not raise TargetSetupError with unreadable apk.",
                thrown.getMessage().contains("not read"));
    }

    /**
     * Test {@link DexMetadataExtractor#setUp(TestInformation)} with a missing profgen build
     * artifact.
     *
     * @throws Exception the expected exception
     */
    @Test
    public void testMissingProfgen() throws Exception {
        mFakeProfgen = null; // Profgen doesn't exist
        when(mMockBuildInfo.getFile(PROFGEN_NAME)).thenReturn(mFakeProfgen);

        TargetSetupError thrown =
                assertThrows(TargetSetupError.class, () -> mPrep.setUp(mTestInfo));

        assertTrue(
                "DexMetadataExtractor#setUp() did not raise TargetSetupError with missing profgen"
                        + " build artifact.",
                thrown.getMessage().contains("not included"));
    }

    /**
     * Test {@link DexMetadataExtractor#setUp(TestInformation)} with an unreadable profgen build
     * artifact.
     *
     * @throws Exception the expected exception
     */
    @Test
    public void testUnreadableProfgen() throws Exception {
        mFakeProfgen.delete(); // Profgen cannot be read

        TargetSetupError thrown =
                assertThrows(TargetSetupError.class, () -> mPrep.setUp(mTestInfo));

        assertTrue(
                "DexMetadataExtractor#setUp() did not raise TargetSetupError with unreadable"
                        + " profgen build artifact.",
                thrown.getMessage().contains("cannot be read"));
    }

    /**
     * Test {@link DexMetadataExtractor#setUp(TestInformation)} with a missing apk and
     * ThrowIfNoFile=False. Silent skip expected.
     *
     * @throws Exception the expected exception
     */
    @Test
    public void testMissingApk_silent() throws Exception {
        mFakeApk = null; // Apk doesn't exist
        mSetter.setOptionValue(THROW_IF_NOT_FOUND_OPTION, "false");

        mPrep.setUp(mTestInfo);

        verify(mRunUtil, times(1)).runTimedCmd(anyLong(), ArgumentMatchers.<String>any());
    }

    /**
     * Test {@link DexMetadataExtractor#setUp(TestInformation)} with an unreadable apk and
     * ThrowIfNoFile=False. Silent skip expected.
     *
     * @throws Exception the expected exception
     */
    @Test
    public void testUnreadableApk_silent() throws Exception {
        mFakeApk.delete(); // Apk cannot be read
        mSetter.setOptionValue(THROW_IF_NOT_FOUND_OPTION, "false");

        mPrep.setUp(mTestInfo);

        verify(mRunUtil, times(1)).runTimedCmd(anyLong(), ArgumentMatchers.<String>any());
    }

    /**
     * Tests that we throw exception with check-min-sdk when file fails to parse
     *
     * @throws Exception the expected exception
     */
    @Test
    public void testResolveApkFile_checkMinSdk_failParsing() throws Exception {
        mPrep =
                new DexMetadataExtractor(mRunUtil) {
                    @Override
                    AaptParser doAaptParse(File apkFile) {
                        return null;
                    }
                };
        OptionSetter setter = new OptionSetter(mPrep);
        setter.setOptionValue(CHECK_MIN_SDK_OPTION, "true");

        TargetSetupError thrown =
                assertThrows(
                        TargetSetupError.class, () -> mPrep.resolveApkFile(mTestInfo, mFakeApk));

        assertEquals(
                String.format("Failed to extract info from `%s` using aapt2", mFakeApk.getName()),
                thrown.getMessage());
    }

    /**
     * Tests that we don't include the file if api level too low
     *
     * @throws Exception the expected exception
     */
    @Test
    public void testResolveApkFile_checkMinSdk_apiLow() throws Exception {
        mPrep =
                new DexMetadataExtractor(mRunUtil) {
                    @Override
                    AaptParser doAaptParse(File apkFile) {
                        return mMockAaptParser;
                    }

                    @Override
                    protected String parsePackageName(File testAppFile) {
                        return PACKAGE_NAME;
                    }
                };
        OptionSetter setter = new OptionSetter(mPrep);
        setter.setOptionValue(CHECK_MIN_SDK_OPTION, "true");
        when(mMockTestDevice.getSerialNumber()).thenReturn(SERIAL);
        when(mMockTestDevice.getApiLevel()).thenReturn(21);
        doReturn(22).when(mMockAaptParser).getSdkVersion();

        File expected = null;
        File result = mPrep.resolveApkFile(mTestInfo, mFakeApk);

        verify(mMockTestDevice, times(2)).getApiLevel();
        verify(mMockAaptParser, times(2)).getSdkVersion();
        assertEquals(expected, result);
    }

    /**
     * Tests that we include the file if level is appropriate
     *
     * @throws Exception the expected exception
     */
    @Test
    public void testResolveApkFile_checkMinSdk_apiOk() throws Exception {
        mPrep =
                new DexMetadataExtractor(mRunUtil) {
                    @Override
                    AaptParser doAaptParse(File apkFile) {
                        return mMockAaptParser;
                    }

                    @Override
                    protected String parsePackageName(File testAppFile) {
                        return PACKAGE_NAME;
                    }
                };
        OptionSetter setter = new OptionSetter(mPrep);
        setter.setOptionValue(CHECK_MIN_SDK_OPTION, "true");
        when(mMockTestDevice.getSerialNumber()).thenReturn(SERIAL);
        when(mMockTestDevice.getApiLevel()).thenReturn(23);
        doReturn(22).when(mMockAaptParser).getSdkVersion();

        File expected = mFakeApk;
        File result = mPrep.resolveApkFile(mTestInfo, mFakeApk);

        verify(mMockAaptParser).getSdkVersion();
        assertEquals(expected, result);
    }
}
