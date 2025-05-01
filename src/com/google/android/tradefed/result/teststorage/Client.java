/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.tradefed.result.teststorage;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;

import com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper;
import com.google.api.client.googleapis.batch.BatchCallback;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.json.GoogleJsonErrorContainer;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.services.androidbuildinternal.v3.Androidbuildinternal;
import com.google.api.services.androidbuildinternal.v3.Androidbuildinternal.Testartifact.Get;
import com.google.api.services.androidbuildinternal.v3.model.BatchInsertEntry;
import com.google.api.services.androidbuildinternal.v3.model.BatchInsertError;
import com.google.api.services.androidbuildinternal.v3.model.Build;
import com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata;
import com.google.api.services.androidbuildinternal.v3.model.BuildAttempt;
import com.google.api.services.androidbuildinternal.v3.model.Invocation;
import com.google.api.services.androidbuildinternal.v3.model.TestArtifactListResponse;
import com.google.api.services.androidbuildinternal.v3.model.TestResult;
import com.google.api.services.androidbuildinternal.v3.model.TestResultBatchInsertRequest;
import com.google.api.services.androidbuildinternal.v3.model.TestResultBatchInsertResponse;
import com.google.api.services.androidbuildinternal.v3.model.WorkUnit;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android Test storage client that uploads test results to the Test Storage service. */
public final class Client implements ITestStorageClient {
    private final Androidbuildinternal mAndroidbuildClient;
    private final com.google.api.services.androidbuildinternal.Androidbuildinternal mLegacyClient;
    private final Uploader mUploader;
    private final Thread mUploadThread;
    private final int mMaxBatchSize;
    private final int mInsertBatchSize;

    // TODO(b/143365209): Remove the flag once we fully switch over to v3 api.
    private final boolean mUseLegacyMigration;

    private final boolean mUseBatchApi;

    private static final String OP_WORK_UNIT = "work_unit";
    private static final String OP_INVOCATION = "invocation";
    private static final String OP_BATCH_UPLOAD = "batch_upload";

    private static final int HTTP_BATCH_SIZE = 1024;
    private static final int DEFAULT_BATCH_SIZE = 200;
    private static final int DEADLINE_EXCEEDED_ERROR = 4;

    public static ITestStorageClient create(
            String buildEnvironment, boolean useLegacyMigration, boolean useBatchApi)
            throws GeneralSecurityException, IOException {
        return new Client(
                AndroidBuildAPIHelper.getTestStorageClient(buildEnvironment),
                AndroidBuildAPIHelper.getLegacyClient(buildEnvironment),
                useLegacyMigration,
                true,
                useBatchApi,
                DEFAULT_BATCH_SIZE,
                0);
    }

    public static ITestStorageClient create(
            String buildEnvironment,
            boolean useLegacyMigration,
            boolean enableUploadThread,
            boolean useBatchApi)
            throws GeneralSecurityException, IOException {
        return new Client(
                AndroidBuildAPIHelper.getTestStorageClient(buildEnvironment),
                AndroidBuildAPIHelper.getLegacyClient(buildEnvironment),
                useLegacyMigration,
                enableUploadThread,
                useBatchApi,
                DEFAULT_BATCH_SIZE,
                0);
    }

    public static ITestStorageClient create(
            String buildEnvironment,
            boolean useLegacyMigration,
            boolean enableUploadThread,
            boolean useBatchApi,
            int maxBatchSize,
            int insertBatchSize)
            throws GeneralSecurityException, IOException {
        return new Client(
                AndroidBuildAPIHelper.getTestStorageClient(buildEnvironment),
                AndroidBuildAPIHelper.getLegacyClient(buildEnvironment),
                useLegacyMigration,
                enableUploadThread,
                useBatchApi,
                maxBatchSize,
                insertBatchSize);
    }

    public static ITestStorageClient create(Androidbuildinternal client) {
        return new Client(client);
    }

