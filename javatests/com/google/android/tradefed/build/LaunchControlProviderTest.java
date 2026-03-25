// Copyright 2012 Google Inc. All Rights Reserved.

package com.google.android.tradefed.build;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.FileDownloadCacheWrapper;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.host.HostOptions;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.util.CasUtil;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.net.IHttpHelper;

import com.google.android.tradefed.util.CasFileDownloader;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;

/** Unit tests for {@link LaunchControlProvider} */
@RunWith(JUnit4.class)
public class LaunchControlProviderTest {
    @Rule public TemporaryFolder mTestTemp = new TemporaryFolder();

    @BeforeClass
    public static void setUpClass() throws Exception {
        try {
            // Initialize GlobalConfiguration before calling GlobalConfiguration.getInstance().
            GlobalConfiguration.createGlobalConfiguration(new String[] {});
        } catch (IllegalStateException e) {
            // Ignore.
        }
    }

    /**
     * Ensure that when the sso option is on, we create the proper helper and use right location.
     */
    @Test
    public void testSsoClientlocation() throws ConfigurationException {
        final String ssoFakePath = "/tmp/sso_client_test";
        LaunchControlProvider mLC = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }
            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName, String buildName,
                    IFileDownloader downloader) throws BuildRetrievalError {
                // empty on purpose
                return null;
            }
        };
        OptionSetter setter = new OptionSetter(mLC);
        setter.setOptionValue("sso-client-path", ssoFakePath);
        setter.setOptionValue("use-sso-client", "true");
        IHttpHelper helper = mLC.createHttpHelper();
        assertTrue(helper instanceof SsoClientHttpHelper);
        assertEquals(((SsoClientHttpHelper)helper).getSsoClientPath(), ssoFakePath);
    }

    /**
     * Test that the build info associated with the {@link BuildRetrievalError} contains some
     * information necessary for reporting.
     */
    @Test
    public void testBuildRetrievalError_buildInfo() throws Exception {
        final String buildResponse =
                String.format(
                        "%s:54321", RemoteBuildInfo.BuildAttributeKey.BUILD_ID.getRemoteValue());
        final RemoteBuildInfo build = RemoteBuildInfo.parseRemoteBuildInfo(buildResponse);

        LaunchControlProvider mLC =
                new LaunchControlProvider() {
                    @Override
                    public void cleanUp(IBuildInfo info) {
                        // empty on purpose
                    }

                    @Override
                    protected IBuildInfo downloadBuildFiles(
                            RemoteBuildInfo remoteBuild,
                            String testTargetName,
                            String buildName,
                            IFileDownloader downloader)
                            throws BuildRetrievalError {
                        return null;
                    }

                    @Override
                    public IBuildInfo fetchRemoteBuild(RemoteBuildInfo remoteBuild)
                            throws BuildRetrievalError {
                        throw new BuildRetrievalError(
                                "test", new Exception("failed"), new BuildInfo());
                    }

                    @Override
                    protected RemoteBuildInfo queryForBuild() throws BuildRetrievalError {
                        return build;
                    }

                    @Override
                    IHostOptions getHostOptions() {
                        // Use default hostOption with null value.
                        IHostOptions hostOption = new HostOptions();
                        return hostOption;
                    }
                };
        mLC.setBuildFlavor("TEST_FLAVOR");
        mLC.setTestTag("TEST_TAG");
        mLC.setBranch("BRANCH");
        mLC.setBuildOs("fastlinux");
        try {
            mLC.getBuild();
            fail("Should have thrown an exception");
        } catch (BuildRetrievalError expected) {
            assertNotNull(expected.getBuildInfo());
            assertEquals("BRANCH", expected.getBuildInfo().getBuildBranch());
            assertEquals("TEST_FLAVOR", expected.getBuildInfo().getBuildFlavor());
            assertEquals(
                    "TEST_FLAVOR_fastlinux",
                    expected.getBuildInfo().getBuildAttributes().get("build_target"));
        }
    }

    /** Test that "" doesn't match RemoteBuildInfo with no files. */
    @Test(expected = BuildRetrievalError.class)
    public void testDownloadAdditionalFiles_failNoFile() throws Exception {
        LaunchControlProvider lcp =
                new LaunchControlProvider() {
                    @Override
                    protected IBuildInfo downloadBuildFiles(
                            RemoteBuildInfo remoteBuild,
                            String testTargetName,
                            String buildName,
                            IFileDownloader downloader)
                            throws BuildRetrievalError {
                        return null;
                    }

                    @Override
                    public void cleanUp(IBuildInfo info) {
                        // empty on purpose
                    }
                };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("additional-files-filter", "");
        RemoteBuildInfo remoteBuildInfo = new RemoteBuildInfo();
        remoteBuildInfo.addAttribute(RemoteBuildInfo.BuildAttributeKey.FILES, "");
        lcp.downloadAdditionalFiles(remoteBuildInfo, null, null);
    }

    /** Test method downloadAdditionalFiles. */
    @Test
    public void testDownloadAdditionalFiles() throws Exception {
        final String buildPath = "branch-linux-target-userdebug/P123/";
        final String remoteZip = buildPath + "tests.zip";
        final String additionalFile = buildPath + "other.img";
        LaunchControlProvider lcp =
                new LaunchControlProvider() {
                    @Override
                    protected IBuildInfo downloadBuildFiles(
                            RemoteBuildInfo remoteBuild,
                            String testTargetName,
                            String buildName,
                            IFileDownloader downloader)
                            throws BuildRetrievalError {
                        return null;
                    }

                    @Override
                    public void cleanUp(IBuildInfo info) {
                        // empty on purpose
                    }
                };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("additional-files-filter", additionalFile);
        lcp.addRemoteZipFilter(".*tests.zip$");
        RemoteBuildInfo remoteBuildInfo = new RemoteBuildInfo();
        remoteBuildInfo.addAttribute(
                RemoteBuildInfo.BuildAttributeKey.FILES,
                String.format("%s,%s", remoteZip, additionalFile));
        IBuildInfo localBuild = Mockito.mock(IBuildInfo.class);
        File remoteFile = new File(LCUtil.createResolvableFilePath(remoteZip));

        IFileDownloader downloader = Mockito.mock(IFileDownloader.class);
        when(downloader.downloadFile(Mockito.eq(additionalFile))).thenReturn(null);

        lcp.downloadAdditionalFiles(remoteBuildInfo, downloader, localBuild);
        verify(localBuild).setFile(
                Mockito.eq(IBuildInfo.REMOTE_FILE_PREFIX + remoteFile.toString()),
                Mockito.eq(remoteFile),
                Mockito.eq(IBuildInfo.REMOTE_FILE_VERSION));
        verify(localBuild).setFile(Mockito.eq("other.img"), Mockito.eq(null), Mockito.eq("0"));
    }

    /**
     * Make sure that {@link LaunchControlProvider#queryForBuild()} does not trigger HTTP code path
     * when set to use build API
     **/
    @Test
    public void testQueryWithBuildApi_noHttp() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }

            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName,
                    String buildName, IFileDownloader downloader) throws BuildRetrievalError {
                return null;
            }

            @Override
            protected RemoteBuildInfo queryForBuildWithBuildApi() throws BuildRetrievalError {
                return null;
            }

            @Override
            protected IHttpHelper createHttpHelper() {
                throw new IllegalStateException("createHttpHelper should not be called");
            }
        };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("use-build-api", "true");
        setter.setOptionValue("branch", "foo");
        setter.setOptionValue("build-flavor", "bar");
        lcp.queryForBuild();
    }

    /**
     * Make sure that {@link LaunchControlProvider#queryForBuild()} throws exception when no build
     * flavor is provided, when using build API
     */
    @Test(expected = HarnessRuntimeException.class)
    public void testQueryWithBuildApi_noBuildFlavor() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }

            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName,
                    String buildName, IFileDownloader downloader) throws BuildRetrievalError {
                return null;
            }

            @Override
            protected RemoteBuildInfo performBuildApiQuery() throws BuildRetrievalError {
                return null;
            }
        };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("use-build-api", "true");
        lcp.queryForBuild();
    }

    /**
     * Make sure that {@link LaunchControlProvider#queryForBuild()} throws exception when
     * unsupported params are used when using build API
     */
    @Test(expected = HarnessRuntimeException.class)
    public void testQueryWithBuildApi_unsupportedParams_iteration() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }

            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName,
                    String buildName, IFileDownloader downloader) throws BuildRetrievalError {
                return null;
            }

            @Override
            protected RemoteBuildInfo performBuildApiQuery() throws BuildRetrievalError {
                return null;
            }
        };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("use-build-api", "true");
        setter.setOptionValue("test-iterations", "10");
        lcp.queryForBuild();
    }

    /**
     * Make sure that {@link LaunchControlProvider#queryForBuild()} throws exception when
     * unsupported params are used when using build API
     */
    @Test(expected = HarnessRuntimeException.class)
    public void testQueryWithBuildApi_unsupportedParams_fallback() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }

            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName,
                    String buildName, IFileDownloader downloader) throws BuildRetrievalError {
                return null;
            }

            @Override
            protected RemoteBuildInfo performBuildApiQuery() throws BuildRetrievalError {
                return null;
            }
        };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("use-build-api", "true");
        setter.setOptionValue("fallback", "true");
        lcp.queryForBuild();
    }

    /**
     * Make sure that {@link LaunchControlProvider#queryForBuild()} throws exception when
     * unsupported params are used when using build API
     */
    @Test(expected = HarnessRuntimeException.class)
    public void testQueryWithBuildApi_unsupportedParams_whitelist() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }

            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName,
                    String buildName, IFileDownloader downloader) throws BuildRetrievalError {
                return null;
            }

            @Override
            protected RemoteBuildInfo performBuildApiQuery() throws BuildRetrievalError {
                return null;
            }
        };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("use-build-api", "true");
        lcp.queryForBuild();
    }

    /**
     * Make sure that {@link LaunchControlProvider#queryForBuild()} throws exception when
     * unsupported params are used when using build API
     */
    @Test(expected = HarnessRuntimeException.class)
    public void testQueryWithBuildApi_unsupportedParams_queryWindow() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }

            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName,
                    String buildName, IFileDownloader downloader) throws BuildRetrievalError {
                return null;
            }

            @Override
            protected RemoteBuildInfo performBuildApiQuery() throws BuildRetrievalError {
                return null;
            }
       };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("use-build-api", "true");
        setter.setOptionValue("query-window", "5m30s");
        lcp.queryForBuild();
    }

    /**
     * Make sure that {@link LaunchControlProvider#queryForBuild()} throws exception when neither
     * branch nor build id is provided, when using build API
     */
    @Test(expected = HarnessRuntimeException.class)
    public void testQueryWithBuildApi_mustHaveBranchOrBuildId() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }

            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName,
                    String buildName, IFileDownloader downloader) throws BuildRetrievalError {
                return null;
            }

            @Override
            protected RemoteBuildInfo performBuildApiQuery() throws BuildRetrievalError {
                return null;
            }
       };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("use-build-api", "true");
        setter.setOptionValue("build-flavor", "bar");
        lcp.queryForBuild();
    }

    /**
     * Make sure that {@link LaunchControlProvider#queryForBuild()} works with branch provided
     * when using build API
     **/
    @Test
    public void testQueryWithBuildApi_withBranch() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }

            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName,
                    String buildName, IFileDownloader downloader) throws BuildRetrievalError {
                return null;
            }

            @Override
            protected RemoteBuildInfo performBuildApiQuery() throws BuildRetrievalError {
                return null;
            }
        };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("use-build-api", "true");
        setter.setOptionValue("build-flavor", "bar");
        setter.setOptionValue("branch", "foo");
        setter.setOptionValue("query-type", "NOTIFY_TEST_BUILD");
        lcp.queryForBuild();
    }

    /**
     * Make sure that {@link LaunchControlProvider#queryForBuild()} works with build id provided
     * when using build API
     **/
    @Test
    public void testQueryWithBuildApi_withBuildId() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider() {
            @Override
            public void cleanUp(IBuildInfo info) {
                // empty on purpose
            }

            @Override
            protected IBuildInfo downloadBuildFiles(
                    RemoteBuildInfo remoteBuild, String testTargetName,
                    String buildName, IFileDownloader downloader) throws BuildRetrievalError {
                return null;
            }

            @Override
            protected RemoteBuildInfo performBuildApiQuery() throws BuildRetrievalError {
                return null;
            }
       };
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("use-build-api", "true");
        setter.setOptionValue("build-flavor", "bar");
        setter.setOptionValue("build-id", "1");
        setter.setOptionValue("branch", "foo");
        lcp.queryForBuild();
    }

    @Test
    public void testCreateLaunchControlDownloader_default_createCasDownloader() throws Exception {
        LaunchControlProvider lcp = new LaunchControlProvider();

        IFileDownloader downloader = lcp.createLaunchControlDownloader(null);

        assertThat(downloader).isInstanceOf(CasFileDownloader.class);
    }

    @Test
    public void testCreateLaunchControlDownloader_forceDisableCas_createCacheWrapper() throws Exception {
        OptionSetter optionSetter =
                new OptionSetter(GlobalConfiguration.getInstance().getHostOptions());
        optionSetter.setOptionValue(
                "host_options:service-account-json-key-file",
                "cas-json-key",
                "/path/to/account_key.json");
        LaunchControlProvider lcp = new LaunchControlProvider();
        OptionSetter setter = new OptionSetter(lcp);
        setter.setOptionValue("force-disable-cas", "true");
        setter.setOptionValue("cas-manifest-file", "/path/to/manifest.json");

        IFileDownloader downloader = lcp.createLaunchControlDownloader(null);

        assertThat(downloader).isInstanceOf(FileDownloadCacheWrapper.class);
    }

    @Test
    public void testHandleDirectoryDownload_returnsDirectoryForChunkedDir()
            throws Exception {
        File downloadedDir = mTestTemp.newFolder("downloadedDir");
        downloadedDir.mkdirs();
        File chunksDir = new File(downloadedDir, CasUtil.CHUNKS_DIR_NAME);
        chunksDir.mkdirs();

        LaunchControlProvider lcp = new LaunchControlProvider();
        File file = lcp.handleDirectoryDownload(downloadedDir);

        assertThat(file).isEqualTo(downloadedDir);
    }

    @Test
    public void testHandleDirectoryDownload_returnsFileForSingleFileDirectory()
            throws Exception {
        File downloadedDir = mTestTemp.newFolder("downloadedDir");
        downloadedDir.mkdirs();
        File imageFile = new File(downloadedDir, "abc-image.zip");
        FileUtil.writeToFile("File content", imageFile);

        LaunchControlProvider lcp = new LaunchControlProvider();
        File file = lcp.handleDirectoryDownload(downloadedDir);

        assertThat(file).isEqualTo(imageFile);
    }

    @Test
    public void testHandleDirectoryDownload_returnsDirectoryForMultipleFiles()
            throws Exception {
        File downloadedDir = mTestTemp.newFolder("downloadedDir");
        downloadedDir.mkdirs();
        File chunksDir = new File(downloadedDir, CasUtil.CHUNKS_DIR_NAME);
        chunksDir.mkdirs();
        File imageFile = new File(downloadedDir, "system.img");
        FileUtil.writeToFile("File content", imageFile);

        LaunchControlProvider lcp = new LaunchControlProvider();
        File file = lcp.handleDirectoryDownload(downloadedDir);

        assertThat(file).isEqualTo(downloadedDir);
    }

}

