// Copyright 2017 Google Inc. All Rights Reserved.
package com.google.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.FolderBuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.observatory.TestDiscoveryInvoker;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.FuseUtil;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.ZipUtil2;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;

import org.json.JSONException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unit tests for {@link CtsLaunchControlProvider} */
@RunWith(JUnit4.class)
public class CtsLaunchControlProviderTest {
    @Rule public final TemporaryFolder mTestTempFolder = new TemporaryFolder();

    private static final String TEST_TARGET = "TEST_TARGET";
    private static final String BUILD_NAME = "BUILD_NAME";
    private static final String CTS_FOLDER_NAME = "fake-cts";
    private static final String TEST_MODULE_FOLDER_NAME = "testcases";
    private static final String TEST_MODULE_NAME = "CtsFakeTests";
    private static final String TEST_DEPENDENCY_FILE_NAME = "FakeDependencyFile.apk";
    private static final String TEST_CONFIG_FILE_NAME = "FakeConfig.config";
    private static final String TEST_JAR_FILE_NAME = "FakeJar.jar";
    private static final String TEST_DISCOVERY_DEPENDENCY_FOLDER_NAME = "tools";
    private static final String TEST_FORCE_DOWNLOAD_DEPENDENCY_NAME = "dependency.jar";
    private CtsLaunchControlProvider mProvider;
    private IBuildInfo mMockBuildInfo;
    private IBuildInfo mResultBuild;
    private IFileDownloader mMockFileDownloader;
    private FuseUtil mMockFuseUtil;
    private TestDiscoveryInvoker mMockTestDiscoveryInvoker;

    private File mCtsZip;