    private Client(
            Androidbuildinternal client,
            com.google.api.services.androidbuildinternal.Androidbuildinternal legacyClient,
            boolean useLegacyMigration,
            boolean withUploadThread,
            boolean useBatchApi,
            int maxBatchSize,
            int insertBatchSize) {
        mMaxBatchSize = maxBatchSize;
        mInsertBatchSize = insertBatchSize;
        mAndroidbuildClient = client;
        mLegacyClient = legacyClient;
        if (withUploadThread) {
            mUploader = new Uploader();
            mUploadThread = new Thread(mUploader, "AnTS upload thread");
            mUploadThread.setDaemon(true);
            mUploadThread.start();
        } else {
            mUploader = null;
            mUploadThread = null;
        }
        mUseLegacyMigration = useLegacyMigration;
        mUseBatchApi = useBatchApi;
    }

    private Client(Androidbuildinternal client) {
        mMaxBatchSize = DEFAULT_BATCH_SIZE;
        mInsertBatchSize = 0;
        mAndroidbuildClient = client;
        mLegacyClient = null;
        mUploader = null;
        mUploadThread = null;
        mUseLegacyMigration = true;
        mUseBatchApi = false;
    }

    /** {@inheritDoc} */
    @Override
    public void uploadResult(TestResult result) {
        mUploader.enqueue(result);
    }

    @Override
    public void finalizeTestResults() {
        mUploader.cancel();
        try {
            mUploadThread.join();
        } catch (InterruptedException e) {
            // ignore
        }
    }

    @Override
    public void uploadAssociations(List<LogAssociation> associations, int poolSize)
            throws IOException {
        // Ensure we uploaded last few results before log associations
        mUploader.forceUpload();
        if (mUseLegacyMigration) {
            uploadMigratedAssociations(associations, poolSize);
        } else {
            legacyUploadAssociations(associations);
        }
    }

    // Will upload log association using the migration api available in V3 client.
    // This will still make logs available using V2 api, but migration will
    // be made automatically on the backend.
    private void uploadMigratedAssociations(List<LogAssociation> associations, int poolSize)
            throws IOException {
        // TODO(b/143365209): rename to uploadAssociations once we fully switched over to v3
        List<Callable<Boolean>> toBeUploaded = new ArrayList<>();
        for (LogAssociation association : associations) {
            if (!association.isSupported()) {
                CLog.w("Log saver does not support association for %s", association);
                continue;
            }
            LogAssociationUpload oneUpload = new LogAssociationUpload(association, this);
            toBeUploaded.add(oneUpload);
        }
        if (toBeUploaded.isEmpty()) {
            return;
        }
        if (poolSize > 0) {
            int poolSizeMin = Math.min(poolSize, toBeUploaded.size());
            ParallelDeviceExecutor<Boolean> executor = new ParallelDeviceExecutor<>(poolSizeMin);
            executor.invokeAll(toBeUploaded, 0, TimeUnit.MILLISECONDS);
        } else {
            // Call sequentially with blocking call.
            for (Callable<Boolean> callable : toBeUploaded) {
                ((LogAssociationUpload) callable).call();
            }
        }
    }

    /** A Callable implementation that upload a single logAssociation. */
    private class LogAssociationUpload implements Callable<Boolean> {

        private final LogAssociation mAssociation;
        private final ITestStorageClient mClient;

        LogAssociationUpload(LogAssociation association, ITestStorageClient client) {
            mAssociation = association;
            mClient = client;
        }

        @Override
        public Boolean call() throws IOException {
            CLog.v("Updating association %s name: %s:", mAssociation, mAssociation.getResourceId());
            ObjectUpdater updater = ObjectUpdater.create(mClient);
            updater.runUpdate(
                    mAssociation.getInvocationId(),
                    mAssociation.getResourceId(),
                    mAssociation.getLegacyResultId(),
                    new ObjectUpdater.TestArtifactUpdate() {
                        @Override
                        public BuildArtifactMetadata doUpdate(BuildArtifactMetadata current) {
                            if (current == null) { // We are creating a new artifact here.
                                current =
                                        new BuildArtifactMetadata()
                                                .setName(mAssociation.getResourceId());
                            }
                            current.setInvocationId(mAssociation.getInvocationId())
                                    .setWorkUnitId(mAssociation.getWorkUnitId())
                                    .setTestResultId(mAssociation.getTestResultId());
                            return current;
                        }
                    });
            return true;
        }
    }

