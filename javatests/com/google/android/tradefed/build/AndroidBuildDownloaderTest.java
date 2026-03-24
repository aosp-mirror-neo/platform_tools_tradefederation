// Copyright 2018 Google Inc. All Rights Reserved.
package com.google.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.host.HostOptions;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RemoteZip;
import com.android.tradefed.util.ZipUtilTest;

import com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

/** Unit tests for {@link AndroidBuildDownloader}. */
@RunWith(JUnit4.class)
public class AndroidBuildDownloaderTest {

    private static final String REMOTE_FILE =
            "aosp_main-linux-yakju-userdebug/P123/device-tests.zip";

    private AndroidBuildDownloader mDownloader;
    private AndroidBuildFetcher mMockFetcher;
    private HostOptions mHostOptions;
    private static BuildArtifactMetadata mExpectedMetadata;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        try {
            GlobalConfiguration.getInstance();
        } catch (IllegalStateException e) {
            GlobalConfiguration.createGlobalConfiguration(new String[] {});
        }

        final InputStream inputStream =
                ZipUtilTest.class.getResourceAsStream("/util/partial_zip.zip");
        long zipSize = 0;
        long count;
        byte[] bytes = new byte[1024];
        while ((count = inputStream.read(bytes)) > 0) {
            zipSize += count;
        }
        mExpectedMetadata = new BuildArtifactMetadata().setSize(zipSize);
    }

    @Before
    public void setUp() throws Exception {
        mMockFetcher = Mockito.mock(AndroidBuildFetcher.class);
        mHostOptions = new HostOptions();
        mDownloader = new TestableAndroidBuildDownloader();

        Mockito.when(
                        mMockFetcher.getArtifactMetadata(
                                Mockito.eq("yakju-userdebug"),
                                Mockito.eq("P123"),
                                Mockito.eq("device-tests.zip"),
                                Mockito.eq("latest")))
                .thenReturn(mExpectedMetadata);

        Mockito.doAnswer(
                        (Answer<?>)
                                invocation -> {
                                    File destFile = (File) invocation.getArgument(4);
                                    long startOffset = (long) invocation.getArgument(5);
                                    long size = (long) invocation.getArgument(6);
                                    saveTestDataFile(destFile, startOffset, size);
                                    return null;
                                })
                .when(mMockFetcher)
                .fetchArtifact(
                        Mockito.eq("yakju-userdebug"),
                        Mockito.eq("P123"),
                        Mockito.eq("device-tests.zip"),
                        Mockito.eq("latest"),
                        Mockito.any(File.class),
                        Mockito.anyLong(),
                        Mockito.anyLong());
    }

    class TestableAndroidBuildDownloader extends AndroidBuildDownloader {
        @Override
        protected AndroidBuildFetcher getBuildFetcher()
                throws IOException, GeneralSecurityException {
            return mMockFetcher;
        }

        @Override
        IHostOptions getHostOptions() {
            return mHostOptions;
        }
    }

    private void saveTestDataFile(File destFile, long startOffset, long size) {
        try {
            final InputStream inputStream =
                    ZipUtilTest.class.getResourceAsStream("/util/partial_zip.zip");
            FileUtil.writeToFile(inputStream, destFile, false, startOffset, size);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testDownload() throws Exception {
        Mockito.when(
                        mMockFetcher.getArtifactMetadata(
                                Mockito.eq("phone-user"),
                                Mockito.eq("2425"),
                                Mockito.eq("userdata.img"),
                                Mockito.eq("latest")))
                .thenReturn(mExpectedMetadata);
        File f = null;
        try {
            f = mDownloader.downloadFile("userdata:git_main-linux-phone-user/2425/userdata.img");
            assertNotNull(f);
        } finally {
            FileUtil.deleteFile(f);
        }
    }

    @Test
    public void testDownload_exception() throws Exception {
        File f = null;
        Mockito.doThrow(
                        new BuildRetrievalError(
                                "File doesn't exist.",
                                InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR))
                .when(mMockFetcher)
                .fetchArtifact(
                        Mockito.eq("yakju-userdebug"),
                        Mockito.eq("P123"),
                        Mockito.eq("device-tests.zip"),
                        Mockito.eq("latest"),
                        Mockito.any(File.class),
                        Mockito.anyLong(),
                        Mockito.anyLong());
        try {
            f = mDownloader.downloadFile(REMOTE_FILE);
            fail("Expected BuildRetrievalError not thrown.");
        } catch (BuildRetrievalError e) {
            assertEquals(InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR, e.getErrorId());
        } finally {
            FileUtil.deleteFile(f);
        }
    }

    @Test
    public void testGetMetaData() throws Exception {
        BuildArtifactMetadata metadata = mDownloader.getMetadata(REMOTE_FILE);
        assertEquals(mExpectedMetadata.getSize(), metadata.getSize());
    }

    @Test
    public void testDownloadZippedFiles() throws Exception {
        File destDir = null;
        try {
            destDir = FileUtil.createTempDir("test");

            List<String> includeFilters = Arrays.asList("/large_text/|/executable/|");
            List<String> excludeFilters = Arrays.asList(".*/executable_file");

            mDownloader.downloadZippedFiles(destDir, REMOTE_FILE, includeFilters, excludeFilters);
            File targetFile =
                    new File(Paths.get(destDir.getPath(), "large_text", "file.txt").toString());
            assertEquals(4146093769L, FileUtil.calculateCrc32(targetFile));
            targetFile =
                    new File(
                            Paths.get(destDir.getPath(), "executable", "executable_file")
                                    .toString());
            assertFalse(targetFile.exists());
        } finally {
            FileUtil.recursiveDelete(destDir);
        }
    }

    @Test
    public void testDownloadZippedFiles_withLinks_feature_not_enabled() throws Exception {
        File destDir = null;
        try {
            destDir = FileUtil.createTempDir("test");

            List<String> includeFilters = Arrays.asList("/large_text_derived2/");
            List<String> excludeFilters = Arrays.asList(".*/executable_file");

            mDownloader.downloadZippedFiles(destDir, REMOTE_FILE, includeFilters, excludeFilters);
            assertEquals(
                    "../large_text_derived/file.txt",
                    Files.readSymbolicLink(
                                    Paths.get(
                                            destDir.getPath(),
                                            "symlinks/large_text_derived2",
                                            "file_derived_2.txt"))
                            .toString());
            assertFileNotExist(Paths.get(destDir.getPath(), "large_text", "file.txt"));
        } finally {
            FileUtil.recursiveDelete(destDir);
        }
    }

    @Test
    public void testDownloadZippedFiles_withLinks() throws Exception {
        mDownloader.setResolveSymlinks(true);
        File destDir = null;
        try {
            destDir = FileUtil.createTempDir("test");

            List<String> includeFilters = Arrays.asList("/large_text_derived2/");
            List<String> excludeFilters = Arrays.asList(".*/executable_file");

            mDownloader.downloadZippedFiles(destDir, REMOTE_FILE, includeFilters, excludeFilters);

            assertFileCRC(
                    4146093769L,
                    Paths.get(
                            destDir.getPath(),
                            "symlinks/large_text_derived2",
                            "file_derived_2.txt"));
            assertFileNotExist(Paths.get(destDir.getPath(), "large_text", "file.txt"));
        } finally {
            FileUtil.recursiveDelete(destDir);
        }
    }

    @Test
    public void testDownloadZippedFiles_withLinks_extractAll() throws Exception {
        mDownloader.setResolveSymlinks(true);
        File destDir = null;
        try {
            destDir = FileUtil.createTempDir("test");

            List<String> includeFilters = Arrays.asList("/large_text/|/executable/|");
            List<String> excludeFilters = Arrays.asList(".*/executable_file");

            mDownloader.downloadZippedFiles(destDir, REMOTE_FILE, includeFilters, excludeFilters);

            assertFileCRC(4146093769L, Paths.get(destDir.getPath(), "large_text", "file.txt"));
            assertFileCRC(
                    4146093769L,
                    Paths.get(destDir.getPath(), "symlinks/large_text_derived", "file.txt"));
            assertFileCRC(
                    4146093769L,
                    Paths.get(
                            destDir.getPath(),
                            "symlinks/large_text_derived2",
                            "file_derived_2.txt"));
        } finally {
            FileUtil.recursiveDelete(destDir);
        }
    }

    @Test
    public void testDownloadZippedFiles_withLinks_chainResolve() throws Exception {
        mDownloader.setResolveSymlinks(true);
        File destDir = null;
        try {
            destDir = FileUtil.createTempDir("test");

            List<String> includeFilters = Arrays.asList("symlinks");
            List<String> excludeFilters = Arrays.asList(".*/executable_file");

            mDownloader.downloadZippedFiles(destDir, REMOTE_FILE, includeFilters, excludeFilters);

            assertFileCRC(
                    4146093769L,
                    Paths.get(destDir.getPath(), "symlinks/large_text_derived", "file.txt"));
            assertFileCRC(
                    4146093769L,
                    Paths.get(
                            destDir.getPath(),
                            "symlinks/large_text_derived2",
                            "file_derived_2.txt"));
            assertFileNotExist(Paths.get(destDir.getPath(), "large_text", "file.txt"));
        } finally {
            FileUtil.recursiveDelete(destDir);
        }
    }

    @Test
    public void testDownloadZippedFiles_withLinks_brokenChain() throws Exception {
        mDownloader.setResolveSymlinks(true);
        File destDir = null;
        try {
            destDir = FileUtil.createTempDir("test");

            List<String> includeFilters = Arrays.asList("large_text");
            List<String> excludeFilters = Arrays.asList("/large_text_derived/");

            mDownloader.downloadZippedFiles(destDir, REMOTE_FILE, includeFilters, excludeFilters);

            assertFileCRC(4146093769L, Paths.get(destDir.getPath(), "large_text", "file.txt"));
            assertFileCRC(
                    4146093769L,
                    Paths.get(
                            destDir.getPath(),
                            "symlinks/large_text_derived2",
                            "file_derived_2.txt"));
            assertFileNotExist(
                    Paths.get(destDir.getPath(), "symlinks/large_text_derived", "file.txt"));
        } finally {
            FileUtil.recursiveDelete(destDir);
        }
    }

    @Test
    public void testCleanupRemoteZipCache() throws Exception {
        RemoteZip remoteZip1 = new RemoteZip("fiel1", 0, null);
        remoteZip1.setLastAccess(System.currentTimeMillis() - 24 * 3600 * 1000);
        RemoteZip remoteZip2 = new RemoteZip("fiel2", 0, null);
        AndroidBuildDownloader.REMOTE_ZIP_CACHE.putIfAbsent(
                remoteZip1.getRemoteFilePath(), remoteZip1);
        AndroidBuildDownloader.REMOTE_ZIP_CACHE.putIfAbsent(
                remoteZip2.getRemoteFilePath(), remoteZip2);
        AndroidBuildDownloader.cleanupRemoteZipCache();
        assertFalse(
                AndroidBuildDownloader.REMOTE_ZIP_CACHE.containsKey(
                        remoteZip1.getRemoteFilePath()));
        assertTrue(
                AndroidBuildDownloader.REMOTE_ZIP_CACHE.containsKey(
                        remoteZip2.getRemoteFilePath()));
    }

    private void assertFileCRC(long expectedCRC, Path path) throws IOException {
        assertEquals(expectedCRC, FileUtil.calculateCrc32(new File(path.toString())));
    }

    private void assertFileNotExist(Path path) {
        assertFalse(new File(path.toString()).exists());
    }
}
