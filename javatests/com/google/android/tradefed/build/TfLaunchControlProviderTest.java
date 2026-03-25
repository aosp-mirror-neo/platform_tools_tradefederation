// Copyright 2017 Google Inc. All Rights Reserved.
package com.google.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.host.HostOptions;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.net.IHttpHelper;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;

/** Unit tests for {@link TfLaunchControlProvider}. */
@RunWith(JUnit4.class)
public class TfLaunchControlProviderTest {

    private static final String TEST_TAG = "tag";
    private static final String TEST_TARGET = "passion";
    private static final String TEST_BRANCH = "main";
    private static final String TEST_QUERY_RESULT =
            String.format(
                    "%s:4\n%s:filepath\nrc:AA3",
                    BuildAttributeKey.BUILD_ID.getRemoteValue(),
                    BuildAttributeKey.DEVICE_IMAGE.getRemoteValue());
    private TfLaunchControlProvider mLcp;
    private IHttpHelper mMockHttpHelper;
    private IFileDownloader mMockDownloader;
    private AndroidBuildArtifactHelper mMockAbHelper;

    private File mLcCacheDir = null;

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
        mMockHttpHelper = Mockito.mock(IHttpHelper.class);
        mMockDownloader = Mockito.mock(IFileDownloader.class);
        mMockAbHelper = Mockito.mock(AndroidBuildArtifactHelper.class);

        mLcCacheDir = FileUtil.createTempDir("dlcp-unit-test");
        HostOptions hostOptions = new HostOptions();
        OptionSetter setter = new OptionSetter(hostOptions);
        setter.setOptionValue("host_options:download-cache-dir", mLcCacheDir.getAbsolutePath());

        mLcp =
                new TfLaunchControlProvider() {
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
                };
        mLcp.setTestTag(TEST_TAG);
        mLcp.setBuildFlavor(TEST_TARGET);
        mLcp.setBranch(TEST_BRANCH);
    }

    @After
    public void tearDown() {
        FileUtil.recursiveDelete(mLcCacheDir);
    }

    /** Test for when there is no google-tradefed.zip in the filepath returned by server. */
    @Test
    public void testQueryForBuild_noTfArtifact() throws Exception {
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(TEST_QUERY_RESULT + "\nfiles:file.img");
        doReturn(remoteBuild)
                .when(mMockAbHelper)
                .queryForLatestBuild(TEST_BRANCH, TEST_TARGET, "linux");
        try {
            mLcp.queryForBuild();
            fail("Should have thrown an exception");
        } catch (BuildRetrievalError expected) {
            assertEquals(
                    "Failed to locate TradeFed artifact google-tradefed.zip for branch 'main', "
                            + "build-id 'null' and flavor 'passion'",
                    expected.getMessage());
        }
    }

    @Test
    public void testQueryForBuild_noTfArtifact_aospFallback() throws Exception {
        mLcp.setAospFallback(true);

        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(TEST_QUERY_RESULT + "\nfiles:file.img");
        doReturn(remoteBuild)
                .when(mMockAbHelper)
                .queryForLatestBuild(TEST_BRANCH, TEST_TARGET, "linux");
        try {
            mLcp.queryForBuild();
            fail("Should have thrown an exception");
        } catch (BuildRetrievalError expected) {
            assertEquals(
                    "Failed to locate TradeFed artifact google-tradefed.zip and failed to locate "
                            + "fallback tradefed.zip for branch 'main', build-id 'null' and "
                            + "flavor 'passion'",
                    expected.getMessage());
        }
    }

    /** Test for when the server returns a google-tradefed.zip path. */
    @Test
    public void testQueryForBuild() throws Exception {
        final String tfRemotePath = "path/google-tradefed.zip";
        RemoteBuildInfo remoteBuild =
                RemoteBuildInfo.parseRemoteBuildInfo(TEST_QUERY_RESULT + "\nfiles:" + tfRemotePath);
        doReturn(remoteBuild)
                .when(mMockAbHelper)
                .queryForLatestBuild(TEST_BRANCH, TEST_TARGET, "linux");
        RemoteBuildInfo res = mLcp.queryForBuild();

        assertNotNull(res);
        assertEquals(tfRemotePath, res.getAttribute(BuildAttributeKey.TF));
    }

    /** Test when the downloaded file cannot be extracted. */
    @Test
    public void testDownloadFiles_failedToExtract() throws Exception {
        final String fakeRemotePath = "fake/path/google-tradefed.zip";
        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.TF, fakeRemotePath);
        File downloadedFile = FileUtil.createTempFile("tfLCP", ".test");
        when(mMockDownloader.downloadFile(fakeRemotePath)).thenReturn(downloadedFile);
        try {
            mLcp.downloadBuildFiles(remoteBuild, "targetName", "tradefed", mMockDownloader);
            fail("Should have thrown an exception");
        } catch (BuildRetrievalError expected) {
            // File should have been deleted
            assertFalse(downloadedFile.exists());
            assertEquals("Failed to extract google-tradefed.zip", expected.getMessage());
        } finally {
            FileUtil.recursiveDelete(downloadedFile);
        }
    }

    /** Test when the downloaded file is correctly extracted and cleaned up. */
    @Test
    public void testDownloadFiles() throws Exception {
        final String fakeRemotePath = "fake/path/google-tradefed.zip";
        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.TF, fakeRemotePath);
        File downloadedFile = FileUtil.createTempDir("tf-lcp-test");
        File zipTf = ZipUtil.createZip(downloadedFile);

        when(mMockDownloader.downloadFile(fakeRemotePath)).thenReturn(zipTf);
        try {
            IBuildInfo info = mLcp.downloadBuildFiles(remoteBuild, "tag", "build", mMockDownloader);
            assertNotNull(info);
            assertTrue(info instanceof IFolderBuildInfo);
            File rootFolder = ((IFolderBuildInfo) info).getRootDir();
            assertNotNull(rootFolder);
            assertTrue(rootFolder.exists());
            // After clean up the root folder must be deleted.
            mLcp.cleanUp(info);
            assertFalse(rootFolder.exists());
        } finally {
            FileUtil.recursiveDelete(downloadedFile);
            FileUtil.recursiveDelete(zipTf);
        }
    }

    /** Test {@link TfLaunchControlProvider#findFilePath(String, String)}. */
    @Test
    public void testFindFilePath() throws Exception {
        assertNull(mLcp.findFilePath("", ""));
        assertNull(mLcp.findFilePath("", "abc"));
        assertNull(mLcp.findFilePath("abc", "def"));
        assertEquals("d/abc", mLcp.findFilePath("d/abc", "abc"));
        assertEquals("g/def", mLcp.findFilePath("abc,g/def", "def"));
    }
}