    @Override
    public TestArtifactListResponse listTestArtifact(String invocationId, String artifactType)
            throws IOException {
        return mAndroidbuildClient
                .testartifact()
                .list()
                .setInvocationId(invocationId)
                .setArtifactType(artifactType)
                .execute();
    }

    @Override
    public File fetchTestArtifact(String invocationId, String resourceId) throws IOException {
        File f = FileUtil.createTempFile("tmp-fetch-artifact", "");
        try (FileOutputStream outputStream = new FileOutputStream(f)) {
            Get getObject =
                    mAndroidbuildClient
                            .testartifact()
                            .get(resourceId)
                            .setInvocationId(invocationId);
            getObject.executeMediaAndDownloadTo(outputStream);
        } catch (IOException e) {
            FileUtil.deleteFile(f);
            throw e;
        }
        return f;
    }

    @Override
    public BuildArtifactMetadata getTestArtifact(String invocationId, String resourceId)
            throws IOException {
        return mAndroidbuildClient
                .testartifact()
                .get(resourceId)
                .setInvocationId(invocationId)
                .execute();
    }

    @Override
    public BuildArtifactMetadata updateTestArtifact(
            String invocationId,
            String workUnitId,
            String testResultId,
            BuildArtifactMetadata artifact,
            long legacyId)
            throws IOException {
        return mAndroidbuildClient
                .testartifact()
                .update(artifact.getName(), artifact)
                .setInvocationId(invocationId)
                .setWorkUnitId(workUnitId)
                .setResultId(testResultId)
                .setLegacyTestResultId(legacyId)
                .execute();
    }

    @Override
    public BuildArtifactMetadata updateTestArtifact(
            BuildArtifactMetadata artifact, long legacyId, AbstractInputStreamContent content)
            throws IOException {
        return mAndroidbuildClient
                .testartifact()
                .update(artifact.getName(), artifact, content)
                .setInvocationId(artifact.getInvocationId())
                .setWorkUnitId(artifact.getWorkUnitId())
                .setResultId(artifact.getTestResultId())
                .setLegacyTestResultId(legacyId)
                .execute();
    }

