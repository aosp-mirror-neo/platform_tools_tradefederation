/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.google.android.tradefed.config.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.remote.ExtendedFile;
import com.android.tradefed.config.remote.IRemoteFileResolver.RemoteFileResolverArgs;
import com.android.tradefed.config.remote.IRemoteFileResolver.ResolvedFile;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;

import com.google.android.tradefed.build.RemoteBuildInfo;
import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;
import com.google.common.base.Strings;
import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Run unit tests for {@link AndroidBuildRemoteFileResolver}. */
@RunWith(JUnit4.class)
public class AndroidBuildRemoteFileResolverTest {

    private static final String REMOTE_BUILD_FILES =
            "git_main-linux-test_suites_x86_64/7606894/manifest_P7606894.xml,"
                    + "git_main-linux-test_suites_x86_64/P7606894/module-info.json,"
                    + "some/path/subdir/pattern.txt";
    private AndroidBuildRemoteFileResolver mResolver;
    private IFileDownloader mMockDownloader;
    private RemoteBuildInfo mMockRemoteBuild;

    @BeforeClass
    public static void setUpClass() throws ConfigurationException {
        try {
            GlobalConfiguration.createGlobalConfiguration(new String[] {"empty"});
        } catch (IllegalStateException ignored) {
            // Ignored
        }
    }

    @Before
    public void setUp() {
        mMockDownloader = Mockito.mock(IFileDownloader.class);
        mMockRemoteBuild = Mockito.mock(RemoteBuildInfo.class);
        mResolver =
                new AndroidBuildRemoteFileResolver() {
                    @Override
                    protected IFileDownloader getAndroidBuildDownloader(RemoteBuildInfo info, boolean useCas) {
                        return mMockDownloader;
                    }

                    @Override
                    protected RemoteBuildInfo performBuildApiQuery(
                            String buildId, String branch, String buildFlavor, String pattern)
                            throws BuildRetrievalError {
                        return mMockRemoteBuild;
                    }
                };
    }

    @Test
    public void testDownloads() throws Exception {
        doReturn(REMOTE_BUILD_FILES).when(mMockRemoteBuild).getAttribute(BuildAttributeKey.FILES);

        RemoteFileResolverArgs args = new RemoteFileResolverArgs();
        args.setConsideredFile(new File("ab:/branch/target/bid/subdir/pattern.txt"));
        ResolvedFile resolved = mResolver.resolveRemoteFile(args);
        try {
            assertNotNull(resolved);
            File res = resolved.getResolvedFile();
            assertTrue(res instanceof ExtendedFile);
            ((ExtendedFile) res).waitForDownload();
        } finally {
            FileUtil.deleteFile(resolved.getResolvedFile());
        }
        verify(mMockDownloader)
                .downloadFile(Mockito.eq("some/path/subdir/pattern.txt"), Mockito.any());
    }

    @Test
    public void testDownloads_withRegex() throws Exception {
        doReturn(REMOTE_BUILD_FILES).when(mMockRemoteBuild).getAttribute(BuildAttributeKey.FILES);
        doReturn("bid").when(mMockRemoteBuild).getBuildId();

        // Use a pattern containing a regex
        RemoteFileResolverArgs args = new RemoteFileResolverArgs();
        args.setConsideredFile(new File("ab:/branch/target/bid/subdir/pat.*rn.txt"));
        ResolvedFile resolved = mResolver.resolveRemoteFile(args);
        try {
            assertNotNull(resolved);
            File res = resolved.getResolvedFile();
            assertTrue(res instanceof ExtendedFile);
            assertEquals("bid", ((ExtendedFile) res).getBuildId());
            ((ExtendedFile) res).waitForDownload();
        } finally {
            FileUtil.deleteFile(resolved.getResolvedFile());
        }
        verify(mMockDownloader)
                .downloadFile(Mockito.eq("some/path/subdir/pattern.txt"), Mockito.any());
    }

    @Test
    public void testDownloads_noMatch() throws Exception {
        doReturn(REMOTE_BUILD_FILES).when(mMockRemoteBuild).getAttribute(BuildAttributeKey.FILES);
        try {
            RemoteFileResolverArgs args = new RemoteFileResolverArgs();
            args.setConsideredFile(new File("ab:/branch/target/bid/subdir/pattern22.txt"));
            mResolver.resolveRemoteFile(args);
            fail("Should have thrown an exception.");
        } catch (BuildRetrievalError expected) {
            assertTrue(expected.getMessage().contains("match the requested pattern"));
        }
    }

    /**
     * Test when downloading is requested with no particular build-id, instead LATEST is requested.
     */
    @Test
    public void testPattern_noBuildid() throws Exception {
        mResolver =
                new AndroidBuildRemoteFileResolver() {
                    @Override
                    protected IFileDownloader getAndroidBuildDownloader(RemoteBuildInfo info, boolean useCas) {
                        return mMockDownloader;
                    }

                    @Override
                    protected RemoteBuildInfo performBuildApiQuery(
                            String buildId, String branch, String buildFlavor, String pattern)
                            throws BuildRetrievalError {
                        assertTrue(
                                String.format("build was '%s' instead of null or empty.", buildId),
                                Strings.isNullOrEmpty(buildId) || "null".equals(buildId));
                        return mMockRemoteBuild;
                    }
                };

        doReturn(REMOTE_BUILD_FILES).when(mMockRemoteBuild).getAttribute(BuildAttributeKey.FILES);

        RemoteFileResolverArgs args = new RemoteFileResolverArgs();
        args.setConsideredFile(new File("ab:/branch/target/LATEST/subdir/pattern.txt"));
        ResolvedFile resolved = mResolver.resolveRemoteFile(args);
        try {
            assertNotNull(resolved);
            File res = resolved.getResolvedFile();
            assertTrue(res instanceof ExtendedFile);
            ((ExtendedFile) res).waitForDownload();
        } finally {
            FileUtil.deleteFile(resolved.getResolvedFile());
        }
        verify(mMockDownloader)
                .downloadFile(Mockito.eq("some/path/subdir/pattern.txt"), Mockito.any());
    }