    @Before
    public void setUp() throws IOException {
        mMockFuseUtil = Mockito.mock(FuseUtil.class);
        mMockTestDiscoveryInvoker = Mockito.mock(TestDiscoveryInvoker.class);
        Mockito.when(mMockFuseUtil.canMountZip()).thenReturn(true);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock invocation) throws Throwable {
                                File zipFile = (File) invocation.getArgument(0);
                                File mountDir = (File) invocation.getArgument(1);
                                ZipUtil2.extractZip(zipFile, mountDir);
                                return null;
                            }
                        })
                .when(mMockFuseUtil)
                .mountZip(Mockito.any(File.class), Mockito.any(File.class));
        mProvider =
                new CtsLaunchControlProvider(mMockTestDiscoveryInvoker) {

                    @Override
                    protected void setBuildRemoteFile(
                            IFolderBuildInfo localBuild, String remoteCtsZipPath) {
                        // Avoid in unit tests
                    }

                    @Override
                    FuseUtil getFuseUtil() {
                        return mMockFuseUtil;
                    }

                    @Override
                    Set<String> getTestDiscoveryForcedDownloadFiles(List<String> modules) {
                        return new HashSet<>();
                    }
                };
        Configuration config = new Configuration("test", "test");
        config.setCommandLine(new String[] {"test"});
        mProvider.setConfiguration(config);
        mMockBuildInfo = Mockito.mock(IBuildInfo.class);
        mMockFileDownloader = Mockito.mock(IFileDownloader.class);

        mCtsZip = ZipUtil.createZip(mTestTempFolder.getRoot());
    }

    @After
    public void tearDown() {
        mProvider.cleanUp(mMockBuildInfo);
        FileUtil.recursiveDelete(mCtsZip);
        if (mResultBuild != null) {
            mResultBuild.cleanUp();
        }
    }

    /**
     * Test that {@link CtsLaunchControlProvider#downloadBuildFiles(RemoteBuildInfo, String, String,
     * IFileDownloader)} correctly returns a {@link IBuildInfo} created with downloaded info.
     */
    @Test
    public void testDownloadBuildFile() throws Exception {
        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");
        when(mMockFileDownloader.downloadFile(Mockito.eq("fakepath"))).thenReturn(mCtsZip);

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);

        assertEquals(BUILD_NAME, mResultBuild.getBuildTargetName());
        assertEquals("0", mResultBuild.getBuildId());
        assertTrue(mResultBuild instanceof FolderBuildInfo);
        assertTrue(((FolderBuildInfo) mResultBuild).getRootDir().getName().startsWith("cts_0_"));
    }

    /**
     * Test that {@link CtsLaunchControlProvider#downloadBuildFiles(RemoteBuildInfo, String, String,
     * IFileDownloader)} correctly returns a {@link IBuildInfo} created with downloaded info when an
     * extracted directory rather than a zip file is downloaded.
     */
    @Test
    public void testDownloadBuildFile_supportsDirectoryDownloads() throws Exception {
        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");
        File ctsRootDir = FileUtil.createTempDir("fake_cts_root", mTestTempFolder.getRoot());
        when(mMockFileDownloader.downloadFile(Mockito.eq("fakepath"))).thenReturn(ctsRootDir);

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);

        assertEquals(BUILD_NAME, mResultBuild.getBuildTargetName());
        assertEquals("0", mResultBuild.getBuildId());
        assertTrue(mResultBuild instanceof FolderBuildInfo);
        assertEquals(((FolderBuildInfo) mResultBuild).getRootDir(), ctsRootDir);
    }

    /**
     * Test that {@link CtsLaunchControlProvider#downloadBuildFiles(RemoteBuildInfo, String, String,
     * IFileDownloader)} correctly returns a {@link IBuildInfo} created with downloaded info with
     * the extra files.
     */
    @Test
    public void testDownloadBuildFile_withExtra() throws Exception {
        File extraDir = FileUtil.createTempDir("extra-dir", mTestTempFolder.getRoot());
        File extra1 = FileUtil.createTempFile("extra1", "jar", extraDir);
        OptionSetter setter = new OptionSetter(mProvider);
        setter.setOptionValue("cts-extra-artifact", extra1.getName());
        setter.setOptionValue("cts-extra-artifact", "doesnotexists");
        setter.setOptionValue("cts-extra-alt-path", extraDir.getAbsolutePath());

        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");
        when(mMockFileDownloader.downloadFile(Mockito.eq("fakepath"))).thenReturn(mCtsZip);

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);
        assertEquals(BUILD_NAME, mResultBuild.getBuildTargetName());
        assertEquals("0", mResultBuild.getBuildId());
        assertTrue(mResultBuild instanceof FolderBuildInfo);
        assertTrue(((FolderBuildInfo) mResultBuild).getRootDir().getName().startsWith("cts_0_"));
        // extra file
        assertNotNull(mResultBuild.getFile(extra1.getName()));
        assertNull(mResultBuild.getFile("doesnotexists"));
    }

    /** Test {@link CtsLaunchControlProvider#findFilePath(String, String)}. */
    @Test
    public void testFindFilePath() throws Exception {
        assertNull(mProvider.findFilePath("", ""));
        assertNull(mProvider.findFilePath("", "abc"));
        assertNull(mProvider.findFilePath("abc", "def"));
        assertEquals("abc", mProvider.findFilePath("abc", "abc"));
        assertEquals("def", mProvider.findFilePath("abc,def", "def"));
    }

    /**
     * Test that {@link CtsLaunchControlProvider#downloadBuildFiles(RemoteBuildInfo, String, String,
     * IFileDownloader)} correctly returns a {@link IBuildInfo} created with downloaded info with
     * fuse-zip option enabled.
     */
    @Test
    public void testDownloadBuildWithUseFuseZip() throws Exception {
        OptionSetter setter = new OptionSetter(mProvider);
        setter.setOptionValue("cts-use-fusezip", "true");
        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");
        when(mMockFileDownloader.downloadFile(Mockito.eq("fakepath"))).thenReturn(mCtsZip);

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);

        assertEquals(BUILD_NAME, mResultBuild.getBuildTargetName());
        assertEquals("0", mResultBuild.getBuildId());
        assertTrue(mResultBuild instanceof FolderBuildInfo);
        assertTrue(((FolderBuildInfo) mResultBuild).getRootDir().getName().startsWith("cts_0_"));
        assertTrue(((FolderBuildInfo) mResultBuild).shouldUseFuseZip());
    }

    /**
     * Test that {@link CtsLaunchControlProvider#downloadBuildFiles(RemoteBuildInfo, String, String,
     * IFileDownloader)} correctly download a {@link IBuildInfo} when partial download is enabled.
     */
    @Test
    public void testPartialDownload_PartialDownloadTestModules() throws Exception {
        mProvider.setCtsUsePartialDownload(true);
        mProvider.setCtsPackageName("fake-cts.zip");

        Set<String> forceDownloadRegex = new HashSet<>();
        forceDownloadRegex.add(TEST_FORCE_DOWNLOAD_DEPENDENCY_NAME);
        mProvider.setCtsForcePartialDownloadRegex(forceDownloadRegex);

        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");

        List<String> testDiscoveryDependencies = new ArrayList<>();
        testDiscoveryDependencies.add(
                CTS_FOLDER_NAME + "/" + TEST_DISCOVERY_DEPENDENCY_FOLDER_NAME);

        Map<String, List<String>> dependencies = new HashMap<>();
        List<String> testModuleNameList = new ArrayList<>();
        testModuleNameList.add(TEST_MODULE_NAME);
        List<String> testDependencyNameList = new ArrayList<>();
        testDependencyNameList.add(TEST_DEPENDENCY_FILE_NAME);
        dependencies.put(TestDiscoveryInvoker.TEST_MODULES_LIST_KEY, testModuleNameList);
        dependencies.put(TestDiscoveryInvoker.TEST_DEPENDENCIES_LIST_KEY, testDependencyNameList);
        when(mMockTestDiscoveryInvoker.discoverTestDependencies()).thenReturn(dependencies);

        // Mock first partial download of the /tools folder
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                File ctsRootDir = mock.getArgument(0);
                                File ctsZipFolder = new File(ctsRootDir, CTS_FOLDER_NAME);
                                assertTrue(ctsZipFolder.mkdir());
                                File testDiscoveryDependencyFolder =
                                        new File(
                                                ctsZipFolder,
                                                TEST_DISCOVERY_DEPENDENCY_FOLDER_NAME);
                                assertTrue(testDiscoveryDependencyFolder.mkdir());
                                return null;
                            }
                        })
                .when(mMockFileDownloader)
                .downloadZippedFiles(
                        Mockito.any(),
                        Mockito.eq("fakepath"),
                        Mockito.eq(testDiscoveryDependencies),
                        Mockito.anyList());

        // Mock the second download of the test dependencies
        List<String> partialDownloadList = new ArrayList<>();
        partialDownloadList.add(TEST_MODULE_NAME);
        partialDownloadList.add(TEST_DEPENDENCY_FILE_NAME);
        partialDownloadList.add(TEST_FORCE_DOWNLOAD_DEPENDENCY_NAME);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                File ctsRootDir = mock.getArgument(0);
                                List<String> includes = mock.getArgument(2);
                                File ctsZipFolder = new File(ctsRootDir, CTS_FOLDER_NAME);
                                File testFolder = new File(ctsZipFolder, TEST_MODULE_FOLDER_NAME);
                                assertTrue(testFolder.mkdir());
                                for (String dependencyName : includes) {
                                    File dependency = new File(testFolder, dependencyName);
                                    if (dependencyName.contains(".")) {
                                        assertTrue(dependency.createNewFile());
                                    } else {
                                        assertTrue(dependency.mkdir());
                                    }
                                }
                                return null;
                            }
                        })
                .when(mMockFileDownloader)
                .downloadZippedFiles(
                        Mockito.any(),
                        Mockito.eq("fakepath"),
                        Mockito.eq(partialDownloadList),
                        Mockito.anyList());

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);
        assertEquals(BUILD_NAME, mResultBuild.getBuildTargetName());
        assertEquals("0", mResultBuild.getBuildId());
        assertTrue(mResultBuild instanceof FolderBuildInfo);
        File ctsRootDir = ((FolderBuildInfo) mResultBuild).getRootDir();
        assertTrue(ctsRootDir.getName().startsWith("cts_0_"));
        assertNotNull(FileUtil.findFile(ctsRootDir, CTS_FOLDER_NAME));
        assertNotNull(FileUtil.findFile(ctsRootDir, TEST_DISCOVERY_DEPENDENCY_FOLDER_NAME));
        assertNotNull(FileUtil.findFile(ctsRootDir, TEST_MODULE_FOLDER_NAME));
        assertNotNull(FileUtil.findFile(ctsRootDir, TEST_MODULE_NAME));
        assertNotNull(FileUtil.findFile(ctsRootDir, TEST_DEPENDENCY_FILE_NAME));
        assertNotNull(FileUtil.findFile(ctsRootDir, TEST_FORCE_DOWNLOAD_DEPENDENCY_NAME));
        // No full download should have been executed
        Mockito.verify(mMockFileDownloader, times(0)).downloadFile(Mockito.anyString());
        assertEquals(
                CtsLaunchControlProvider.PartialDownloadMode.PartialDownloadTestModules,
                mProvider.getPartialDownloadMode());
    }

    /**
     * Test that {@link CtsLaunchControlProvider#downloadBuildFiles(RemoteBuildInfo, String, String,
     * IFileDownloader)} correctly download a {@link IBuildInfo} when partial download is enabled.
     */
    @Test
    public void testPartialDownload_PartialDownloadJarsAndConfigs() throws Exception {
        mProvider.setCtsUsePartialDownload(true);
        mProvider.setCtsPackageName("fake-cts.zip");
        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");

        List<String> testDiscoveryDependencies = new ArrayList<>();
        testDiscoveryDependencies.add(
                CTS_FOLDER_NAME + "/" + TEST_DISCOVERY_DEPENDENCY_FOLDER_NAME);

        Map<String, List<String>> dependencies = new HashMap<>();
        // Simulate the case when observatory only able to discover test dependencies
        List<String> testModuleNameList = new ArrayList<>();
        List<String> testDependencyNameList = new ArrayList<>();
        testDependencyNameList.add(TEST_DEPENDENCY_FILE_NAME);
        dependencies.put(TestDiscoveryInvoker.TEST_MODULES_LIST_KEY, testModuleNameList);
        dependencies.put(TestDiscoveryInvoker.TEST_DEPENDENCIES_LIST_KEY, testDependencyNameList);
        dependencies.put(
                TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY, Arrays.asList("true"));
        when(mMockTestDiscoveryInvoker.discoverTestDependencies()).thenReturn(dependencies);

        // Mock first partial download of the /tools folder
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                File ctsRootDir = mock.getArgument(0);
                                File ctsZipFolder = new File(ctsRootDir, CTS_FOLDER_NAME);
                                assertTrue(ctsZipFolder.mkdir());
                                File testDiscoveryDependencyFolder =
                                        new File(
                                                ctsZipFolder,
                                                TEST_DISCOVERY_DEPENDENCY_FOLDER_NAME);
                                assertTrue(testDiscoveryDependencyFolder.mkdir());
                                return null;
                            }
                        })
                .when(mMockFileDownloader)
                .downloadZippedFiles(
                        Mockito.any(),
                        Mockito.eq("fakepath"),
                        Mockito.eq(testDiscoveryDependencies),
                        Mockito.anyList());

        // Second download will download jars, configs and test dependencies
        List<String> secondPartialDownloadList = new ArrayList<>();
        secondPartialDownloadList.addAll(CtsLaunchControlProvider.JARS_AND_CONFIG_REGEX);
        secondPartialDownloadList.add(TEST_DEPENDENCY_FILE_NAME);

        // Mock the second download of the test dependencies
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                File ctsRootDir = mock.getArgument(0);
                                List<String> includes = mock.getArgument(2);
                                File ctsZipFolder = new File(ctsRootDir, CTS_FOLDER_NAME);
                                File testFolder = new File(ctsZipFolder, TEST_MODULE_FOLDER_NAME);
                                assertTrue(testFolder.mkdir());
                                for (String dependencyName : includes) {
                                    File dependency = new File(testFolder, dependencyName);
                                    if (dependencyName.contains(".")) {
                                        assertTrue(dependency.createNewFile());
                                    } else {
                                        assertTrue(dependency.mkdir());
                                    }
                                }
                                File testJar = new File(testFolder, TEST_JAR_FILE_NAME);
                                assertTrue(testJar.createNewFile());
                                File testConfig = new File(testFolder, TEST_CONFIG_FILE_NAME);
                                assertTrue(testConfig.createNewFile());
                                return null;
                            }
                        })
                .when(mMockFileDownloader)
                .downloadZippedFiles(
                        Mockito.any(),
                        Mockito.eq("fakepath"),
                        Mockito.eq(secondPartialDownloadList),
                        Mockito.eq(testDiscoveryDependencies));

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);
        assertEquals(BUILD_NAME, mResultBuild.getBuildTargetName());
        assertEquals("0", mResultBuild.getBuildId());
        assertTrue(mResultBuild instanceof FolderBuildInfo);
        File ctsRootDir = ((FolderBuildInfo) mResultBuild).getRootDir();
        assertTrue(ctsRootDir.getName().startsWith("cts_0_"));
        assertNotNull(FileUtil.findFile(ctsRootDir, CTS_FOLDER_NAME));
        assertNotNull(FileUtil.findFile(ctsRootDir, TEST_DISCOVERY_DEPENDENCY_FOLDER_NAME));
        assertNotNull(FileUtil.findFile(ctsRootDir, TEST_CONFIG_FILE_NAME));
        assertNotNull(FileUtil.findFile(ctsRootDir, TEST_JAR_FILE_NAME));
        assertNotNull(FileUtil.findFile(ctsRootDir, TEST_DEPENDENCY_FILE_NAME));
        // No full download should have been executed
        Mockito.verify(mMockFileDownloader, times(0)).downloadFile(Mockito.anyString());
        assertEquals(
                CtsLaunchControlProvider.PartialDownloadMode.PartialDownloadJarsAndConfigs,
                mProvider.getPartialDownloadMode());
    }

    /**
     * Test that {@link CtsLaunchControlProvider#downloadBuildFiles(RemoteBuildInfo, String, String,
     * IFileDownloader)} correctly fall back to fully download a {@link IBuildInfo} when partial
     * download is enabled but some dependency files failed to be successfully partially downloaded.
     */
    @Test
    public void testPartialDownload_FallbackToFullDownload_StagedTestModuleCheckFailure()
            throws Exception {
        mProvider.setCtsUsePartialDownload(true);
        mProvider.setCtsPackageName("fake-cts.zip");
        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");

        List<String> testDiscoveryDependencies = new ArrayList<>();
        testDiscoveryDependencies.add(
                CTS_FOLDER_NAME + "/" + TEST_DISCOVERY_DEPENDENCY_FOLDER_NAME);

        Map<String, List<String>> dependencies = new HashMap<>();
        List<String> testModuleNameList = new ArrayList<>();
        testModuleNameList.add(TEST_MODULE_NAME);
        List<String> testDependencyNameList = new ArrayList<>();
        testDependencyNameList.add(TEST_DEPENDENCY_FILE_NAME);
        dependencies.put(TestDiscoveryInvoker.TEST_MODULES_LIST_KEY, testModuleNameList);
        dependencies.put(TestDiscoveryInvoker.TEST_DEPENDENCIES_LIST_KEY, testDependencyNameList);

        // In case of fallback, the full download should correctly provide the full zip to extract
        when(mMockFileDownloader.downloadFile(Mockito.eq("fakepath"))).thenReturn(mCtsZip);

        when(mMockTestDiscoveryInvoker.discoverTestDependencies()).thenReturn(dependencies);

        // Mock first partial download of the /tools folder
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                File ctsRootDir = mock.getArgument(0);
                                File ctsZipFolder = new File(ctsRootDir, CTS_FOLDER_NAME);
                                assertTrue(ctsZipFolder.mkdir());
                                File testDiscoveryDependencyFolder =
                                        new File(
                                                ctsZipFolder,
                                                TEST_DISCOVERY_DEPENDENCY_FOLDER_NAME);
                                assertTrue(testDiscoveryDependencyFolder.mkdir());
                                return null;
                            }
                        })
                .when(mMockFileDownloader)
                .downloadZippedFiles(
                        Mockito.any(),
                        Mockito.eq("fakepath"),
                        Mockito.eq(testDiscoveryDependencies),
                        Mockito.anyList());

        // Mock the second download of the test dependencies, but no dependencies are downloaded
        List<String> partialDownloadList = new ArrayList<>();
        partialDownloadList.add(TEST_MODULE_NAME);
        partialDownloadList.add(TEST_DEPENDENCY_FILE_NAME);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                File ctsRootDir = mock.getArgument(0);
                                File ctsZipFolder = new File(ctsRootDir, CTS_FOLDER_NAME);
                                File testFolder = new File(ctsZipFolder, TEST_MODULE_FOLDER_NAME);
                                assertTrue(testFolder.mkdir());
                                // Skip file creation to simulation partial download failure
                                return null;
                            }
                        })
                .when(mMockFileDownloader)
                .downloadZippedFiles(
                        Mockito.any(),
                        Mockito.eq("fakepath"),
                        Mockito.eq(partialDownloadList),
                        Mockito.anyList());

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);
        assertEquals(BUILD_NAME, mResultBuild.getBuildTargetName());
        assertEquals("0", mResultBuild.getBuildId());
        assertTrue(mResultBuild instanceof FolderBuildInfo);
        File ctsRootDir = ((FolderBuildInfo) mResultBuild).getRootDir();
        assertTrue(ctsRootDir.getName().startsWith("cts_0_"));
        // A fallback to full download should happen
        Mockito.verify(mMockFileDownloader, times(1)).downloadFile("fakepath");
        assertEquals(
                CtsLaunchControlProvider.PartialDownloadMode.PartialDownloadFallbackToFullDownload,
                mProvider.getPartialDownloadMode());
    }

    @Test
    public void testPartialDownload_DisallowList() throws Exception {
        OptionSetter setter = new OptionSetter(mProvider);
        setter.setOptionValue("branch", "git_main");
        mProvider.setCtsPackageName("android-cts.zip");
        mProvider.setCtsUsePartialDownload(true);
        Set<String> disallowList = new HashSet<>();
        disallowList.add("android-cts.zip:git_main");
        mProvider.setPartialDownloadDisallowList(disallowList);

        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");

        when(mMockFileDownloader.downloadFile(Mockito.eq("fakepath"))).thenReturn(mCtsZip);

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);

        assertEquals(
                CtsLaunchControlProvider.PartialDownloadMode.PartialDownloadNotInUse,
                mProvider.getPartialDownloadMode());
    }

    @Test
    public void testPartialDownload_ForceDisablePartialDownloadExperiment() throws Exception {
        OptionSetter setter = new OptionSetter(mProvider);
        setter.setOptionValue("branch", "git_main");
        mProvider.setCtsPackageName("android-cts.zip");
        mProvider.setPartialDownloadRolloutArtifact("android-cts.zip");
        mProvider.setForceDisablePartialDownloadExperiment(true);

        // Legacy logic:
        mProvider.setCtsUsePartialDownload(false);
        mProvider.setDefaultRollout(false); // Legacy says no partial download

        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");

        when(mMockFileDownloader.downloadFile(Mockito.eq("fakepath"))).thenReturn(mCtsZip);

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);

        assertEquals(
                CtsLaunchControlProvider.PartialDownloadMode.PartialDownloadNotInUse,
                mProvider.getPartialDownloadMode());
    }

    @Test
    public void testPartialDownload_ForceDisablePartialDownloadExperiment_LegacyRollout()
            throws Exception {
        OptionSetter setter = new OptionSetter(mProvider);
        setter.setOptionValue("branch", "git_main");
        mProvider.setCtsPackageName("android-cts.zip");
        mProvider.setPartialDownloadRolloutArtifact("android-cts.zip");
        mProvider.setForceDisablePartialDownloadExperiment(true);

        // Legacy logic:
        // Set cts-use-partial-download to false so that the default rollout logic is triggered.
        // If it was true, it would just use partial download immediately.
        mProvider.setCtsUsePartialDownload(false);
        mProvider.setDefaultRollout(true);
        Set<String> defaultRollout = new HashSet<>();
        defaultRollout.add("android-cts.zip:git_main");
        mProvider.setPartialDownloadDefaultRollout(defaultRollout);

        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");

        when(mMockFileDownloader.downloadFile(Mockito.eq("fakepath"))).thenReturn(mCtsZip);
        // Throw exception to skip discovery and easily assert that partial download was attempted
        when(mMockTestDiscoveryInvoker.discoverTestDependencies())
                .thenThrow(new JSONException("Test Discovery failed"));

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);

        // It should attempt partial download due to default rollout list, but fall back to full
        // download.
        assertEquals(
                CtsLaunchControlProvider.PartialDownloadMode.PartialDownloadFallbackToFullDownload,
                mProvider.getPartialDownloadMode());
    }

    /**
     * Test that {@link CtsLaunchControlProvider#downloadBuildFiles(RemoteBuildInfo, String, String,
     * IFileDownloader)} correctly fall back to fully download a {@link IBuildInfo} when partial
     * download is enabled but test discovery failed.
     */
    @Test
    public void testPartialDownload_FallbackToFullDownload_ObservatoryInvocationFailure()
            throws Exception {
        mProvider.setCtsUsePartialDownload(true);
        mProvider.setCtsPackageName("fake-cts.zip");
        RemoteBuildInfo remoteBuild = new RemoteBuildInfo();
        remoteBuild.addAttribute(BuildAttributeKey.CTS, "fakepath");

        // In case of fallback, the full download should correctly provide the full zip to extract
        when(mMockFileDownloader.downloadFile(Mockito.eq("fakepath"))).thenReturn(mCtsZip);

        when(mMockTestDiscoveryInvoker.discoverTestDependencies())
                .thenThrow(new JSONException("Test Discovery failed"));

        mResultBuild =
                mProvider.downloadBuildFiles(
                        remoteBuild, TEST_TARGET, BUILD_NAME, mMockFileDownloader);

        assertEquals(BUILD_NAME, mResultBuild.getBuildTargetName());
        assertEquals("0", mResultBuild.getBuildId());
        assertTrue(mResultBuild instanceof FolderBuildInfo);
        File ctsRootDir = ((FolderBuildInfo) mResultBuild).getRootDir();
        assertTrue(ctsRootDir.getName().startsWith("cts_0_"));
        // No second partial download should have been attempted
        // There should be one partial download because of staging the /tools folder
        Mockito.verify(mMockFileDownloader, times(1))
                .downloadZippedFiles(
                        Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.anyList());
        // A full download fallback should happen
        Mockito.verify(mMockFileDownloader, times(1)).downloadFile("fakepath");
        assertEquals(
                CtsLaunchControlProvider.PartialDownloadMode.PartialDownloadFallbackToFullDownload,
                mProvider.getPartialDownloadMode());
    }
}
