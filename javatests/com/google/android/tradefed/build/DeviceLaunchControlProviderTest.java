// Copyright 2010 Google Inc. All Rights Reserved.

package com.google.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.VersionedFile;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.host.HostOptions;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.net.IHttpHelper;
import com.android.tradefed.util.net.IHttpHelper.DataSizeException;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Unit tests for {@link DeviceLaunchControlProvider} and by extension {@link LaunchControlProvider}
 */
@RunWith(JUnit4.class)
public class DeviceLaunchControlProviderTest {

    private static final String ANDROID_INFO_FILE_NAME = "android-info.txt";
    private static final String TEST_QUERY_RESULT = String.format("%s:4\n%s:filepath\nrc:AA3",
            BuildAttributeKey.BUILD_ID.getRemoteValue(),
            BuildAttributeKey.DEVICE_IMAGE.getRemoteValue());
    private static final String TEST_QUERY_RESULT_WITH_FILE =
            String.format(
                    "%s:4\n%s:filepath\n%s:foo,bar\nrc:AA3",
                    BuildAttributeKey.BUILD_ID.getRemoteValue(),
                    BuildAttributeKey.DEVICE_IMAGE.getRemoteValue(),
                    BuildAttributeKey.FILES.getRemoteValue());
    private static final String BUILD_DETAIL_RESULT = String.format(
            "%s:git_mnc-release\n%s:2219288\nrc:mra58e\ntags:",
            BuildAttributeKey.BRANCH.getRemoteValue(),
            BuildAttributeKey.BUILD_ID.getRemoteValue());

    private static final String BASEBAND_VERSION = "baseband";
    private static final String BOOTLOADER_VERSION = "bootloader";
    private static final String TRUSTY_VERSION = "trusty-1";
    private static final String ANDROID_INFO = String.format("require board=board1|board2\n" +
            "require version-bootloader=%s\n" +
            "require version-baseband=%s\n" +
            "require version-trusty=%s\n", BOOTLOADER_VERSION, BASEBAND_VERSION, TRUSTY_VERSION);
    private static final String TEST_TAG = "tag";
    private static final QueryType TEST_QUERY = QueryType.LATEST_GREEN_BUILD;
    private static final String TEST_TARGET = "passion";
    private static final String TEST_BRANCH = "main";

    private DeviceLaunchControlProvider mLaunchControlProvider;
    private IHttpHelper mMockHttpHelper;
    private AndroidBuildArtifactHelper mMockAbHelper;
    private IFileDownloader mMockDownloader;
    private File mStubImageFile = null;
    private File mTempDir = null;
    private File mAndroidInfo = null;
    private File mLcCacheDir = null;
    private IConfiguration mConfig = null;
    private List<String> mRemoteBuildFiles = null;

    @BeforeClass
    public static void preSetUp() throws ConfigurationException {
        try {
            // When running in IDE, global configuration might not be initialized.
            GlobalConfiguration.createGlobalConfiguration(new String[] {"empty"});
        } catch (IllegalStateException e) {
            // Ignore
        }
    }

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
        mRemoteBuildFiles = new ArrayList<>(Arrays.asList("tzsw.img"));

        mLcCacheDir = FileUtil.createTempDir("dlcp-unit-test");
        HostOptions hostOptions = new HostOptions();
        OptionSetter setter = new OptionSetter(hostOptions);
        setter.setOptionValue("host_options:download-cache-dir", mLcCacheDir.getAbsolutePath());

        mLaunchControlProvider =
                new DeviceLaunchControlProvider() {
                    @Override
                    protected IHttpHelper createHttpHelper() {
                        return mMockHttpHelper;
                    }

                    @Override
                    public IFileDownloader createLaunchControlDownloader(RemoteBuildInfo info) {
                        return mMockDownloader;
                    }

                    @Override
                    String getUrl() {
                        return "fakeUrl";
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
        mLaunchControlProvider.setBranch(TEST_BRANCH);
        mLaunchControlProvider.setBuildFlavor(TEST_TARGET);
        mLaunchControlProvider.setQueryType(TEST_QUERY);
        mLaunchControlProvider.setTestTag(TEST_TAG);
        mLaunchControlProvider.setConfiguration(mConfig);
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mTempDir);
        FileUtil.recursiveDelete(mLcCacheDir);
    }

    /**
     * Configure the mock downloader to simulate any number of successful download file attempts
     */
    private void setDownloadSuccessExpectations() throws Exception {
        when(mMockDownloader.downloadFile("filepath"))
                .thenReturn(mStubImageFile)
                .thenReturn(new File("fakefilethatdoesntmatter"));
        when(mMockDownloader.downloadFile(Mockito.eq("tzsw.img")))
                .thenReturn(new File("fakeTrusty"));
    }

