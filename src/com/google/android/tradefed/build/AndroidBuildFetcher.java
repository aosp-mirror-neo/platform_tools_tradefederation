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

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.TimeUtil;

import com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper;
import com.google.api.client.googleapis.media.MediaHttpDownloader;
import com.google.api.client.googleapis.media.MediaHttpDownloaderProgressListener;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.androidbuildinternal.v3.Androidbuildinternal;
import com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata;
import com.google.common.base.Strings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;

/** Helper class used for downloading files from Android Build API. */
public class AndroidBuildFetcher {

    private Androidbuildinternal mClient;
    private boolean mUseMd5 = true;
    private static final long MAX_RETRY_CONSIDERED_MILLISECONDS = 4 * 60000;

    /**
     * Constructor of AndroidBuildFetcher.
     *
     * @param buildEnv the Build API environment to use.
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public AndroidBuildFetcher(String buildEnv) throws IOException, GeneralSecurityException {
        mClient = AndroidBuildAPIHelper.getClient(buildEnv);
    }

    public void setUseMd5(boolean useMd5) {
        mUseMd5 = useMd5;
    }

    /**
     * Get the metadata of a build artifact.
     *
     * @param target artifact target. If it's null, the download will fail.
     * @param buildId artifact buildId. If it's null, the download will fail.
     * @param resourceId the filename to download. If it's null, the download will fail.
     * @param attemptId latest, current or attempt id, default is latest.
     * @return a {@link BuildArtifactMetadata} object.
     * @throws IOException
     */
    public BuildArtifactMetadata getArtifactMetadata(
            String target, String buildId, String resourceId, String attemptId) throws IOException {
        Androidbuildinternal.Buildartifact.Get getObject =
                mClient.buildartifact().get(buildId, target, attemptId, resourceId);
        return getObject.execute();
    }

    /**
     * Download artifact to local file.
     *
     * @param target artifact target. If it's null, the download will fail.
     * @param buildId artifact buildId. If it's null, the download will fail.
     * @param resourceId the filename to download. If it's null, the download will fail.
     * @param attemptId latest, current or attempt id, default is latest.
     * @param outputFile download the file to this local file.
     * @throws IOException
     * @throws BuildRetrievalError
     */
    public void fetchArtifact(
            String target, String buildId, String resourceId, String attemptId, File outputFile)
            throws IOException, BuildRetrievalError {
        // Set size to -1 to fetch the whole file.
        fetchArtifact(target, buildId, resourceId, attemptId, outputFile, 0, -1);
    }

    /**
     * Download part of the artifact to local file with the given range.
     *
     * @param target artifact target. If it's null, the download will fail.
     * @param buildId artifact buildId. If it's null, the download will fail.
     * @param resourceId the filename to download. If it's null, the download will fail.
     * @param attemptId latest, current or attempt id, default is latest.
     * @param outputFile download the file to this local file.
     * @param startOffset the start offset of the file to download.
     * @param size number of bytes to download. Set it to a negative value to fetch the whole file.
     * @throws IOException
     * @throws BuildRetrievalError
     */
    public void fetchArtifact(
            String target,
            String buildId,
            String resourceId,
            String attemptId,
            File outputFile,
            long startOffset,
            long size)
            throws IOException, BuildRetrievalError {
        if (Strings.isNullOrEmpty(attemptId)) {
            attemptId = "latest";
        }
        long startTime = System.currentTimeMillis();
        try (CloseableTraceScope ignored = new CloseableTraceScope("download " + target)) {
            boolean retry = false;
            do {
                try {
                    internalDownload(
                            target, buildId, resourceId, attemptId, outputFile, startOffset, size);
                    InvocationMetricLogger.addInvocationPairMetrics(
                            InvocationMetricKey.AB_DOWNLOAD_SIZE_ELAPSED_TIME,
                            outputFile.length(),
                            System.currentTimeMillis() - startTime);
                    break;
                } catch (IOException ioe) {
                    retry =
                            System.currentTimeMillis() - startTime
                                    < MAX_RETRY_CONSIDERED_MILLISECONDS;
                    if (!retry) {
                        throw ioe;
                    }
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.DOWNLOAD_RETRY_COUNT, 1);
                }
            } while (retry);
        } catch (IOException e) {
            ErrorIdentifier errorIdentifier = InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR;
            if (e.getMessage().contains("No space left on device")) {
                errorIdentifier = InfraErrorIdentifier.NO_DISK_SPACE;
            }
            throw new BuildRetrievalError(
                    String.format(
                            "Failed to download %s %s %s %s %d %d to %s",
                            target,
                            buildId,
                            resourceId,
                            attemptId,
                            startOffset,
                            size,
                            outputFile.getAbsolutePath()),
                    e,
                    errorIdentifier);
        } finally {
            if (outputFile.exists()) {
                SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String formattedTime = simpleDateFormat.format(outputFile.lastModified());
                CLog.d(
                        "Downloaded file (last modified): %s (%s) size=%s",
                        outputFile.getAbsolutePath(), formattedTime, outputFile.length());
            } else {
                CLog.e("Downloaded file does not exist: %s", outputFile.getAbsolutePath());
            }
        }

