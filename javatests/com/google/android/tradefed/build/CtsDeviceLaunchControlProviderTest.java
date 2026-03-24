// Copyright 2010 Google Inc. All Rights Reserved.
package com.google.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.host.HostOptions;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.net.IHttpHelper;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

/**
 * Unit tests for {@link CtsDeviceLaunchControlProvider}.
 */
@RunWith(JUnit4.class)
public class CtsDeviceLaunchControlProviderTest {

    private static final String ANDROID_INFO_FILE_NAME = "android-info.txt";
    private static final int BUILD_ID = 4;
    private static final String CTS_PATH = "git_ics-release-linux-cts/168183/android-cts.zip";
    private static final String DEVICE_IMAGE_PATH = "image.zip";
    private static final String DEVICE_TEST_PATH = "test.zip";
    private static final String CTS_QUERY_RESPONSE = String.format("bid:%d\n%s:%s\nfiles:%s",
            BUILD_ID, BuildAttributeKey.CTS.getRemoteValue(), CTS_PATH, CTS_PATH);
    // a mock device build server response, that just uses same file path for all build artifacts
    private static final String DEVICE_QUERY_RESPONSE = String.format(
            "bid:%d\n%s:%s\n%s:%s\n",
            BUILD_ID,
            BuildAttributeKey.DEVICE_IMAGE.getRemoteValue(), DEVICE_IMAGE_PATH,
            BuildAttributeKey.TESTS_ZIP.getRemoteValue(), DEVICE_TEST_PATH);
    private static final String NO_BUILD_QUERY_RESPONSE = "";
    private static final String TAG = "tag";
    private static final String DEVICE_BUILD_FLAVOR = "device-flavor";
    private static final String BRANCH = "main";
    private static final String BASEBAND_VERSION = "baseband";
    private static final String BOOTLOADER_VERSION = "bootloader";
    private static final String ANDROID_INFO = String.format("require board=board1|board2\n" +
            "require version-bootloader=%s\n" +
            "require version-baseband=%s\n", BOOTLOADER_VERSION, BASEBAND_VERSION);

    private IHttpHelper mMockHttpHelper;
    private IFileDownloader mMockDownloader;
    private AndroidBuildArtifactHelper mMockAbHelper;
    private CtsDeviceLaunchControlProvider mProvider;
    private File mMockFile;
    private File mStubImageFile = null;
    private File mTempDir = null;
    private File mAndroidInfo = null;
    private File mLcCacheDir;
    private IConfiguration mConfig;

    @Before
    public void setUp() throws Exception {
        mConfig = new Configuration("test", "test");
        mTempDir = FileUtil.createTempDir("test");
        mAndroidInfo = new File(mTempDir, ANDROID_INFO_FILE_NAME);
        FileUtil.writeToFile(ANDROID_INFO, mAndroidInfo);
        mStubImageFile = new File(mTempDir, "image.zip");
        ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(mStubImageFile));
        ZipUtil.addToZip(zipOutput, mAndroidInfo, new LinkedList<String>());
        StreamUtil.close(zipOutput);
        mMockHttpHelper = Mockito.mock(IHttpHelper.class);
        mMockDownloader = Mockito.mock(IFileDownloader.class);
        mMockAbHelper = Mockito.mock(AndroidBuildArtifactHelper.class);

        mLcCacheDir = FileUtil.createTempDir("dlcp-unit-test");
        HostOptions hostOptions = new HostOptions();
        OptionSetter setter = new OptionSetter(hostOptions);
        setter.setOptionValue("host_options:download-cache-dir", mLcCacheDir.getAbsolutePath());