    /**
     * Create a fake launch control response string from {@code mRemoteBuildFiles}.
     * If {@code mRemoteBuildFiles} is empty, the response will not contain the FILES attribute.
     */
    private String makeLaunchControlResponse() throws Exception {
        String baseResponse = String.format(
                "%s:4\n%s:filepath\nrc:AA3",
                BuildAttributeKey.BUILD_ID.getRemoteValue(),
                BuildAttributeKey.DEVICE_IMAGE.getRemoteValue());
        if (mRemoteBuildFiles.isEmpty()) {
            return baseResponse;
        }
        return String.format(
                "%s\n%s:%s",
                baseResponse,
                BuildAttributeKey.FILES.getRemoteValue(),
                String.join(",", mRemoteBuildFiles));
    }

    /** Test normal success case for {@link LaunchControlProvider#getBuild()}. */
    @Test
    public void testGetBuild() throws Exception {
        setDownloadSuccessExpectations();
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        doReturn(remoteBuild)
                .when(mMockAbHelper)
                .queryForLatestBuild(TEST_BRANCH, TEST_TARGET, "linux");

        IBuildInfo info = mLaunchControlProvider.getBuild();
        assertNotNull(info);
        assertEquals("4", info.getBuildId());
        assertEquals(TEST_BRANCH, info.getBuildBranch());
        assertEquals(TEST_TARGET, info.getBuildFlavor());
        assertEquals("AA3", info.getBuildAttributes().get("build_alias"));
    }

    /** Test that files obtained from the dynamic option field they are copied to the build info. */
    @Test
    public void testGetBuild_dynamicDownloads() throws Exception {
        File tmpFile = FileUtil.createTempFile("dynamic-lcp", "test");
        try {
            OptionSetter setter = new OptionSetter(mLaunchControlProvider);
            setter.setOptionValue("extra-target-downloads", "key", tmpFile.getAbsolutePath());

            setDownloadSuccessExpectations();
            RemoteBuildInfo remoteBuild =
                    RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
            doReturn(remoteBuild)
                    .when(mMockAbHelper)
                    .queryForLatestBuild(TEST_BRANCH, TEST_TARGET, "linux");
            IBuildInfo info = mLaunchControlProvider.getBuild();
            assertNotNull(info);
            assertEquals("4", info.getBuildId());
            assertEquals(TEST_BRANCH, info.getBuildBranch());
            assertEquals(TEST_TARGET, info.getBuildFlavor());
            assertEquals("AA3", info.getBuildAttributes().get("build_alias"));
            // File is now present in the build info
            assertEquals(tmpFile, info.getFile("key"));
        } finally {
            FileUtil.recursiveDelete(tmpFile);
        }
    }

    /**
     * Test that files obtained from the dynamic option field they are copied to the build info and
     * keys that support multiple files also are properly populated.
     */
    @Test
    public void testGetBuild_dynamicDownloads_multiFile() throws Exception {
        File tmpFile = FileUtil.createTempFile("dynamic-lcp", "test");
        File tmpFile2 = FileUtil.createTempFile("dynamic2-lcp", "test");
        try {
            OptionSetter setter = new OptionSetter(mLaunchControlProvider);
            // If we use a key that support multi files.
            setter.setOptionValue(
                    "extra-target-downloads",
                    BuildInfoFileKey.PACKAGE_FILES.getFileKey(),
                    tmpFile.getAbsolutePath());
            setter.setOptionValue(
                    "extra-target-downloads",
                    BuildInfoFileKey.PACKAGE_FILES.getFileKey(),
                    tmpFile2.getAbsolutePath());

            setDownloadSuccessExpectations();
            RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
            doReturn(remoteBuild)
                    .when(mMockAbHelper)
                    .queryForLatestBuild(TEST_BRANCH, TEST_TARGET, "linux");
            IBuildInfo info = mLaunchControlProvider.getBuild();
            assertNotNull(info);
            assertEquals("4", info.getBuildId());
            assertEquals(TEST_BRANCH, info.getBuildBranch());
            assertEquals(TEST_TARGET, info.getBuildFlavor());
            assertEquals("AA3", info.getBuildAttributes().get("build_alias"));
            // File is now present in the build info
            List<VersionedFile> files = info.getVersionedFiles(BuildInfoFileKey.PACKAGE_FILES);
            // We find them in reverse orders to match the last-win behavior
            assertEquals(tmpFile2, files.get(0).getFile());
            assertEquals(tmpFile, files.get(1).getFile());
        } finally {
            FileUtil.recursiveDelete(tmpFile);
            FileUtil.recursiveDelete(tmpFile2);
        }
    }