    @Test
    public void testPattern_badBuildId() throws Exception {
        mResolver =
                new AndroidBuildRemoteFileResolver() {
                    @Override
                    protected IFileDownloader getAndroidBuildDownloader(RemoteBuildInfo info, boolean useCas) {
                        return mMockDownloader;
                    }

                    @Override
                    protected RemoteBuildInfo performBuildApiQuery(
                            String buildId, String branch, String buildFlavor, String pattern)
                            throws BuildRetrievalError {
                        throw new BuildRetrievalError(
                                String.format("%s doesn't exist", buildId),
                                InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
                    }
                };
        String path = "ab:/branch/target/WRONG_BUILD/subdir/pattern.txt";
        RemoteFileResolverArgs args = new RemoteFileResolverArgs();
        args.setConsideredFile(new File(path));
        try {
            mResolver.resolveRemoteFile(args);
            fail("Should have thrown an exception.");
        } catch (BuildRetrievalError expected) {
            // Expected
            Truth.assertThat(expected)
                    .hasMessageThat()
                    .isEqualTo(
                            String.format(
                                    "Failed to download %s due to: "
                                            + "com.android.tradefed.build.BuildRetrievalError"
                                            + "[ARTIFACT_NOT_FOUND|500001|DEPENDENCY_ISSUE]: "
                                            + "WRONG_BUILD doesn't exist",
                                    path));
        }
    }

    @Test
    public void testDownloads_wrongPattern() throws Exception {
        RemoteFileResolverArgs args = new RemoteFileResolverArgs();
        args.setConsideredFile(new File("whatever/file"));
        assertNull(mResolver.resolveRemoteFile(args));
    }

    @Test
    public void testPartialFiles_failOnDirNotExist() throws Exception {
        try {
            Map<String, String> queryArgs = new HashMap<>();
            queryArgs.put("include_filters", "file");
            RemoteFileResolverArgs args = new RemoteFileResolverArgs();
            args.setConsideredFile(new File("ab:/branch/target/BUILD/whatever/pattern.txt"))
                    .setDestinationDir(new File("/non/exist/dir"))
                    .addQueryArgs(queryArgs);
            mResolver.resolveRemoteFile(args);
            fail("resolveRemoteFiles should fail when partial_download_dir doesn't exist.");
        } catch (BuildRetrievalError e) {
            // Expected
        }
    }

    @Test
    public void testPartialFiles_dirParamSet_downloadsToDir() throws Exception {
        doReturn(REMOTE_BUILD_FILES).when(mMockRemoteBuild).getAttribute(BuildAttributeKey.FILES);
        final String remoteFile = "ab:/branch/target/build/subdir/pattern.txt";

        File tmpDir = null;
        try {
            tmpDir = FileUtil.createTempDir("partial-download");

            Map<String, String> queryArgs = new HashMap<>();
            queryArgs.put(AndroidBuildRemoteFileResolver.INCLUDE_FILTERS, "f1;f2");
            queryArgs.put(AndroidBuildRemoteFileResolver.EXCLUDE_FILTERS, "exf1;exf2");
            List<String> includeFilters = Arrays.asList("f1", "f2");
            List<String> excludeFilters = Arrays.asList("exf1", "exf2");
            RemoteFileResolverArgs args = new RemoteFileResolverArgs();
            args.setConsideredFile(new File(remoteFile))
                    .setDestinationDir(tmpDir)
                    .addQueryArgs(queryArgs);
            mResolver.resolveRemoteFile(args);
            verify(mMockDownloader)
                    .downloadZippedFiles(
                            Mockito.eq(tmpDir),
                            Mockito.eq("some/path/subdir/pattern.txt"),
                            Mockito.eq(includeFilters),
                            Mockito.eq(excludeFilters));

        } finally {
            FileUtil.recursiveDelete(tmpDir);
        }
    }

    @Test
    public void testPartialFiles_dirParamNotSet_downloadsToTempDir() throws Exception {
        doReturn(REMOTE_BUILD_FILES).when(mMockRemoteBuild).getAttribute(BuildAttributeKey.FILES);
        final String remoteFile = "ab:/branch/target/build/subdir/pattern.txt";
        Map<String, String> queryArgs = new HashMap<>();
        queryArgs.put(AndroidBuildRemoteFileResolver.INCLUDE_FILTERS, "f1;f2");
        queryArgs.put(AndroidBuildRemoteFileResolver.EXCLUDE_FILTERS, "exf1;exf2");
        List<String> includeFilters = Arrays.asList("f1", "f2");
        List<String> excludeFilters = Arrays.asList("exf1", "exf2");

        RemoteFileResolverArgs args = new RemoteFileResolverArgs();
        args.setConsideredFile(new File(remoteFile)).addQueryArgs(queryArgs);
        ResolvedFile resolvedFile = mResolver.resolveRemoteFile(args);
        File dir = resolvedFile.getResolvedFile();
        try {
            verify(mMockDownloader)
                    .downloadZippedFiles(
                            Mockito.any(),
                            Mockito.eq("some/path/subdir/pattern.txt"),
                            Mockito.eq(includeFilters),
                            Mockito.eq(excludeFilters));

            assertTrue(dir.isDirectory());
        } finally {
            FileUtil.recursiveDelete(dir);
        }
    }
}