        mProvider =
                new CtsDeviceLaunchControlProvider() {
                    @Override
                    protected IHttpHelper createHttpHelper() {
                        return mMockHttpHelper;
                    }

                    @Override
                    public IFileDownloader createLaunchControlDownloader(RemoteBuildInfo info) {
                        return mMockDownloader;
                    }

                    @Override
                    IHostOptions getHostOptions() {
                        return hostOptions;
                    }

                    @Override
                    protected AndroidBuildArtifactHelper getBuildArtifactHelper()
                            throws BuildRetrievalError {
                        return mMockAbHelper;
                    }

                    @Override
                    protected Set<String> getGlobalDownloadExclusionPatterns() {
                        return new HashSet<>();
                    }

                    @Override
                    CtsLaunchControlProvider createCtsLcProvider() {
                        return new CtsLaunchControlProvider() {
                            @Override
                            protected IHttpHelper createHttpHelper() {
                                return mMockHttpHelper;
                            }

                            @Override
                            public IFileDownloader createLaunchControlDownloader(
                                    RemoteBuildInfo info) {
                                return mMockDownloader;
                            }

                            @Override
                            File extractZip(File ctsZip, String name) {
                                // overridden because parent method requires that the ctsZip file be
                                // a valid zip file
                                return mTempDir;
                            }

                            @Override
                            IHostOptions getHostOptions() {
                                return hostOptions;
                            }

                            @Override
                            protected AndroidBuildArtifactHelper getBuildArtifactHelper()
                                    throws BuildRetrievalError {
                                return mMockAbHelper;
                            }

                            @Override
                            protected Set<String> getGlobalDownloadExclusionPatterns() {
                                return new HashSet<>();
                            }
                        };
                    }

                    @Override
                    File extractZip(File testsZip, String name) throws IOException, ZipException {
                        return new File("/tmp/somepath");
                    }
                };
        mProvider.setBranch(BRANCH);
        mProvider.setBuildFlavor(DEVICE_BUILD_FLAVOR);
        mProvider.setTestTag(TAG);
        mProvider.skipDownload(BuildAttributeKey.MKBOOTIMG);
        mProvider.skipDownload(BuildAttributeKey.RAMDISK);
        mProvider.setConfiguration(mConfig);
        mProvider.setInvocationContext(new InvocationContext());
        mMockFile = new File("fakefilethatdoesntmatter");
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mTempDir);
        FileUtil.recursiveDelete(mLcCacheDir);
    }

    /** Test normal case for {@link CtsDeviceLaunchControlProvider#getBuild()}. */
    @Test
    public void testGetBuild() throws Exception {
        RemoteBuildInfo deviceRemote = RemoteBuildInfo.parseRemoteBuildInfo(DEVICE_QUERY_RESPONSE);
        doReturn(deviceRemote)
                .when(mMockAbHelper)
                .queryForLatestBuild(BRANCH, DEVICE_BUILD_FLAVOR, "linux");

        RemoteBuildInfo ctsRemote = RemoteBuildInfo.parseRemoteBuildInfo(CTS_QUERY_RESPONSE);
        doReturn(ctsRemote)
                .when(mMockAbHelper)
                .queryForBuildById(BRANCH, "4", "cts", "linux", null, new LinkedHashSet<>());

        when(mMockDownloader.downloadFile(CTS_PATH)).thenReturn(mMockFile);
        // expect download of device image and tests zip
        when(mMockDownloader.downloadFile(DEVICE_IMAGE_PATH)).thenReturn(mStubImageFile);
        when(mMockDownloader.downloadFile(DEVICE_TEST_PATH)).thenReturn(mMockFile);
        IBuildInfo build = mProvider.getBuild();
        assertTrue(build instanceof IFolderBuildInfo);
        assertTrue(build instanceof IDeviceBuildInfo);

        assertEquals(mStubImageFile, ((IDeviceBuildInfo) build).getDeviceImageFile());
    }

    /**
     * Test {@link CtsDeviceLaunchControlProvider#getBuild()} when there is no device build
     * available for testing.
     */
    @Test
    public void testGetBuild_noDeviceBuild() throws Exception {
        RemoteBuildInfo deviceRemote =
                RemoteBuildInfo.parseRemoteBuildInfo(NO_BUILD_QUERY_RESPONSE);
        doReturn(deviceRemote)
                .when(mMockAbHelper)
                .queryForLatestBuild(BRANCH, DEVICE_BUILD_FLAVOR, "linux");

        assertNull(mProvider.getBuild());
    }

    /**
     * Test {@link CtsDeviceLaunchControlProvider#getBuild()} when there is a device build, but no
     * CTS build
     */
    @Test
    public void testGetBuild_noCtsBuild() throws Exception {
        RemoteBuildInfo deviceRemote = RemoteBuildInfo.parseRemoteBuildInfo(DEVICE_QUERY_RESPONSE);
        doReturn(deviceRemote)
                .when(mMockAbHelper)
                .queryForLatestBuild(BRANCH, DEVICE_BUILD_FLAVOR, "linux");

        RemoteBuildInfo ctsRemote = RemoteBuildInfo.parseRemoteBuildInfo(NO_BUILD_QUERY_RESPONSE);
        doReturn(ctsRemote)
                .when(mMockAbHelper)
                .queryForBuildById(BRANCH, "4", "cts", "linux", null, new LinkedHashSet<>());

        assertNull(mProvider.getBuild());
    }

    /** Test {@link CtsDeviceLaunchControlProvider#getBuild()} when download of CTS build fails. */
    @Test
    public void testGetBuild_ctsDownloadFailed() throws Exception {
        RemoteBuildInfo deviceRemote = RemoteBuildInfo.parseRemoteBuildInfo(DEVICE_QUERY_RESPONSE);
        doReturn(deviceRemote)
                .when(mMockAbHelper)
                .queryForLatestBuild(BRANCH, DEVICE_BUILD_FLAVOR, "linux");

        RemoteBuildInfo ctsRemote = RemoteBuildInfo.parseRemoteBuildInfo(CTS_QUERY_RESPONSE);
        doReturn(ctsRemote)
                .when(mMockAbHelper)
                .queryForBuildById(BRANCH, "4", "cts", "linux", null, new LinkedHashSet<>());
        // expect download of device image, and tests_zip
        when(mMockDownloader.downloadFile(DEVICE_IMAGE_PATH)).thenReturn(mStubImageFile);
        when(mMockDownloader.downloadFile(DEVICE_TEST_PATH)).thenReturn(mMockFile);
        when(mMockDownloader.downloadFile(CTS_PATH))
                .thenThrow(
                        new BuildRetrievalError(
                                "error", InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR));
        try {
            mProvider.getBuild();
            fail("BuildRetrievalError not called");
        } catch (BuildRetrievalError e) {
            // expected
        }
    }

    /**
     * Test {@link CtsDeviceLaunchControlProvider#getBuild()} when download of device build fails.
     */
    @Test
    public void testGetBuild_deviceDownloadFailed() throws Exception {
        RemoteBuildInfo deviceRemote = RemoteBuildInfo.parseRemoteBuildInfo(DEVICE_QUERY_RESPONSE);
        doReturn(deviceRemote)
                .when(mMockAbHelper)
                .queryForLatestBuild(BRANCH, DEVICE_BUILD_FLAVOR, "linux");

        RemoteBuildInfo ctsRemote = RemoteBuildInfo.parseRemoteBuildInfo(CTS_QUERY_RESPONSE);
        doReturn(ctsRemote)
                .when(mMockAbHelper)
                .queryForBuildById(BRANCH, "4", "cts", "linux", null, new LinkedHashSet<>());
        // expect download of device image, tests zip, and userdata
        when(mMockDownloader.downloadFile(DEVICE_IMAGE_PATH))
                .thenThrow(
                        new BuildRetrievalError(
                                "error", InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR));
        try {
            mProvider.getBuild();
            fail("BuildRetrievalError not called");
        } catch (BuildRetrievalError e) {
            // expected
        }
    }
}