    /**
     * Test {@link LaunchControlProvider#getBuild()} when a build id is provided wirth no query
     * type.
     *
     * <p>Expect queryType to be override to {@link QueryType#NOTIFY_TEST_BUILD}.
     */
    @Test
    public void testGetBuild_buildId() throws Exception {
        setDownloadSuccessExpectations();
        mLaunchControlProvider.setBuildId("4");
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        doReturn(remoteBuild)
                .when(mMockAbHelper)
                .queryForBuildById(
                        TEST_BRANCH, "4", TEST_TARGET, "linux", null, new LinkedHashSet<>());
        IBuildInfo info = mLaunchControlProvider.getBuild();
        assertNotNull(info);
        assertEquals("4", info.getBuildId());
        assertEquals(TEST_BRANCH, info.getBuildBranch());
        assertEquals(TEST_TARGET, info.getBuildFlavor());
    }

    /** Test {@link LaunchControlProvider#getBuild()} behavior when branch is not specified. */
    @Test
    public void testGetBuild_noBranch() throws Exception {
        mLaunchControlProvider.setBranch(null);
        try {
            mLaunchControlProvider.getBuild();
            fail("did not throw IllegalArgumentException");
        } catch (HarnessRuntimeException expected) {
            // expected
        }
    }

    /** Test {@link LaunchControlProvider#getBuild()} behavior when target is not specified. */
    @Test
    public void testGetBuild_noTarget() throws Exception {
        mLaunchControlProvider.setBuildFlavor(null);
        try {
            mLaunchControlProvider.getBuild();
            fail("did not throw BuildRetrievalError");
        } catch (BuildRetrievalError expected) {
            // expected
        }
    }

    /** Test {@link LaunchControlProvider#getBuild()} behavior when test tag is not specified. */
    @Test
    public void testGetBuild_noTag() throws Exception {
        mLaunchControlProvider.setTestTag(null);
        mLaunchControlProvider.setUseBuildApi(false);
        try {
            mLaunchControlProvider.getBuild();
            fail("did not throw IllegalArgumentException");
        } catch (HarnessRuntimeException expected) {
            // expected
        }
    }

    /**
     * Test {@link LaunchControlProvider#getBuild()} when {@link IOException} is thrown when
     * contacting server.
     */
    @Test
    public void testGetBuild_IOException() throws Exception {
        mLaunchControlProvider.setUseBuildApi(false);
        when(
                        mMockHttpHelper.buildUrl(
                                (String) Mockito.any(), Mockito.any()))
                .thenReturn("");
        doThrow(new IOException()).when(mMockHttpHelper).doGetWithRetry((String)Mockito.any());
        try {
            mLaunchControlProvider.getBuild();
            fail("did not throw BuildRetrievalError");
        } catch (BuildRetrievalError e) {
            // expected
        }
    }

    /**
     * Test {@link LaunchControlProvider#getBuild()} when {@link DataSizeException} is thrown when
     * contacting server.
     */
    @Test
    public void testGetBuild_DataSizeException() throws Exception {
        mLaunchControlProvider.setUseBuildApi(false);
        when(
                        mMockHttpHelper.buildUrl(
                                (String) Mockito.any(), Mockito.any()))
                .thenReturn("");
        doThrow(new DataSizeException()).when(mMockHttpHelper)
                .doGetWithRetry((String)Mockito.any());
        try {
            mLaunchControlProvider.getBuild();
            fail("did not throw BuildRetrievalError");
        } catch (BuildRetrievalError e) {
            // expected
        }
    }

    /** Test {@link LaunchControlProvider#getBuild()} when a min build id has been provided */
    @Test
    public void testGetBuild_minBuildId() throws Exception {
        // set min build id > build id returned in Launch Control response.
        mLaunchControlProvider.setMinBuildId(5);
        when(mMockHttpHelper.buildUrl((String) Mockito.any(), Mockito.any())).thenReturn("");
        when(mMockHttpHelper.doGetWithRetry((String) Mockito.any()))
                .thenReturn(makeLaunchControlResponse());
        assertNull(mLaunchControlProvider.getBuild());
    }

    /**
     * Test {@link LaunchControlProvider#getBuild()} when downloading of files fails due to a {@link
     * BuildRetrievalError}
     */
    @Test
    public void testGetBuild_downloadFailed() throws Exception {
        setGetBuildDownloadFailedExpectations(
                new BuildRetrievalError("error", InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR));
        try {
            mLaunchControlProvider.getBuild();
            fail("BuildRetrievalError not thrown");
        } catch (BuildRetrievalError e) {
            // expected
        }
    }