        // Verify md5 checksum only if it's not a partial download.
        if (size < 0) {
            if (!mUseMd5) {
                return;
            }
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope("md5" + outputFile.getName())) {
                String md5 =
                        mClient.buildartifact()
                                .get(buildId, target, attemptId, resourceId)
                                .execute()
                                .getMd5();
                if (md5 == null) {
                    CLog.w(
                            "No MD5 checksum found for %s. Skipping file corruption check...",
                            outputFile.getPath());
                } else {
                    String localMd5 = FileUtil.calculateMd5(outputFile);
                    if (!md5.equals(localMd5)) {
                        outputFile.delete();
                        throw new BuildRetrievalError(
                                String.format(
                                        "Downloaded %s [md5: %s] file does not "
                                                + "match expected checksum from server "
                                                + "[server md5: %s]",
                                        outputFile.getPath(), localMd5, md5),
                                InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
                    }
                }
                CLog.d("Downloaded file [md5]: %s [%s]", outputFile.getAbsolutePath(), md5);
            }
        }
    }

    /**
     * Download device blob to local file.
     *
     * @param deviceName If this is null, the download will fail.
     * @param binaryType radio.img or bootloader.img. If this is null, the download will fail.
     * @param version device version. If this is null, the download will fail.
     * @param outputFile local file. Download the blob to this local file.
     * @throws IOException
     */
    public void fetchDeviceBlob(
            String deviceName, String binaryType, String version, File outputFile)
            throws IOException {
        FileOutputStream outputStream = new FileOutputStream(outputFile);
        try {
            mClient.deviceblob()
                    .get(deviceName, binaryType, version)
                    .executeMediaAndDownloadTo(outputStream);
        } finally {
            outputStream.close();
        }

        // Verify md5 checksum
        String md5 = mClient.deviceblob().get(deviceName, binaryType, version).execute().getMd5();
        if (md5 == null) {
            CLog.w(
                    "No MD5 checksum found for %s. Skipping file corruption check...",
                    outputFile.getPath());
        } else if (!md5.equals(FileUtil.calculateMd5(outputFile))) {
            outputFile.delete();
            throw new IOException(
                    String.format(
                            "Downloaded %s file does not match expected checksum",
                            outputFile.getPath()));
        }
    }

    private void internalDownload(
            String target,
            String buildId,
            String resourceId,
            String attemptId,
            File outputFile,
            long startOffset,
            long size)
            throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            Androidbuildinternal.Buildartifact.Get getObject =
                    mClient.buildartifact().get(buildId, target, attemptId, resourceId);
            if (size < 0) {
                // Download the whole file.
                long startTime = System.currentTimeMillis();
                boolean directDownload =
                        AndroidBuildAPIHelper.getAndroidBuildAPIOptions().isDirectDownload();
                getObject.getMediaHttpDownloader().setDirectDownloadEnabled(directDownload);
                int chunkSize = AndroidBuildAPIHelper.getAndroidBuildAPIOptions().getChunkSize();
                getObject.getMediaHttpDownloader().setChunkSize(chunkSize);
                if (directDownload) {
                    CLog.d("Direct download without chunks is enabled.");
                } else {
                    CLog.d("Downloading with chunk size: %d", chunkSize);
                }

                getObject
                        .getMediaHttpDownloader()
                        .setProgressListener(
                                new MediaHttpDownloaderProgressListener() {

                                    @Override
                                    public void progressChanged(MediaHttpDownloader downloader)
                                            throws IOException {
                                        switch (downloader.getDownloadState()) {
                                            case MEDIA_IN_PROGRESS:
                                                CLog.d(
                                                        "Download percentage: "
                                                                + downloader.getProgress());
                                                break;
                                            case MEDIA_COMPLETE:
                                                CLog.d("Download Completed!");
                                                break;
                                            case NOT_STARTED:
                                                CLog.d("Not started yet");
                                                break;
                                        }
                                    }
                                });
                getObject.executeMediaAndDownloadTo(outputStream);
                CLog.d(
                        "Download time for %s: %s",
                        resourceId,
                        TimeUtil.formatElapsedTime(System.currentTimeMillis() - startTime));
            } else {
                getObject.setRequestHeaders(
                        new HttpHeaders()
                                .setRange(
                                        String.format(
                                                "bytes=%d-%d",
                                                startOffset, startOffset + size - 1)));
                getObject.getMediaHttpDownloader().setDirectDownloadEnabled(true);
                getObject.executeMediaAndDownloadTo(outputStream);
            }
        }
    }
}