    private void legacyUploadAssociations(List<LogAssociation> associations) throws IOException {
        for (LogAssociation association : associations) {
            if (!association.isSupported()) {
                CLog.w("Log saver does not support association for %s", association);
                continue;
            }
            CLog.v("Updating association %s name: %s", association, association.getResourceId());
            BuildArtifactMetadata artifact = null;
            try {
                artifact =
                        mAndroidbuildClient
                                .testartifact()
                                .get(association.getResourceId())
                                .setInvocationId(association.getInvocationId())
                                .setWorkUnitId(association.getWorkUnitId())
                                .setResultId(association.getTestResultId())
                                .execute();
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() != 404) {
                    // We received an unexpected error.
                    throw e;
                }
            }
            BuildArtifactMetadata update =
                    new BuildArtifactMetadata().setName(association.getResourceId());
            if (artifact != null) {
                update.setRevision(artifact.getRevision());
            } else {
                // To be compatible with the V2 logs uploaded by AndroidLogSaver, we need to create
                // an empty log file using v2 API, then link that file with the log created by V3
                // api.
                createLegacyArtifact(association);
            }
            CLog.v(
                    "Updating test artifact %s %s %s %s",
                    association.getResourceId(),
                    association.getInvocationId(),
                    association.getWorkUnitId(),
                    association.getTestResultId());
            BuildArtifactMetadata result =
                    mAndroidbuildClient
                            .testartifact()
                            .update(association.getResourceId(), update)
                            .setInvocationId(association.getInvocationId())
                            .setWorkUnitId(association.getWorkUnitId())
                            .setResultId(association.getTestResultId())
                            .execute();
        }
    }

    private void createLegacyArtifact(LogAssociation association) throws IOException {
        try {
            mLegacyClient
                    .testartifact()
                    .get(
                            association.getBuildType(),
                            association.getBuildId(),
                            association.getBuildTarget(),
                            association.getAttemptId(),
                            association.getLegacyResultId(),
                            association.getResourceId())
                    .execute();
            // If the get was successful that means that legacy artifact already exists
            // and we can proceed.
            return;
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() != 404) {
                // If we get an exception other than 404 report it.
                throw e;
            }
            // If we got 404 means that artifact does not exist, attempt to create it.
        }
        try {
            mLegacyClient
                    .testartifact()
                    .update(
                            association.getBuildType(),
                            association.getBuildId(),
                            association.getBuildTarget(),
                            association.getAttemptId(),
                            association.getLegacyResultId(),
                            association.getResourceId(),
                            new com.google.api.services.androidbuildinternal.model
                                    .BuildArtifactMetadata(),
                            ByteArrayContent.fromString(
                                    "text/plain", "A")) // Empty file is not accepted.
                    .execute();
        } catch (GoogleJsonResponseException e) {
            // We can ignore 409 Conflict response because it means the log was already created for
            // us by periodic uploader in AndroidBuildLogSaver.
            if (e.getStatusCode() != 409) {
                throw e;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public WorkUnit createWorkUnit(WorkUnit workUnit) throws IOException {
        return mAndroidbuildClient.workunit().insert(workUnit).execute();
    }

    /** {@inheritDoc} */
    @Override
    public WorkUnit updateWorkUnit(WorkUnit workUnit) throws IOException {
        return mAndroidbuildClient.workunit().update(workUnit.getId(), workUnit).execute();
    }

    @Override
    public WorkUnit getWorkUnit(String workUnitId) throws IOException {
        return mAndroidbuildClient.workunit().get(workUnitId).execute();
    }

    @Override
    public ImmutableList<WorkUnit> createWorkUnits(List<WorkUnit> workUnits) throws IOException {
        ImmutableList.Builder<WorkUnit> results = ImmutableList.builder();
        BatchRequest batch =
                mAndroidbuildClient.batch(mAndroidbuildClient.getRequestFactory().getInitializer());
        for (WorkUnit workUnit : workUnits) {
            HttpRequest request =
                    mAndroidbuildClient.workunit().insert(workUnit).buildHttpRequest();
            batch.queue(
                    request,
                    WorkUnit.class,
                    GoogleJsonErrorContainer.class,
                    new BatchCallback<WorkUnit, GoogleJsonErrorContainer>() {
                        @Override
                        public void onFailure(GoogleJsonErrorContainer error, HttpHeaders headers) {
                            CLog.e(error.getError().getMessage());
                        }

                        @Override
                        public void onSuccess(WorkUnit result, HttpHeaders headers) {
                            results.add(result);
                        }
                    });
            if (batch.size() >= HTTP_BATCH_SIZE) {
                batch.execute();
                batch =
                        mAndroidbuildClient.batch(
                                mAndroidbuildClient.getRequestFactory().getInitializer());
            }
        }

        if (batch.size() > 0) {
            batch.execute();
        }

        return results.build();
    }

    /** See go/local_build_guide for details. */
    @Override
    public Build createLocalBuild(Build localBuild) throws IOException {
        Build build = mAndroidbuildClient.build().insert("LOCAL", localBuild).execute();
        // Once build is created, it needs an attempt to be inserted too.
        BuildAttempt attempt = new BuildAttempt();
        attempt.setId(0);
        attempt.setTimestampStart(System.currentTimeMillis());
        attempt.setTimestampEnd(System.currentTimeMillis());
        attempt.setStatus("complete");
        attempt.setSuccessful(true);
        attempt =
                mAndroidbuildClient
                        .buildattempt()
                        .insert(build.getBuildId(), build.getTarget().getName(), attempt)
                        .execute();
        return build;
    }

    /** {@inheritDoc} */
    @Override
    public Invocation createInvocation(Invocation invocation) throws IOException {
        return mAndroidbuildClient.invocation().insert(invocation).execute();
    }

    /** {@inheritDoc} */
    @Override
    public Invocation updateInvocation(Invocation invocation) throws IOException {
        return mAndroidbuildClient
                .invocation()
                .update(invocation.getInvocationId(), invocation)
                .execute();
    }

    @Override
    public Invocation getInvocation(String invocationId) throws IOException {
        return mAndroidbuildClient.invocation().get(invocationId).execute();
    }

    private class Uploader implements Runnable {

        // API has a request size limit of 10mb. We will prevent batch requests from going
        // over this size. Since we can't measure the size of the batch request directly,
        // put the limit for content to 8mb to give the batch request itself some room for
        // overhead.
        private static final int MAX_CONTENT_SIZE = 8388608; // 8mb

        private final List<TestResult> mResultQueue = new ArrayList<TestResult>();
        private volatile boolean mCanceled = false;

        public void enqueue(TestResult result) {
            if (mCanceled) {
                throw new IllegalStateException(
                        "Attempted to upload results after upload thread was cancelled");
            }
            synchronized (this) {
                mResultQueue.add(result);
                if (mResultQueue.size() >= HTTP_BATCH_SIZE) {
                    this.notifyAll();
                }
            }
        }

        public synchronized void forceUpload() {
            List<TestResult> results = new ArrayList<TestResult>();
            if (mResultQueue.isEmpty()) {
                return;
            }
            CLog.i("Starting force batch upload of %d results", mResultQueue.size());
            results.addAll(mResultQueue);
            mResultQueue.clear();
            if (results.isEmpty()) {
                return;
            }
            try {
                upload(results);
                results.clear();
            } catch (IOException e) {
                CLog.e("Error uploading results:");
                CLog.e(e);
            }
        }

        public synchronized void cancel() {
            CLog.i("Canceling ants uploader");
            mCanceled = true;
            this.notifyAll();
        }

        @Override
        public void run() {
            List<TestResult> results = new ArrayList<TestResult>();
            while (!mCanceled) {
                synchronized (this) {
                    try {
                        this.wait(10000L);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    if (mResultQueue.size() >= HTTP_BATCH_SIZE) {
                        CLog.i("Starting batch upload of %d results", mResultQueue.size());
                        results.addAll(mResultQueue);
                        mResultQueue.clear();
                    }
                }
                if (!results.isEmpty()) {
                    try {
                        upload(results);
                        results.clear();
                    } catch (IOException e) {
                        CLog.e("Error uploading results:");
                        CLog.e(e);
                    }
                }
            }
            synchronized (this) {
                if (!mResultQueue.isEmpty()) {
                    try {
                        upload(mResultQueue);
                        mResultQueue.clear();
                    } catch (IOException e) {
                        CLog.e("Error uploading results:");
                        CLog.e(e);
                    }
                }
            }
            CLog.i("Uploader terminating");
        }

        private void upload(List<TestResult> allResults) throws IOException {
            if (mUseBatchApi) {
                batchUpload(allResults);
            } else {
                httpBatchUpload(allResults);
            }
        }

        private void batchUpload(List<TestResult> allResults) throws IOException {
            ArrayList<BatchInsertEntry> buffer = new ArrayList<>();
            int counter = 0;
            String invocationId = "";
            Map<Integer, TestResult> resultMap = new HashMap<>();
            long totalSize = 0;
            for (TestResult result : allResults) {
                BatchInsertEntry entry = new BatchInsertEntry();
                entry.setTestResult(result);
                entry.setToken(counter);
                buffer.add(entry);
                invocationId = result.getInvocationId();
                resultMap.put(counter, result);
                totalSize = totalSize + entry.toString().length();
                if (buffer.size() >= mMaxBatchSize || totalSize >= MAX_CONTENT_SIZE) {
                    TestResultBatchInsertRequest request =
                            new TestResultBatchInsertRequest()
                                    .setTestResults(buffer)
                                    .setInsertBatchSize(mInsertBatchSize);
                    handleResponse(
                            executeBatchInsert(request, invocationId),
                            resultMap,
                            invocationId,
                            true);
                    buffer.clear();
                    resultMap.clear();
                    totalSize = 0;
                }
                counter++;
            }
            if (!buffer.isEmpty()) {
                TestResultBatchInsertRequest request =
                        new TestResultBatchInsertRequest().setTestResults(buffer);
                handleResponse(
                        executeBatchInsert(request, invocationId), resultMap, invocationId, true);
            }
        }

        private void handleResponse(
                TestResultBatchInsertResponse response,
                Map<Integer, TestResult> resultMap,
                String invocationId,
                boolean doRetry)
                throws IOException {
            if (response.getTestResults() != null) {
                for (BatchInsertEntry entry : response.getTestResults()) {
                    TestResult result = resultMap.getOrDefault(entry.getToken(), null);
                    if (result == null) {
                        CLog.w(
                                "Received a token with no matching test result %d %s",
                                entry.getToken(), entry.getTestResult());
                        continue;
                    }
                    result.setTestResultId(entry.getTestResult().getTestResultId());
                }
            }

            if (response.getInsertErrors() == null || response.getInsertErrors().isEmpty()) {
                // There were no errors so return.
                return;
            }

            Set<Integer> errorCodes = new HashSet<Integer>();
            ArrayList<BatchInsertEntry> retries = new ArrayList<>();
            CLog.w("Failed to upload %d results from a batch.", response.getInsertErrors().size());
            for (BatchInsertError error : response.getInsertErrors()) {
                if (!resultMap.containsKey(error.getToken())) {
                    CLog.w("Received error token with no matching result %d", error.getToken());
                    continue;
                }
                TestResult result = resultMap.get(error.getToken());
                if (!errorCodes.contains(error.getErrorCode())) {
                    // Only print one message per error code, so that we don't spam the log too
                    // much.
                    CLog.w("Upload error %d for test %s", error.getErrorCode(), result);
                }
                errorCodes.add(error.getErrorCode());
                if (error.getErrorCode() == DEADLINE_EXCEEDED_ERROR) {
                    // It is unlikelly other errors can be successfully retried.
                    BatchInsertEntry entry =
                            new BatchInsertEntry().setTestResult(result).setToken(error.getToken());
                    retries.add(entry);
                }
            }
            if (retries.isEmpty() || !doRetry) {
                return;
            }
            TestResultBatchInsertRequest req =
                    new TestResultBatchInsertRequest()
                            .setTestResults(retries)
                            .setInsertBatchSize(mInsertBatchSize);
            handleResponse(executeBatchInsert(req, invocationId), resultMap, invocationId, false);
        }

        private TestResultBatchInsertResponse executeBatchInsert(
                TestResultBatchInsertRequest request, String invocationId) throws IOException {
            CLog.i("Executing batch of size %d", request.getTestResults().size());
            return mAndroidbuildClient
                    .testresult()
                    .batchinsert(request)
                    .setInvocationId(invocationId)
                    .execute();
        }

        private void httpBatchUpload(List<TestResult> allResults) throws IOException {
            long size = 0;
            BatchRequest batch =
                    mAndroidbuildClient.batch(
                            mAndroidbuildClient.getRequestFactory().getInitializer());
            for (TestResult result : allResults) {
                long length = 0;
                HttpRequest insert =
                        mAndroidbuildClient.testresult().insert(result).buildHttpRequest();
                HttpContent content = insert.getContent();
                if (content != null) {
                    length = content.getLength();
                    if (length > 0) {
                        size += length;
                    }
                }
                if (size >= MAX_CONTENT_SIZE || batch.size() >= HTTP_BATCH_SIZE) {
                    executeUploadBatch(batch);
                    batch =
                            mAndroidbuildClient.batch(
                                    mAndroidbuildClient.getRequestFactory().getInitializer());
                    size = length;
                }
                AtomicBoolean printedError = new AtomicBoolean(false);
                batch.queue(
                        insert,
                        TestResult.class,
                        GoogleJsonErrorContainer.class,
                        new BatchCallback<TestResult, GoogleJsonErrorContainer>() {
                            @Override
                            public void onFailure(
                                    GoogleJsonErrorContainer error, HttpHeaders headers)
                                    throws IOException {
                                if (!printedError.get()) {
                                    CLog.e(error.getError().getMessage());
                                    printedError.set(true);
                                }
                            }

                            @Override
                            public void onSuccess(TestResult updatedResult, HttpHeaders headers)
                                    throws IOException {
                                result.setTestResultId(updatedResult.getTestResultId());
                            }
                        });
            }
            if (batch.size() > 0) {
                executeUploadBatch(batch);
            }
        }

        private void executeUploadBatch(BatchRequest batch) throws IOException {
            CLog.i("Executing batch of size %d to %s", batch.size(), batch.getBatchUrl());
            batch.execute();
        }
    }
}
