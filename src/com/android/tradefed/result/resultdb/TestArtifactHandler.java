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
package com.android.tradefed.result.resultdb;

import com.android.resultdb.proto.Artifact;
import com.android.resultdb.proto.TestIdentifier;
import com.android.resultdb.proto.TestResult;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.LogFile;

import com.google.common.base.Strings;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Handles Artifact level states and artifact reporting for ResultDB. */
class TestArtifactHandler {
    // Pattern to extract artifact id from logsaver URL.
    private static final Pattern LOGSAVER_PATTERN =
            Pattern.compile("https://android-build.googleplex.com/builds/I\\d+/(.+)");

    // A map from resource ID to TestArtifact.
    private final Map<String, RecorderClient.ArtifactItem> mTestArtifactItems =
            new ConcurrentHashMap<>();
    // No more log associations can be added to the upload queue after this set to true.
    private boolean mUploadQueueFinalized = false;

    public void logAssociation(
            String dataName, LogFile logFile, String workUnitId, TestResult currentTestResult) {
        String resourceId = getAntsResourceId(logFile);
        if (resourceId.isEmpty()) {
            // Ignore logs with empty resource id.
            return;
        }
        Artifact.Builder artifactBuilder = Artifact.newBuilder().setArtifactId(resourceId);

        CLog.v(
                "Received association %s %s %s %s",
                dataName, resourceId, logFile.getPath(), logFile.getUrl());

        if (currentTestResult != null) {
            TestIdentifier testId = currentTestResult.getTestIdStructured();
            artifactBuilder
                    .setTestIdStructured(
                            TestIdentifier.newBuilder()
                                    .setModuleName(testId.getModuleName())
                                    .setModuleScheme(testId.getModuleScheme())
                                    .setCoarseName(testId.getCoarseName())
                                    .setFineName(testId.getFineName())
                                    .setCaseName(testId.getCaseName()))
                    .setResultId(currentTestResult.getResultId());
        }
        mTestArtifactItems.put(
                resourceId, new RecorderClient.ArtifactItem(workUnitId, artifactBuilder.build()));
    }

    /** Upload artifact with the storage path to ResultDB. */
    public void uploadStoragePath(
            String resourceId,
            String storagePath,
            LogFile logFile,
            String topWorkUnitId,
            IRecorderClientLegacy recorderLegacy,
            IRecorderClient recorder) {
        CLog.v("Uploading storage path %s %s", resourceId, storagePath);
        RecorderClient.ArtifactItem artifactItem = mTestArtifactItems.remove(resourceId);
        if (artifactItem == null) {
            // If the artifact is not found, it means the log association is not called before.
            // Upload the artifact to the top work unit.
            CLog.d(
                    "Artifact for resource ID %s not found, upload to the top work unit.",
                    resourceId);
            artifactItem =
                    new RecorderClient.ArtifactItem(
                            topWorkUnitId, Artifact.newBuilder().setArtifactId(resourceId).build());
        }
        if (!storagePath.matches("^/bigstore/([^/]+)/.+$")) {
            return;
        }
        // The storage path is a valid bigstore path, set the artifact to with GCS URI.
        String contentType = logFile.getType().getContentType();
        // Convert storage path to GCS URI.
        String gcsUri = storagePath.replaceFirst("^/bigstore/", "gs://");
        Artifact artifact =
                artifactItem.getArtifact().toBuilder()
                        .setGcsUri(gcsUri)
                        .setContentType(contentType)
                        .setSizeBytes(logFile.getSize())
                        .setArtifactType(logFile.getType().toString())
                        .build();
        if (recorder == null && recorderLegacy == null) {
            CLog.w(
                    "Recorder is not initialized, skipping upload storage path %s %s",
                    resourceId, storagePath);
            return;
        }
        if (recorderLegacy != null) {
            if (mUploadQueueFinalized) {
                // If the upload queue is finalized, make block call to upload the artifact
                // directly.
                CLog.d("Upload queue is finalized, has been called, upload the artifact directly.");
                recorderLegacy.uploadArtifact(artifact);
            } else {
                // Queue the artifact for upload.
                recorderLegacy.enqueueArtifact(artifact);
            }
        }
        if (recorder != null) {
            String workUnitId = artifactItem.getWorkUnitId();
            if (Strings.isNullOrEmpty(workUnitId)) {
                workUnitId = topWorkUnitId;
            }
            if (mUploadQueueFinalized) {
                // If the upload queue is finalized, make block call to upload the artifact
                // directly.
                CLog.d("Upload queue is finalized, has been called, upload the artifact directly.");
                recorder.uploadArtifact(workUnitId, artifact);
            } else {
                // Queue the artifact for upload.
                recorder.enqueueArtifact(workUnitId, artifact);
            }
        }
    }

    public void setUploadQueueFinalized() {
        mUploadQueueFinalized = true;
    }

    // Returns the AnTS artifact resource ID.
    private String getAntsResourceId(LogFile logFile) {
        // We need to decode the resourceId because the backend expects that it is unencoded,
        // but TF gives as the url that is already encoded.
        String resourceId = "";
        if (logFile.getUrl() == null) {
            resourceId = "";
        } else {
            Matcher m = LOGSAVER_PATTERN.matcher(logFile.getUrl());
            if (!m.find()) {
                resourceId = "";
            } else {
                try {
                    resourceId = new URI(m.group(1)).getPath();
                } catch (URISyntaxException e) {
                    // ignore
                }
            }
        }
        return resourceId;
    }
}
