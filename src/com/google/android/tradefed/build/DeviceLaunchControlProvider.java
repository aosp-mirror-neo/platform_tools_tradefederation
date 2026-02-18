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
package com.google.android.tradefed.build;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.IDeviceBuildProvider;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.build.content.ContentAnalysisContext;
import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.build.content.ContentInformation;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceManager.FastbootDevice;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NullDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.ExecutionFiles.FilesKey;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.TracePropagatingExecutorService;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.observatory.IDiscoverDependencies;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.FlashingResourcesParser;
import com.android.tradefed.targetprep.IDeviceFlasher;
import com.android.tradefed.targetprep.IFlashingResourcesParser;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IDisableable;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.StaticDeviceInfoUtil;
import com.android.tradefed.util.ZipUtil2;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;
import com.google.android.tradefed.build.content.ContentBasedHelper;
import com.google.android.tradefed.build.content.ContentMappings;
import com.google.common.base.Strings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A {@link LaunchControlProvider} for {@link IDeviceBuildInfo} */
@OptionClass(alias = "device-launch-control")
public class DeviceLaunchControlProvider extends LaunchControlProvider
        implements IDeviceBuildProvider {
    private static final String MKBOOTIMG_NAME = "mkbootimg";
    private static final String BOOTLOADER_FILTER = "bootloader\\.img$";
    private static final String BASEBAND_FILTER = "radio\\.img$";
    private static final String TESTS_ZIP_PREFIX = "tests-zip_";

    @Option(
            name = "download-build-file",
            description = "Files to download from the build " + "server.  May be repeated.")
    private Collection<BuildAttributeKey> mDownloadKeys =
            new HashSet<BuildAttributeKey>(
                    ArrayUtil.list(
                            BuildAttributeKey.DEVICE_IMAGE, BuildAttributeKey.TESTS_ZIP,
                            BuildAttributeKey.BOOTLOADER, BuildAttributeKey.BASEBAND));

    @Option(
            name = "test-zip-file-filter",
            description =
                    "Regex to specify the test zip to download. "
                            + "If set, the first test zip matching this filter will be downloaded "
                            + "instead of the default test zip.")
    private String mTestZipFileFilter = null;

    @Option(
            name = "error-if-zip-filter-mismatch",
            description = "Throw an error when the zip file filter didn't match any test")
    private boolean mErrorIfZipFilterMismatch = false;

    @Option(
            name = "test-zip-partial-download-filter",
            description =
                    "Queries to be passed to test zip download "
                            + "to allow partially download parts of the test zip file. The "
                            + "values shall be regex pattern to match the file path inside the "
                            + "zip file, e.g., crashcollector/.*")
    private List<String> mTestZipPartialDownloadFilters = new LinkedList<>();

    @Option(
            name = "auto-populate-partial-download-filter",
            description = "Attempt to populate test-zip-partial-download-filter automatically.")
    private boolean mAutoPopulatePartialDownload = false;

    @Option(name = "skip-download", description = "Skip download of given file. May be repeated.")
    private Set<BuildAttributeKey> mSkipDownloadKeys = new LinkedHashSet<BuildAttributeKey>();

    @Option(
            name = "skip-if-same",
            description =
                    "Do not proceed with invocation if device is "
                            + "already running requested build")
    private boolean mSkipIfSameBuild = false;

    @Option(
            name = "bootstrap-build-info",
            description =
                    "When set, attempt to bootstrap build info from device, then fallback to"
                            + " provided branch and build information; using this option will"
                            + " implicitly use NOTIFY-TEST-CL or QUERY-LATEST-CL as query type")
    private boolean mBootstrapBuildInfo = false;

    @Option(
            name = "bootloader-filter",
            description =
                    "Regex to specify the bootloader image to download. If set, the first file in"
                        + " files matching this filter will be downloaded and used as bootloader"
                        + " image.")
    private String mBootloaderFilter = BOOTLOADER_FILTER;

    @Option(
            name = "baseband-filter",
            description =
                    "Regex to specify the baseband image to download. If set, the first file in"
                            + " files matching this filter will be downloaded and used as baseband"
                            + " image.")
    private String mBasebandFilter = BASEBAND_FILTER;

    @Option(
            name = "ramdisk-file",
            description =
                    "the name of the ramdisk file in build artifacts; setting this option also "
                            + "implicitly adds ramdisk file to list of files to download")
    private String mRamdiskFile = null;

    @Option(
            name = "extra-host-shared-lib-zip",
            description =
                    "Name of the zip file containing the host shared libraries. This will be "
                            + "enabled only when the partial download is set.")
    private String mExtraHostSharedLibZip = null;

    @Option(
            name = "check-modified-files",
            description = "Collect information from modified_files.json on what changed.")
    private boolean mCheckModifiedFiles = true;

    @Option(
            name = "alt-static-device-info-class",
            description = "Alternative class to use for fetching static device info.")
    private String mAltStaticDeviceInfoClass = null;

    @Option(
            name = "device-flasher-class",
            description = "Name of the IDeviceFlasher class that will be used to flash the device.")
    private String mDeviceFlasherClass = null;

    /** stores the current device build's id */
    private String mCurrentBuildId = null;

    private boolean mAlreadyEvaluated = false;
    private ITestDevice mDevice;

    private Future<Boolean> futureContentDownload;
    private ExecutorService mFutureContentDownloadExecutorService;

    protected void setDevice(ITestDevice device) {
        mDevice = device;
    }

    public ITestDevice getDevice() {
        return mDevice;
    }

    public DeviceLaunchControlProvider() {
        super();
        if (!Strings.isNullOrEmpty(mAltStaticDeviceInfoClass)) {
            StaticDeviceInfoUtil.setStaticDeviceInfoClass(mAltStaticDeviceInfoClass);
        }
    }

    String fetchTestsZipPath(RemoteBuildInfo remoteBuild) throws BuildRetrievalError {
        if (mTestZipFileFilter == null || mTestZipFileFilter.isEmpty()) {
            return remoteBuild.getAttribute(BuildAttributeKey.TESTS_ZIP);
        }
        // if mTestZipFileFilter was specified, search through all files to locate the new
        // test zip file.
        final String filesPathCsvString = remoteBuild.getAttribute(BuildAttributeKey.FILES);
        if (filesPathCsvString == null) {
            return null;
        }
        String[] filePaths;
        if (filesPathCsvString.isEmpty()) {
            filePaths = new String[] {};
        } else {
            filePaths = filesPathCsvString.split(",");
        }
        for (String filePath : filePaths) {
            if (filePath.matches(mTestZipFileFilter)) {
                CLog.d("Found matching test zip file %s", filePath);
                return filePath;
            }
        }
        if (shouldDownload(BuildAttributeKey.TESTS_ZIP)) {
            CLog.w("Did not find matching test zip file for regex %s", mTestZipFileFilter);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.ZIP_PATTERN_NO_MATCH, 1);
            if (mErrorIfZipFilterMismatch) {
                throw new BuildRetrievalError(
                        String.format(
                                "Did not find matching test zip file for regex %s.",
                                mTestZipFileFilter),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    protected void convertBuildToSigned(RemoteBuildInfo remoteBuild) throws BuildRetrievalError {
        // TODO: check that a signed build exists, error if there's no signed build
        replaceParamWithSigned(remoteBuild, BuildAttributeKey.DEVICE_IMAGE);
        replaceParamWithSigned(remoteBuild, BuildAttributeKey.TARGET_FILES);
        replaceParamWithSigned(remoteBuild, BuildAttributeKey.OTA_PACKAGE);
    }

    @Override
    protected IBuildInfo downloadBuildFiles(
            RemoteBuildInfo remoteBuild,
            String testTargetName,
            String buildName,
            IFileDownloader downloader)
            throws BuildRetrievalError {
        String buildId = remoteBuild.getBuildId();
        IDeviceBuildInfo localBuild = new DeviceBuildInfo(buildId, buildName);
        localBuild.setBuildFlavor(getBuildFlavor());
        localBuild.setBuildBranch(getBranch());
        localBuild.setTestTag(testTargetName);
        if (mRamdiskFile != null) {
            mDownloadKeys.add(BuildAttributeKey.RAMDISK);
        }
        try {
            evaluateDeviceImageChanges(remoteBuild, downloader, localBuild);
            final String deviceImgPath = remoteBuild.getAttribute(BuildAttributeKey.DEVICE_IMAGE);
            if (shouldDownload(BuildAttributeKey.DEVICE_IMAGE)) {
                if (deviceImgPath == null) {
                    throw new BuildRetrievalError(
                            "Device image path (updater) not found in RemoteBuildInfo.",
                            InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
                }
                localBuild.setDeviceImageFile(
                        handleDirectoryDownload(downloader.downloadFile(deviceImgPath)), buildId);
            }
            final String userdataPath = remoteBuild.getAttribute(BuildAttributeKey.USER_DATA);
            if (userdataPath != null && shouldDownload(BuildAttributeKey.USER_DATA)) {
                localBuild.setUserDataImageFile(downloader.downloadFile(userdataPath), buildId);
            }

            String testsZipPath = fetchTestsZipPath(remoteBuild);
            String originalPath = testsZipPath;
            if (testsZipPath != null && shouldDownload(BuildAttributeKey.TESTS_ZIP)) {
                File extraHostSharedLib = null;
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.AB_TEST_ZIP_NAME,
                        getBuildFlavor() + ":" + new File(testsZipPath).getName());
                if (getTestConfigOnlyZip() != null) {
                    // Add the test zip file filter to remote zip filter for delay download.
                    addRemoteZipFilter(mTestZipFileFilter);
                    // Only enable getting extra host shared libraries when partial download is set.
                    if (mExtraHostSharedLibZip != null) {
                        String artifact = getPathForPattern(remoteBuild, mExtraHostSharedLibZip);
                        if (artifact == null) {
                            throw new BuildRetrievalError(
                                    String.format(
                                            "Failed to find test zip %s.", mExtraHostSharedLibZip),
                                    InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
                        }
                        extraHostSharedLib = downloader.downloadFile(artifact);
                        localBuild.setFile(mExtraHostSharedLibZip, extraHostSharedLib, buildId);
                    }
                    // Overwrite tests zip path with the path of the test config zip.
                    testsZipPath = getPathForPattern(remoteBuild, getTestConfigOnlyZip());
                    if (testsZipPath == null) {
                        throw new BuildRetrievalError(
                                String.format(
                                        "Failed to find test config only zip %s.",
                                        getTestConfigOnlyZip()),
                                InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
                    }
                }
                File testsZip = null;
                try {
                    File testsDir = null;
                    List<String> filters = new ArrayList<>();
                    filters.addAll(mTestZipPartialDownloadFilters);
                    if (mAutoPopulatePartialDownload) {
                        filters.addAll(discoverDependencies(getConfiguration()));
                    }
                    if (filters.isEmpty()) {
                        testsZip = downloader.downloadFile(testsZipPath);
                        if (testsZip.isFile()) {
                            try {
                                testsDir = extractZip(testsZip, TESTS_ZIP_PREFIX);
                            } catch (IOException e) {
                                // clear cache if we can't unzip the downloaded file.
                                deleteCacheEntry(testsZipPath);
                                throw e;
                            }
                        } else {
                            // Already unzipped
                            testsDir = testsZip;
                        }
                    } else {
                        CLog.d(
                                "Attempting partial downloading of tests zip '%s' for '%s'",
                                testsZipPath, filters);
                        testsDir =
                                partialDownload(
                                        testsZipPath, TESTS_ZIP_PREFIX, filters, downloader);
                    }
                    if (extraHostSharedLib != null) {
                        if (extraHostSharedLib.isFile()) {
                            CLog.i("Extracting extra host shared libraries to %s", testsDir);
                            ZipUtil2.extractZip(extraHostSharedLib, testsDir);
                        } else {
                            CLog.i("Linking extra host shared libraries directory to %s", testsDir);
                            FileUtil.recursiveHardlink(extraHostSharedLib, testsDir, false);
                        }
                    }
                    localBuild.setTestsDir(testsDir, buildId);
                    if (CurrentInvocation.getInvocationFiles() != null) {
                        CurrentInvocation.getInvocationFiles()
                                .put(FilesKey.TESTS_DIRECTORY, testsDir);
                    }
                    if (shouldReportContentInformation()) {
                        String entryName = new File(originalPath).getName();
                        AnalysisMethod method = AnalysisMethod.FILE;
                        if (ContentMappings.WORKDIR_COMMON_DIR.containsKey(entryName)) {
                            method = AnalysisMethod.SANDBOX_WORKDIR;
                        }
                        if (!ContentBasedHelper.hasCasDetails(remoteBuild)) {
                            ContentAnalysisContext context =
                                    new ContentAnalysisContext(entryName, null, method);
                            context.invalidateAnalysis();
                            getConfiguration().getSkipManager().setTestArtifactsAnalysis(context);
                        } else {
                            ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
                            ThreadFactory factory =
                                    new ThreadFactory() {
                                        @Override
                                        public Thread newThread(Runnable r) {
                                            Thread t = new Thread(currentGroup, r, "DLCP-content");
                                            t.setDaemon(true);
                                            return t;
                                        }
                                    };
                            mFutureContentDownloadExecutorService =
                                    TracePropagatingExecutorService.create(
                                            Executors.newFixedThreadPool(1, factory));
                            futureContentDownload =
                                    CompletableFuture.supplyAsync(
                                            () ->
                                                    wrapContentInformationDownload(
                                                            remoteBuild, buildId, originalPath),
                                            mFutureContentDownloadExecutorService);
                        }
                    }
                } finally {
                    FileUtil.deleteFile(testsZip);
                }
            }

            final String otaPackagePath = remoteBuild.getAttribute(BuildAttributeKey.OTA_PACKAGE);
            if (otaPackagePath != null && shouldDownload(BuildAttributeKey.OTA_PACKAGE)) {
                localBuild.setOtaPackageFile(downloader.downloadFile(otaPackagePath), buildId);
            }

            // TODO: Get mkbootimg path from remoteBuild instead of constructing it.
            final String mkbootimgPath =
                    createPathForImage(buildName, remoteBuild.getBuildId(), MKBOOTIMG_NAME);
            if (shouldDownload(BuildAttributeKey.MKBOOTIMG)) {
                try {
                    localBuild.setMkbootimgFile(downloader.downloadFile(mkbootimgPath), buildId);
                } catch (BuildRetrievalError e) {
                    // Do nothing
                    CLog.i("mkbootimg not found on build server. Ignoring.");
                }
            }

            // TODO: Get ramdisk path from remoteBuild instead of constructing it.
            final String ramdiskPath =
                    createPathForImage(buildName, remoteBuild.getBuildId(), mRamdiskFile);
            if (shouldDownload(BuildAttributeKey.RAMDISK)) {
                try {
                    localBuild.setFile("ramdisk", downloader.downloadFile(ramdiskPath), buildId);
                } catch (BuildRetrievalError e) {
                    // Do nothing
                    CLog.i("ramdisk.img not found on build server. Ignoring.");
                }
            }

            String bootloaderVersion = buildId;
            String basebandVersion = buildId;
            if (shouldDownload(BuildAttributeKey.DEVICE_IMAGE)) {
                try {
                    IFlashingResourcesParser flashingResourcesParser =
                            new FlashingResourcesParser(localBuild.getDeviceImageFile());
                    bootloaderVersion = flashingResourcesParser.getRequiredBootloaderVersion();
                    basebandVersion = flashingResourcesParser.getRequiredBasebandVersion();
                } catch (TargetSetupError e) {
                    // delete the image from the cache
                    deleteCacheEntry(deviceImgPath);
                    throw new BuildRetrievalError(
                            "Failed to get image info from android-info.txt",
                            e,
                            InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
                }
            }
            List<DownloadFileDescriptor> toBeDownloaded = new ArrayList<>();
            // If the build artifact does not have bootloader or baseband images,
            // the flash resource retriever will try to download it from build api.
            if (shouldDownload(BuildAttributeKey.BOOTLOADER)) {
                final String bootloaderPath = getPathForPattern(remoteBuild, mBootloaderFilter);
                if (bootloaderPath == null) {
                    CLog.d("There is no %s in files", mBootloaderFilter);
                } else {
                    DownloadFileDescriptor bootloader =
                            new DownloadFileDescriptor(
                                    bootloaderPath,
                                    BuildInfoFileKey.BOOTLOADER_IMAGE.getFileKey(),
                                    false,
                                    bootloaderVersion,
                                    true);
                    toBeDownloaded.add(bootloader);
                }
            }

            if (shouldDownload(BuildAttributeKey.BASEBAND)) {
                final String basebandPath = getPathForPattern(remoteBuild, mBasebandFilter);
                if (basebandPath == null) {
                    CLog.d("There is no %s in files", mBasebandFilter);
                } else {
                    DownloadFileDescriptor bootloader =
                            new DownloadFileDescriptor(
                                    basebandPath,
                                    BuildInfoFileKey.BASEBAND_IMAGE.getFileKey(),
                                    false,
                                    basebandVersion,
                                    true);
                    toBeDownloaded.add(bootloader);
                }
            }
            if (!isSecondaryParallelDownload()) {
                for (DownloadFileDescriptor file : toBeDownloaded) {
                    try {
                        File downloaded = downloader.downloadFile(file.mDownloadPath);
                        localBuild.setFile(file.mKeyInBuildInfo, downloaded, file.mVersion);
                    } catch (BuildRetrievalError e) {
                        if (file.mMandatory) {
                            throw e;
                        }
                        CLog.w("%s not found on build server. Ignoring.", file.mDownloadPath);
                    }
                }
            } else {
                List<Entry<DownloadFileDescriptor, File>> downloaded =
                        downloadFilesNonBlocking(downloader, toBeDownloaded);
                for (Entry<DownloadFileDescriptor, File> entry : downloaded) {
                    String label = entry.getKey().mKeyInBuildInfo;
                    CLog.d("label for %s is %s", entry.getKey().mDownloadPath, label);
                    localBuild.setFile(label, entry.getValue(), entry.getKey().mVersion);
                }
            }
        } catch (BuildRetrievalError e) {
            // one or more packages failed to download - clean up any successfully downloaded files
            localBuild.cleanUp();
            e.setBuildInfo(localBuild);
            if (futureContentDownload != null) {
                futureContentDownload.cancel(true);
            }
            throw e;
        } catch (IOException e) {
            localBuild.cleanUp();
            BuildRetrievalError be =
                    new BuildRetrievalError(
                            String.format(
                                    "IO error (%s) when retrieving device build", e.toString()),
                            e,
                            InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
            be.setBuildInfo(localBuild);
            if (futureContentDownload != null) {
                futureContentDownload.cancel(true);
            }
            throw be;
        } catch (RuntimeException e) {
            // one or more packages failed to download - clean up any successfully downloaded files
            localBuild.cleanUp();
            if (futureContentDownload != null) {
                futureContentDownload.cancel(true);
            }
            throw e;
        } finally {
            if (mFutureContentDownloadExecutorService != null) {
                if (futureContentDownload != null) {
                    Exception error = null;
                    try {
                        futureContentDownload.get();
                    } catch (InterruptedException | ExecutionException e) {
                        error = new BuildRetrievalError(e.getMessage(), e);
                    }
                    if (error != null) {
                        CLog.e(error);
                    }
                }
                mFutureContentDownloadExecutorService.shutdown();
            }
        }
        return localBuild;
    }

    private boolean wrapContentInformationDownload(
            RemoteBuildInfo remoteBuild, String buildId, String originalPath) {
        String entryName = new File(originalPath).getName();
        AnalysisMethod method = AnalysisMethod.FILE;
        if (ContentMappings.WORKDIR_COMMON_DIR.containsKey(entryName)) {
            method = AnalysisMethod.SANDBOX_WORKDIR;
        }
        if (!ContentBasedHelper.hasCasDetails(remoteBuild)) {
            ContentAnalysisContext context = new ContentAnalysisContext(entryName, null, method);
            context.invalidateAnalysis();
            getConfiguration().getSkipManager().setTestArtifactsAnalysis(context);
        } else {
            ContentInformation info =
                    ContentBasedHelper.getContentInformation(
                            remoteBuild, getBranch(), getBuildFlavor(), buildId);
            ContentAnalysisContext context = new ContentAnalysisContext(entryName, info, method);
            if (AnalysisMethod.SANDBOX_WORKDIR.equals(method)) {
                if (ContentMappings.WORKDIR_COMMON_DIR.containsKey(entryName)) {
                    context.addCommonLocations(ContentMappings.WORKDIR_COMMON_DIR.get(entryName));
                } else {
                    context.invalidateAnalysis();
                }
            }
            if (info == null) {
                context.invalidateAnalysis();
            }
            getConfiguration().getSkipManager().setTestArtifactsAnalysis(context);
        }
        return true;
    }

    /**
     * Partial download the given zip file to a local dir.
     *
     * @param zipPath path to the File to partial download.
     * @param prefix the prefix of the temporary directory where to save the partial downloaded
     *     file.
     * @param filters the filters to match to do partial download.
     * @param downloader the {@link IFileDownloader} object to download remote file.
     * @return the {@link File} referencing the zip output.
     * @throws IOException
     * @throws BuildRetrievalError
     */
    private File partialDownload(
            String zipPath, String prefix, List<String> filters, IFileDownloader downloader)
            throws BuildRetrievalError, IOException {
        File testsDir = FileUtil.createTempDir(prefix, getWorkFolder());
        try {
            downloader.downloadZippedFiles(testsDir, zipPath, filters, null);
        } catch (BuildRetrievalError e) {
            FileUtil.recursiveDelete(testsDir);
            throw e;
        }
        return testsDir;
    }

    /**
     * Help method to create remote path for images.
     *
     * @param buildName
     * @param buildId
     * @param imageName
     * @return remote path
     */
    private String createPathForImage(String buildName, String buildId, String imageName) {
        return String.format("%s/%s/%s", buildName, buildId, imageName);
    }

    /**
     * Help method to get remote path for images from RemoteBuildInfo
     *
     * @param buildInfo a RemoteBuildInfo
     * @param pattern the pattern of the remote path for the image
     * @return the remote path
     */
    public static String getPathForPattern(RemoteBuildInfo buildInfo, String pattern) {
        return getPathForPattern(buildInfo, pattern, false);
    }

    /**
     * Help method to get remote path for images from RemoteBuildInfo
     *
     * @param buildInfo a RemoteBuildInfo
     * @param pattern the pattern of the remote path for the image
     * @param ignoreSubdir if true, ignore the subdir in the pattern
     * @return the remote path
     */
    public static String getPathForPattern(
            RemoteBuildInfo buildInfo, String pattern, boolean ignoreSubdir) {
        String filesString = buildInfo.getAttribute(BuildAttributeKey.FILES);
        if (filesString == null) {
            return null;
        }
        Pattern p = Pattern.compile(pattern);
        String[] fileStrings;
        if (filesString.isEmpty()) {
            fileStrings = new String[] {};
        } else {
            fileStrings = filesString.split(",");
        }
        for (String fileString : fileStrings) {
            String filename = fileString;
            if (ignoreSubdir) {
                Map<String, String> attribs = LCUtil.parseAttributeLine(fileString);
                if (attribs == null) {
                    continue;
                }
                filename = attribs.get(LCUtil.FILENAME);
                if (filename.contains("/")) {
                    CLog.d("Ignoring file %s because it contains a subdir", filename);
                    continue;
                }
            }
            Matcher matcher = p.matcher(filename);
            if (matcher.find()) {
                return fileString;
            }
        }
        return null;
    }

    /** Exposed for unit testing */
    Collection<BuildAttributeKey> getDownloadKeys() {
        return mDownloadKeys;
    }

    /** Exposed for unit testing */
    Set<BuildAttributeKey> getSkipDownloadKeys() {
        return mSkipDownloadKeys;
    }

    /**
     * Require downloading a file with a given {@link BuildAttributeKey}. Note that exclusions
     * preempt inclusions, so if a file is in the skip list, it won't be downloaded, even if it's
     * mentioned here.
     */
    public void addDownloadKey(BuildAttributeKey attrKey) {
        mDownloadKeys.add(attrKey);
    }

    /** Skip downloading a file with a given {@link BuildAttributeKey}. */
    public void skipDownload(BuildAttributeKey attrKey) {
        mSkipDownloadKeys.add(attrKey);
    }

    /**
     * Return true if file corresponding to given {@link BuildAttributeKey} should be downloaded
     *
     * <p>Exposed for unit testing
     */
    boolean shouldDownload(BuildAttributeKey attrKey) {
        return mDownloadKeys.contains(attrKey) && !mSkipDownloadKeys.contains(attrKey);
    }

    /** {@inheritDoc} */
    @Override
    public void cleanUp(IBuildInfo info) {
        info.cleanUp();
    }

    /**
     * Gets build id, flavor from device and fetches branch information from build server
     *
     * @param device
     */
    @VisibleForTesting
    protected void resolveBuildInfoFromDevice(ITestDevice device)
            throws DeviceNotAvailableException, BuildRetrievalError {
        String buildIdProp = device.getBuildId();
        if (Pattern.matches("^\\d+$", buildIdProp)) {
            Long.parseLong(buildIdProp);
            final MultiMap<String, String> params = new MultiMap<>();
            params.put(QUERY_TYPE_PARAM, QueryType.GET_BUILD_DETAILS.getRemoteValue());
            params.put(BUILD_ID_PARAM, buildIdProp);
            RemoteBuildInfo rbi = queryForBuild(params);
            // First check we got a response.
            if (rbi == null) {
                throw new BuildRetrievalError(
                        String.format(
                                "Could not reach Android Build to look up the build info. "
                                        + "Please check that you have access to %s or that the "
                                        + "build id is valid.",
                                getLcHostname()),
                        InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
            }
            String branch = rbi.getAttribute(BuildAttributeKey.BRANCH);
            if (branch == null) {
                throw new BuildRetrievalError(
                        "cannot get branch info for build " + buildIdProp,
                        InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
            }
            setBranch(branch);
            setBuildId(buildIdProp);
            // overriding query type since we are testing a particular build id
            setQueryType(QueryType.NOTIFY_TEST_BUILD);
            String flavor = device.getBuildFlavor();
            if (flavor != null) {
                setBuildFlavor(flavor);
            }
            CLog.i("Using info from device branch: %s build id: %s", branch, buildIdProp);
        } else {
            if (getBranch() == null) {
                // if device does not have a numerical build id, suggesting locally generated build
                // we then at least need branch parameter to decide where to get the build artifact
                throw new BuildRetrievalError(
                        String.format(
                                "\"%s\" is not from build server, please use --branch <branch"
                                        + " name>",
                                buildIdProp),
                        InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
            } else {
                // override query param in case it's something else
                setQueryType(QueryType.QUERY_LATEST_BUILD);
            }
        }
    }

    /** Invoke the flasher's getAdditionalFileFilters() method. */
    private void addFileFiltersForFlasher(String product, String variant) {
        Class<? extends IDeviceFlasher> flasherClass = null;
        if (!Strings.isNullOrEmpty(mDeviceFlasherClass)) {
            try {
                flasherClass = Class.forName(mDeviceFlasherClass).asSubclass(IDeviceFlasher.class);
            } catch (ClassNotFoundException e) {
                CLog.w(
                        "Flasher class %s was explicitly set for flashing, but could not be found"
                                + " in the classpath.",
                        mDeviceFlasherClass);
                CLog.e(e);
                return;
            } catch (ClassCastException e) {
                CLog.w("%s is not a IDeviceFlasher class.", mDeviceFlasherClass);
                CLog.e(e);
                return;
            }
        } else {
            flasherClass = StaticDeviceInfoUtil.getFlasherClass(product, variant);
        }

        if (flasherClass == null) {
            return;
        }
        IDeviceFlasher flasher = null;
        try {
            flasher = flasherClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            CLog.w(
                    "Class %s lacks public default constructor; preGetBuild not called for it:",
                    flasherClass.getName(), e.toString());
            return;
        }
        Set<String> additionalFilters = flasher.getAdditionalFileFilters();
        for (String filter : additionalFilters) {
            addFileFilter(filter);
        }
    }

    /** {@inheritDoc} */
    @Override
    public IBuildInfo getBuild(ITestDevice device)
            throws BuildRetrievalError, DeviceNotAvailableException {
        mDevice = device;
        if (mBootstrapBuildInfo) {
            resolveBuildInfoFromDevice(device);
        }
        if (getBuildFlavor() == null) {
            if (device.getIDevice() instanceof StubDevice) {
                throw new HarnessRuntimeException(
                        String.format(
                                "Device is a placeholder '%s' and no "
                                        + "build-flavor specified. Please "
                                        + "specify --build-flavor.",
                                device.getIDevice().getClass()),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            if (!Strings.isNullOrEmpty(mDeviceFlasherClass)) {
                throw new HarnessRuntimeException(
                        String.format(
                                "Flasher class %s was explicitly used, but no build-flavor was"
                                        + " specified. Please specify --build-flavor.",
                                mDeviceFlasherClass),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            String product = device.getProductType();
            String variant = device.getProductVariant();
            String flavor = StaticDeviceInfoUtil.getDefaultLcFlavor(product, variant);
            if (flavor == null) {
                throw new BuildRetrievalError(
                        String.format(
                                "Could not determine build flavor for device %s, %s:%s",
                                device.getSerialNumber(), product, variant),
                        InfraErrorIdentifier.ARTIFACT_UNSUPPORTED_PATH);
            } else {
                String buildType = device.getProperty("ro.build.type");
                if (buildType == null) {
                    CLog.i(
                            "Unknown build type for device %s. Assuming userdebug",
                            device.getSerialNumber());
                    buildType = "userdebug";
                }
                flavor = String.format("%s-%s", flavor, buildType);
                CLog.i(
                        "Using build flavor %s for device %s, %s:%s",
                        flavor, device.getSerialNumber(), product, variant);
                setBuildFlavor(flavor);
            }
        }
        if (mSkipIfSameBuild) {
            // only query device's build id if necessary, because this can result in an adb query
            // to device
            mCurrentBuildId = device.getBuildId();
        }
        // If it's a real device, check flasher extra files
        if (!(device.getIDevice() instanceof StubDevice)
                || device.getIDevice() instanceof FastbootDevice) {
            addFileFiltersForFlasher(device.getProductType(), device.getProductVariant());
        }
        return getBuild();
    }

    @Override
    public RemoteBuildInfo getRemoteBuild() throws BuildRetrievalError {
        RemoteBuildInfo remoteBuild = super.getRemoteBuild();
        if (mSkipIfSameBuild
                && remoteBuild != null
                && remoteBuild.getBuildId().equals(mCurrentBuildId)) {
            CLog.d(
                    "Skipping retrieval of build %s, since device is already running that build",
                    mCurrentBuildId);
            return null;
        }
        return remoteBuild;
    }

    /** Download required piece to evaluate whether device image changed or not. */
    public void evaluateDeviceImageChanges(
            RemoteBuildInfo remoteBuild, IFileDownloader downloader, IDeviceBuildInfo localBuild) {
        if (mAlreadyEvaluated) {
            return;
        }
        mAlreadyEvaluated = true;
        if (!mCheckModifiedFiles) {
            return;
        }
        if (getDevice() != null && (getDevice().getIDevice() instanceof NullDevice)) {
            for (IDeviceConfiguration deviceConfig : getConfiguration().getDeviceConfig()) {
                if (deviceConfig.getBuildProvider() == this
                        && deviceConfig.getDeviceRequirements().getBaseDeviceTypeRequested()
                                == null) {
                    return;
                }
            }
        }
        // Need to improve the heuristic for virtual devices
        if (!shouldDownload(BuildAttributeKey.DEVICE_IMAGE)
                && !getBuildFlavor().contains("cf_")
                && !getBuildFlavor().contains("sdv_")) {
            return;
        }
        boolean hasCas = ContentBasedHelper.hasCasDetails(remoteBuild);
        ContentInformation information = null;
        boolean reported = false;
        if (hasCas) {
            information =
                    ContentBasedHelper.getContentInformation(
                            remoteBuild, getBranch(), getBuildFlavor(), localBuild.getBuildId());
        }
        if (getBuildFlavor().contains("cf_")
                && !ContentBasedHelper.hasHostPackageArgs(
                        getConfiguration().getDeviceConfig().get(0).getDeviceOptions())) {
            if (hasCas) {
                boolean invalidate = false;
                if (information == null) {
                    information = new ContentInformation(null, null, null, localBuild.getBuildId());
                    invalidate = true;
                }
                ContentAnalysisContext context =
                        new ContentAnalysisContext(
                                "cvd-host_package.tar.gz", information, AnalysisMethod.BUILD_KEY);
                if (invalidate || !information.isValid()) {
                    context.invalidateAnalysis("No information from base build.");
                }
                reported = true;
                getConfiguration().getSkipManager().setImageAnalysis(mDevice, context);
            } else {
                if (information == null) {
                    information = new ContentInformation(null, null, null, localBuild.getBuildId());
                }
                ContentAnalysisContext context =
                        new ContentAnalysisContext(
                                "cvd-host_package.tar.gz", information, AnalysisMethod.BUILD_KEY);
                context.invalidateAnalysis("Not CAS enabled.");
                getConfiguration().getSkipManager().setImageAnalysis(mDevice, context);
            }
        }
        String targetFiles =
                DeviceLaunchControlProvider.getPathForPattern(remoteBuild, "-target_files-", true);
        CLog.d("target files for image analysis: %s", targetFiles);
        if (targetFiles != null) {
            if (information == null) {
                information = new ContentInformation(null, null, null, localBuild.getBuildId());
            }
            ContentAnalysisContext context =
                    new ContentAnalysisContext(
                            new File(targetFiles).getName(),
                            information,
                            AnalysisMethod.DEVICE_IMAGE);
            if (!hasCas) {
                context.invalidateAnalysis("Not CAS enabled on " + getBranch());
            } else if (!information.isValid()) {
                context.invalidateAnalysis("No information from base build.");
            }
            reported = true;
            getConfiguration().getSkipManager().setImageAnalysis(mDevice, context);
        }
        if (!reported && information != null) {
            information.clean();
        }
    }

    /**
     * Exposed for testing
     *
     * @param bootstrapBuildInfo
     */
    public void setBootstrapBuildInfo(boolean bootstrapBuildInfo) {
        mBootstrapBuildInfo = bootstrapBuildInfo;
    }

    public boolean shouldAutopopulateFilters() {
        return mAutoPopulatePartialDownload;
    }

    /** Discover dependencies of the configuration. */
    public static Set<String> discoverDependencies(IConfiguration config) {
        Set<String> filters = new LinkedHashSet<>();
        for (IDeviceConfiguration deviceConfig : config.getDeviceConfig()) {
            for (Object obj : deviceConfig.getAllObjects()) {
                if (obj instanceof IDisableable) {
                    if (((IDisableable) obj).isDisabled()) {
                        continue;
                    }
                }
                if (obj instanceof IDiscoverDependencies) {
                    Set<String> deps = ((IDiscoverDependencies) obj).reportDependencies();
                    if (deps != null) {
                        filters.addAll(deps);
                    }
                }
            }
        }
        for (Object obj : config.getTests()) {
            if (obj instanceof IDiscoverDependencies) {
                Set<String> deps = ((IDiscoverDependencies) obj).reportDependencies();
                if (deps != null) {
                    filters.addAll(deps);
                }
            }
        }
        return filters;
    }

    @Override
    public Set<String> reportTestZipFileFilter() {
        Set<String> filters = new HashSet<>();
        filters.addAll(super.reportTestZipFileFilter());
        // If we are not skipping the download of the test zip, add the filter.
        if (!mSkipDownloadKeys.contains(BuildAttributeKey.TESTS_ZIP)) {
            if (mTestZipFileFilter != null) {
                filters.add(mTestZipFileFilter);
            } else {
                // Default is to download the -tests- zips under the 'platform_tests' target
                filters.add("plaform_tests.zip");
            }
        }
        return filters;
    }

    public void setAltStaticDeviceInfoClass(String altStaticDeviceInfoClass) {
        StaticDeviceInfoUtil.setStaticDeviceInfoClass(mAltStaticDeviceInfoClass);
    }
}