    /**
     * Test {@link DeviceLaunchControlProvider#downloadBuildFiles(RemoteBuildInfo, String, String,
     * IFileDownloader)} when {@link RemoteBuildInfo} has no device image.
     */
    @Test
    public void testDownloadBuildFiles_noDeviceImage() throws Exception {
        try {
            String queryResultNoDeviceImage = String.format("%s:4\nrc:AA3",
                    BuildAttributeKey.BUILD_ID.getRemoteValue());
            RemoteBuildInfo remoteBuild = RemoteBuildInfo.parseRemoteBuildInfo(
                    queryResultNoDeviceImage);
            mLaunchControlProvider.downloadBuildFiles(
                    remoteBuild, "target", "build", mMockDownloader);
            fail("BuildRetrievalError not thrown");
        } catch (BuildRetrievalError e) {
            // expected
        }
    }

    /** Make sure that download skipping works as expected. */
    @Test
    public void testSkipDownload() throws Exception {
        final BuildAttributeKey key = BuildAttributeKey.USER_DATA;
        mLaunchControlProvider.addDownloadKey(key);
        assertTrue(mLaunchControlProvider.shouldDownload(key));
        mLaunchControlProvider.skipDownload(key);
        assertFalse(mLaunchControlProvider.shouldDownload(key));
    }

    /** Make sure that we don't attempt to download files that aren't specified to download. */
    @Test
    public void testAddDownloadKey() throws Exception {
        final BuildAttributeKey key = BuildAttributeKey.USER_DATA;
        mLaunchControlProvider.getDownloadKeys().clear();
        assertFalse(mLaunchControlProvider.shouldDownload(key));
        mLaunchControlProvider.addDownloadKey(key);
        assertTrue(mLaunchControlProvider.shouldDownload(key));
    }

    /**
     * Make sure that by default, we attempt to download the default keys, and don't attempt to
     * download the keys that aren't mentioned.
     */
    @Test
    public void testDefaults() throws Exception {
        Collection<BuildAttributeKey> downloadKeys = mLaunchControlProvider.getDownloadKeys();

        for (BuildAttributeKey key : BuildAttributeKey.values()) {
            assertEquals(downloadKeys.contains(key), mLaunchControlProvider.shouldDownload(key));
        }
    }

    /**
     * Configure EasyMock expectations to simulate a {@link LaunchControlProvider#getBuild()} call
     * that fails due to a {@link IFileDownloader#downloadFile(String)} exception
     *
     * @param downloadException the {@link Exception} that {@link
     *     IFileDownloader#downloadFile(String)} should throw
     */
    private void setGetBuildDownloadFailedExpectations(Exception downloadException)
            throws Exception {
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        doReturn(remoteBuild)
                .when(mMockAbHelper)
                .queryForLatestBuild(TEST_BRANCH, TEST_TARGET, "linux");

        when(mMockDownloader.downloadFile((String)Mockito.any())).thenThrow(
                downloadException);
    }

    /**
     * Verifies that resolveBuildInfoFromDevice will override query type to notify test CL if device
     * has a valid numeric build id
     *
     * @throws Exception
     */
    @Test
    public void testResolveBuildInfoFromDevice_NotifyTestCl() throws Exception {
        mLaunchControlProvider.setUseBuildApi(false);
        ITestDevice device = Mockito.mock(ITestDevice.class);
        when(device.getBuildId()).thenReturn("2219288");
        when(device.getBuildFlavor()).thenReturn(null);
        when(
                        mMockHttpHelper.buildUrl(
                                (String) Mockito.any(), Mockito.any()))
                .thenReturn("");
        when(mMockHttpHelper.doGetWithRetry((String)Mockito.any())).thenReturn(BUILD_DETAIL_RESULT);

        mLaunchControlProvider.resolveBuildInfoFromDevice(device);
        assertEquals(QueryType.NOTIFY_TEST_BUILD, mLaunchControlProvider.getQueryType());
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#resolveBuildInfoFromDevice(ITestDevice)}
     * throws a clear exception when the server cannot be reached (empty response from LCP).
     */
    @Test
    public void testResolveBuildInfoFromDevice_Failed() throws Exception {
        mLaunchControlProvider.setUseBuildApi(false);
        ITestDevice device = Mockito.mock(ITestDevice.class);
        when(device.getBuildId()).thenReturn("2219288");
        when(
                        mMockHttpHelper.buildUrl(
                                (String) Mockito.any(), Mockito.any()))
                .thenReturn("");
        // Server respond with empty
        when(mMockHttpHelper.doGetWithRetry((String) Mockito.any())).thenReturn("");
        try {
            mLaunchControlProvider.resolveBuildInfoFromDevice(device);
            fail("Should have thrown an exception.");
        } catch (BuildRetrievalError expected) {
            assertEquals(
                    "Could not reach Android Build to look up the build info. Please check that "
                            + "you have access to atp-manager.googleplex.com or that the build id"
                            + " is valid.",
                    expected.getMessage());
        }
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#resolveBuildInfoFromDevice(ITestDevice)}
     * will override query type to query latest build if device has an eng build
     *
     * @throws Exception
     */
    @Test
    public void testResolveBuildInfoFromDevice_QueryLatest() throws Exception {
        ITestDevice device = Mockito.mock(ITestDevice.class);
        when(device.getBuildId()).thenReturn("ABCDEFG");

        mLaunchControlProvider.resolveBuildInfoFromDevice(device);
        assertEquals(QueryType.QUERY_LATEST_BUILD, mLaunchControlProvider.getQueryType());
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will try to download
     * bootloader image.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBootloader() throws Exception {
        when(mMockDownloader.downloadFile(Mockito.any()))
                .thenReturn(mStubImageFile)
                .thenReturn(new File("fakefilethatdoesntmatter"));
        mRemoteBuildFiles.add("bootloader.img");
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        IDeviceBuildInfo info = (IDeviceBuildInfo) mLaunchControlProvider.downloadBuildFiles(
                remoteBuild, "target", "build", mMockDownloader);
        assertNotNull(info);
        assertNotNull(info.getBootloaderImageFile());
        assertEquals(BOOTLOADER_VERSION, info.getBootloaderVersion());
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will skip to download
     * bootloader image if it's not in files.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBootloader_notInFiles() throws Exception {
        setDownloadSuccessExpectations();
        mRemoteBuildFiles.add("file.img");
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        IDeviceBuildInfo info = (IDeviceBuildInfo) mLaunchControlProvider.downloadBuildFiles(
                remoteBuild, "target", "build", mMockDownloader);
        assertNotNull(info);
        assertNull(info.getBootloaderImageFile());
        assertNull(info.getBootloaderVersion());
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will try to download
     * bootloader image and if there is no bootloader image in the artifact, it will ignore the
     * failure.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBootloader_failed() throws Exception {
        mRemoteBuildFiles.add("bootloader.img");
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        when(mMockDownloader.downloadFile("bootloader.img"))
                .thenThrow(
                        new BuildRetrievalError(
                                "No bootloader image.",
                                InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR));
        setDownloadSuccessExpectations();
        IDeviceBuildInfo info = (IDeviceBuildInfo) mLaunchControlProvider.downloadBuildFiles(
                remoteBuild, "target", "build", mMockDownloader);
        assertNotNull(info);
        assertNull(info.getBootloaderImageFile());
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will try to download
     * baseband image.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBaseband() throws Exception {
        setDownloadSuccessExpectations();
        when(mMockDownloader.downloadFile(Mockito.eq("radio.img")))
                .thenReturn(new File("fakefilethatdoesntmatter"));
        mRemoteBuildFiles.add("license/radio.img.txt");
        mRemoteBuildFiles.add("radio.img");
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        IDeviceBuildInfo info = (IDeviceBuildInfo) mLaunchControlProvider.downloadBuildFiles(
                    remoteBuild, "target", "build", mMockDownloader);
        assertNotNull(info);
        assertNotNull(info.getBasebandImageFile());
        assertEquals(BASEBAND_VERSION, info.getBasebandVersion());
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will skip to download
     * baseband image if baseband image is not in files.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBaseband_notInFiles() throws Exception {
        setDownloadSuccessExpectations();
        mRemoteBuildFiles.add("file.img");
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        IDeviceBuildInfo info = (IDeviceBuildInfo) mLaunchControlProvider.downloadBuildFiles(
                remoteBuild, "target", "build", mMockDownloader);
        assertNotNull(info);
        assertNull(info.getBasebandImageFile());
        assertNull(info.getBasebandVersion());
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will try to download
     * baseband image and if there is no baseband image in the artifact, it will ignore the failure.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBaseband_failed() throws Exception {
        mRemoteBuildFiles.add("radio.img");
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        when(mMockDownloader.downloadFile("radio.img"))
                .thenThrow(
                        new BuildRetrievalError(
                                "No baseband image.",
                                InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR));
        setDownloadSuccessExpectations();
        IDeviceBuildInfo info = (IDeviceBuildInfo) mLaunchControlProvider.downloadBuildFiles(
                remoteBuild, "target", "build", mMockDownloader);
        assertNotNull(info);
        assertNull(info.getBasebandImageFile());
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will try to download
     * trusty image.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadTrusty() throws Exception {
        setDownloadSuccessExpectations();
        RemoteBuildInfo remoteBuild = RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());

        IDeviceBuildInfo info = (IDeviceBuildInfo) mLaunchControlProvider.downloadBuildFiles(
                remoteBuild, "target", "build", mMockDownloader);

        assertNotNull(info);
        assertNotNull(info.getFile(BuildInfoFileKey.TRUSTY_IMAGE));
        assertEquals(TRUSTY_VERSION, info.getVersion(BuildInfoFileKey.TRUSTY_IMAGE));

    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will skip to download
     * trusty image if trusty version is not specified in the android-info.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadTrusty_noTrustyVersion() throws Exception {
        String androidInfoContent =
                String.format(
                        "require board=board1|board2\n"
                                + "require version-bootloader=%s\n"
                                + "require version-baseband=%s\n",
                        BOOTLOADER_VERSION, BASEBAND_VERSION);
        FileUtil.writeToFile(androidInfoContent, mAndroidInfo);
        File imageFile = new File(mTempDir, "another_image.zip");
        ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(imageFile));
        ZipUtil.addToZip(zipOutput, mAndroidInfo, new LinkedList<String>());
        StreamUtil.close(zipOutput);
        mStubImageFile = imageFile;
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        setDownloadSuccessExpectations();

        IDeviceBuildInfo info =
                (IDeviceBuildInfo)
                        mLaunchControlProvider.downloadBuildFiles(
                                remoteBuild, "target", "build", mMockDownloader);

        assertNotNull(info);
        assertNull(info.getFile(BuildInfoFileKey.TRUSTY_IMAGE));
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will throw exception if
     * trusty image is not in files.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadTrusty_notInFiles() throws Exception {
        setDownloadSuccessExpectations();
        mRemoteBuildFiles.clear();
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        try {
            mLaunchControlProvider.downloadBuildFiles(
                    remoteBuild, "target", "build", mMockDownloader);

            fail("BuildRetrievalError not thrown");
        } catch (BuildRetrievalError e) {
            assertEquals(InfraErrorIdentifier.ARTIFACT_NOT_FOUND, e.getErrorId());
        }
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will try to download
     * trusty image and if there is no trusty image in the artifact, it will throw an exception.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadTrusty_failed() throws Exception {

        when(mMockDownloader.downloadFile(Mockito.eq("filepath"))).thenReturn(mStubImageFile);
        when(mMockDownloader.downloadFile("tzsw.img"))
                .thenThrow(
                        new BuildRetrievalError(
                                "No trusty image.", InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR));
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());

        try {
            mLaunchControlProvider.downloadBuildFiles(
                    remoteBuild, "target", "build", mMockDownloader);

            fail("BuildRetrievalError not thrown");
        } catch (BuildRetrievalError e) {
            assertEquals(InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR, e.getErrorId());
        }
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will throw
     * BuildRetrievalError when pasing android-info.txt failed.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBuildFiles_parseAndroidInfoFailed() throws Exception {
        when(mMockDownloader.downloadFile("filepath"))
                .thenReturn(new File("fakefilethatdoesntmatter"));
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        try {
            mLaunchControlProvider.downloadBuildFiles(
                    remoteBuild, "target", "build", mMockDownloader);
            fail("BuildRetrievalError not thrown");
        } catch (BuildRetrievalError e) {
            // expected
        }
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} can skip device image.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBuildFiles_skipDeviceImage() throws Exception {
        mLaunchControlProvider.skipDownload(BuildAttributeKey.DEVICE_IMAGE);
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        setDownloadSuccessExpectations();
        IDeviceBuildInfo info = (IDeviceBuildInfo) mLaunchControlProvider.downloadBuildFiles(
                remoteBuild, "target", "build", mMockDownloader);
        assertNotNull(info);
        assertNull(info.getDeviceImageFile());
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} skip downloading test
     * zip, but download the test config only zip instead, when option test-config-only-zip is set.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBuildFiles_delayDownloadTestZip() throws Exception {
        OptionSetter setter = new OptionSetter(mLaunchControlProvider);
        setter.setOptionValue("test-config-only-zip", "bar");
        setter.setOptionValue("test-zip-file-filter", "foo");
        mLaunchControlProvider.skipDownload(BuildAttributeKey.DEVICE_IMAGE);
        mRemoteBuildFiles.add("foo");
        mRemoteBuildFiles.add("bar");
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());

        File testZip = new File(mTempDir, "bar.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(testZip))) {
            ZipEntry e = new ZipEntry("bar.txt");
            out.putNextEntry(e);
            byte[] data = "bar".getBytes();
            out.write(data, 0, data.length);
            out.closeEntry();
        }

        when(mMockDownloader.downloadFile(Mockito.eq("bar"))).thenReturn(testZip);

        IDeviceBuildInfo info = null;
        try {
            info =
                    (IDeviceBuildInfo)
                            mLaunchControlProvider.downloadBuildFiles(
                                    remoteBuild, "target", "build", mMockDownloader);
            assertNotNull(info);
            assertNull(info.getDeviceImageFile());
            assertNotNull(info.getTestsDir());
            // Verify that the content of test config only zip is extracted to tests dir.
            File txtFile = new File(info.getTestsDir(), "bar.txt");
            assertTrue(txtFile.isFile());
            assertTrue(mLaunchControlProvider.getRemoteZipFilters().contains("foo"));
        } finally {
            if (info != null && info.getTestsDir() != null) {
                FileUtil.recursiveDelete(info.getTestsDir());
            }
        }
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} download a non-existing
     * extra zip file from remote.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadHostSharedLibrary_noExistsInRemoteBuild() throws Exception {
        OptionSetter setter = new OptionSetter(mLaunchControlProvider);
        setter.setOptionValue("test-config-only-zip", "bar");
        setter.setOptionValue("test-zip-file-filter", "foo");
        setter.setOptionValue("extra-host-shared-lib-zip", "host-shared-bar");
        mLaunchControlProvider.skipDownload(BuildAttributeKey.DEVICE_IMAGE);
        mRemoteBuildFiles.add("foo");
        mRemoteBuildFiles.add("bar");
        RemoteBuildInfo remoteBuild = RemoteBuildInfo.parseRemoteBuildInfo(
                makeLaunchControlResponse());
        try {
            mLaunchControlProvider.downloadBuildFiles(
                    remoteBuild, "target", "build", mMockDownloader);
            fail("did not throw BuildRetrievalError");
        } catch (BuildRetrievalError e) {
            // expected
        }
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will not download an
     * extra zip file from remote while partial download isn't set.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadHostSharedLibrary_partialDownloadNotSet() throws Exception {
        OptionSetter setter = new OptionSetter(mLaunchControlProvider);
        setter.setOptionValue("test-zip-file-filter", "foo");
        setter.setOptionValue("extra-host-shared-lib-zip", "host-shared");
        mLaunchControlProvider.skipDownload(BuildAttributeKey.DEVICE_IMAGE);
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        remoteBuild.addAttribute(BuildAttributeKey.FILES, "foo,bar,host-shared");
        File testZip = createZipFile("foo.zip");
        when(mMockDownloader.downloadFile(Mockito.eq("foo"))).thenReturn(testZip);

        IDeviceBuildInfo info = null;
        try {
            info =
                    (IDeviceBuildInfo)
                            mLaunchControlProvider.downloadBuildFiles(
                                    remoteBuild, "target", "build", mMockDownloader);
            assertNotNull(info);
            assertNull(info.getFile("host-shared"));
        } finally {
            if (info != null && info.getTestsDir() != null) {
                FileUtil.recursiveDelete(info.getTestsDir());
            }
        }

    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} will download an extra
     * zip file from remote while partial download is set.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadHostSharedLibrary() throws Exception {
        OptionSetter setter = new OptionSetter(mLaunchControlProvider);
        setter.setOptionValue("test-config-only-zip", "bar");
        setter.setOptionValue("test-zip-file-filter", "foo");
        setter.setOptionValue("extra-host-shared-lib-zip", "host-shared");
        mLaunchControlProvider.skipDownload(BuildAttributeKey.DEVICE_IMAGE);
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        remoteBuild.addAttribute(BuildAttributeKey.FILES, "foo,bar,host-shared");
        File sharedZip = createZipFile("host-shared.zip");
        File testZip = createZipFile("bar.zip");
        when(mMockDownloader.downloadFile(
                Mockito.eq("host-shared"))).thenReturn(sharedZip);
        when(mMockDownloader.downloadFile(Mockito.eq("bar"))).thenReturn(testZip);

        IDeviceBuildInfo info = null;
        try {
            info =
                    (IDeviceBuildInfo)
                            mLaunchControlProvider.downloadBuildFiles(
                                    remoteBuild, "target", "build", mMockDownloader);
            assertNotNull(info);
            assertNotNull(info.getFile("host-shared"));
        } finally {
            if (info != null && info.getTestsDir() != null) {
                FileUtil.recursiveDelete(info.getTestsDir());
            }
        }
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#fetchTestsZipPath(RemoteBuildInfo)} returns
     * the correct zip path when RemoteBuildInfo has no files.
     *
     * @throws Exception
     */
    @Test
    public void testFetchTestsZipPath_empty() throws Exception {
        OptionSetter setter = new OptionSetter(mLaunchControlProvider);
        RemoteBuildInfo remoteBuildEmpty =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        assertNull(mLaunchControlProvider.fetchTestsZipPath(remoteBuildEmpty));
        setter.setOptionValue("test-zip-file-filter", "hi");
        assertNull(mLaunchControlProvider.fetchTestsZipPath(remoteBuildEmpty));
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#fetchTestsZipPath(RemoteBuildInfo)} returns
     * the correct zip path when RemoteBuildInfo has a file foo.
     *
     * @throws Exception
     */
    @Test
    public void testFetchTestsZipPath_withFile() throws Exception {
        OptionSetter setter = new OptionSetter(mLaunchControlProvider);
        mRemoteBuildFiles.add("foo");
        mRemoteBuildFiles.add("bar");
        RemoteBuildInfo remoteBuildEmpty =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        assertNull(mLaunchControlProvider.fetchTestsZipPath(remoteBuildEmpty));
        setter.setOptionValue("test-zip-file-filter", "foo");
        assertEquals("foo", mLaunchControlProvider.fetchTestsZipPath(remoteBuildEmpty));
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#fetchTestsZipPath(RemoteBuildInfo)} returns
     * zip mismatch when the pattern is not match.
     *
     * @throws Exception
     */
    @Test
    public void testFetchTestsZipPath_misMatch() throws Exception {
        OptionSetter setter = new OptionSetter(mLaunchControlProvider);
        mRemoteBuildFiles.add("foo");
        mRemoteBuildFiles.add("bar");
        RemoteBuildInfo remoteBuildTest =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());
        final BuildAttributeKey key = BuildAttributeKey.TESTS_ZIP;
        mLaunchControlProvider.addDownloadKey(key);
        setter.setOptionValue("test-zip-file-filter", "hi");
        setter.setOptionValue("error-if-zip-filter-mismatch", "true");
        try {
            mLaunchControlProvider.fetchTestsZipPath(remoteBuildTest);
            fail("did not throw BuildRetrievalError");
        } catch (BuildRetrievalError expected) {
            // expected
        }
    }

    /**
     * Verifies that {@link DeviceLaunchControlProvider#downloadBuildFiles} partially downloads
     * files in test zip based on option test-zip-partial-download-filter.
     *
     * @throws Exception
     */
    @Test
    public void testDownloadBuildFiles_partialDownloadTestZip() throws Exception {
        OptionSetter setter = new OptionSetter(mLaunchControlProvider);
        setter.setOptionValue("test-zip-partial-download-filter", "bar.txt");
        setter.setOptionValue("test-zip-file-filter", "foo");
        mLaunchControlProvider.skipDownload(BuildAttributeKey.DEVICE_IMAGE);
        mRemoteBuildFiles.add("foo");
        mRemoteBuildFiles.add("bar");
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(makeLaunchControlResponse());

        mMockDownloader.downloadZippedFiles(
                (File) Mockito.any(),
                Mockito.eq("foo"),
                Mockito.eq(Arrays.asList("bar.txt")),
                Mockito.eq(null));

        IDeviceBuildInfo info = null;
        try {
            info =
                    (IDeviceBuildInfo)
                            mLaunchControlProvider.downloadBuildFiles(
                                    remoteBuild, "target", "build", mMockDownloader);
            assertNotNull(info);
            assertNull(info.getDeviceImageFile());
            assertNotNull(info.getTestsDir());
        } finally {
            if (info != null && info.getTestsDir() != null) {
                FileUtil.recursiveDelete(info.getTestsDir());
            }
        }
    }

    /**
     * Helper to create a zip file based on the given file name.
     */
    private File createZipFile(String fileName) throws Exception {
        File zipFile = new File(mTempDir, fileName);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))) {
            ZipEntry e = new ZipEntry("someline");
            out.putNextEntry(e);
            byte[] data = "someline".getBytes();
            out.write(data, 0, data.length);
            out.closeEntry();
        }
        return zipFile;
    }
}
