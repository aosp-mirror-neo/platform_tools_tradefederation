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
import com.android.resultdb.proto.BatchCreateArtifactsRequest;
import com.android.resultdb.proto.BatchCreateTestResultsRequest;
import com.android.resultdb.proto.BatchCreateWorkUnitsRequest;
import com.android.resultdb.proto.BatchUpdateWorkUnitsRequest;
import com.android.resultdb.proto.CreateArtifactRequest;
import com.android.resultdb.proto.CreateRootInvocationRequest;
import com.android.resultdb.proto.CreateRootInvocationResponse;
import com.android.resultdb.proto.CreateTestResultRequest;
import com.android.resultdb.proto.CreateWorkUnitRequest;
import com.android.resultdb.proto.RecorderGrpc;
import com.android.resultdb.proto.RootInvocation;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.UpdateWorkUnitRequest;
import com.android.resultdb.proto.WorkUnit;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.FieldMask;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;

/** ResultDB recorder client that uploads test results to ResultDB. */
public class RecorderClient implements IRecorderClient {

    private static final int MAX_BATCH_SIZE = 500;
    private static final String UPDATE_TOKEN_PROVIDER_METHOD_NAME =
            "luci.resultdb.v1.Recorder/CreateRootInvocation";
    private static final String AUTH_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email";
    // The id of the ResultDB root invocation used in upload results.
    private String mRootInvocationId;
    private String mUpdateToken;

    private final RecorderGrpc.RecorderBlockingStub mStub;
    private final Credentials mCredentials;
    public static final int SERVER_PORT = 443;

    private final BatchChannel<WorkUnitItem> mWorkUnitChannel;
    private final BatchChannel<WorkUnitUpdateItem> mWorkUnitUpdateChannel;
    private final BatchChannel<TestResultItem> mTestResultChannel;

    private final BatchChannel<ArtifactItem> mArtifactChannel;

    private RecorderClient(Boolean isStaging) {
        try {
            mCredentials = GoogleCredentials.getApplicationDefault().createScoped(AUTH_EMAIL_SCOPE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to get application default credentials", e);
        }
        ManagedChannel channel =
                ManagedChannelBuilder.forAddress(getServerAddress(isStaging), SERVER_PORT)
                        .executor(Executors.newCachedThreadPool())
                        .build();
        RecorderGrpc.RecorderBlockingStub stub =
                RecorderGrpc.newBlockingStub(channel)
                        .withCallCredentials(MoreCallCredentials.from(mCredentials))
                        .withInterceptors(
                                new UpdateTokenInterceptor(
                                        () -> mUpdateToken,
                                        (token) -> mUpdateToken = token,
                                        UPDATE_TOKEN_PROVIDER_METHOD_NAME));
        mStub = stub;
        mWorkUnitChannel =
                new BatchChannel<WorkUnitItem>(
                        MAX_BATCH_SIZE, "work units", this::batchUploadWorkUnits);
        mWorkUnitUpdateChannel =
                new BatchChannel<WorkUnitUpdateItem>(
                        MAX_BATCH_SIZE, "work unit updates", this::batchUpdateWorkUnits);

        mTestResultChannel =
                new BatchChannel<TestResultItem>(
                        MAX_BATCH_SIZE, "test results", this::batchUploadTestResults);

        mArtifactChannel =
                new BatchChannel<ArtifactItem>(
                        MAX_BATCH_SIZE, "artifacts", this::batchUploadArtifacts);
    }

    public static IRecorderClient create(
            String rootInvocationId, String updateToken, Boolean isStaging) {
        RecorderClient client = new RecorderClient(isStaging);
        client.mRootInvocationId = rootInvocationId;
        client.mUpdateToken = updateToken;
        return client;
    }

    public static IRecorderClient create(
            String rootInvocationId,
            RootInvocation rootInvocation,
            WorkUnit rootWorkUnit,
            Boolean isStaging) {
        RecorderClient client = new RecorderClient(isStaging);
        client.mRootInvocationId = rootInvocationId;
        client.createRootInvocation(
                CreateRootInvocationRequest.newBuilder()
                        .setRootInvocationId(rootInvocationId)
                        .setRootInvocation(rootInvocation)
                        .setRootWorkUnit(rootWorkUnit)
                        .setRequestId(UUID.randomUUID().toString())
                        .build());
        return client;
    }

    private String getServerAddress(Boolean isStaging) {
        if (isStaging) {
            return "staging.results.api.luci.app";
        }
        return "results.api.luci.app";
    }

    private CreateRootInvocationResponse createRootInvocation(CreateRootInvocationRequest request) {
        CreateRootInvocationResponse response = mStub.createRootInvocation(request);
        CLog.i(
                "Created root invocation: %s, and root work unit: %s",
                response.getRootInvocation().getName(), response.getRootWorkUnit().getName());
        return response;
    }

    @Override
    public void enqueueWorkUnit(String parentId, String id, WorkUnit workUnit) {
        try {
            mWorkUnitChannel.enqueue(new WorkUnitItem(parentId, id, workUnit));
        } catch (InterruptedException e) {
            CLog.e("Failed to enqueue work unit: " + e.getMessage());
        }
    }

    @Override
    public void enqueueWorkUnitUpdate(WorkUnit workUnit, FieldMask updateMask) {
        try {
            mWorkUnitUpdateChannel.enqueue(new WorkUnitUpdateItem(workUnit, updateMask));
        } catch (InterruptedException e) {
            CLog.e("Failed to enqueue work unit update: " + e.getMessage());
        }
    }

    @Override
    public void updateWorkUnit(WorkUnit workUnit, FieldMask updateMask) {
        try {
            batchUpdateWorkUnits(ImmutableList.of(new WorkUnitUpdateItem(workUnit, updateMask)));
        } catch (StatusRuntimeException e) {
            CLog.e("Failed to update work unit: " + e.getMessage());
        }
    }

    @Override
    public void enqueueTestResult(String workunitId, TestResult result) {
        try {
            mTestResultChannel.enqueue(new TestResultItem(workunitId, result));
        } catch (InterruptedException e) {
            CLog.e("Failed to enqueue test result: " + e.getMessage());
        }
    }

    @Override
    public void enqueueArtifact(String workUnitId, Artifact artifact) {
        try {
            mArtifactChannel.enqueue(new ArtifactItem(workUnitId, artifact));
        } catch (InterruptedException e) {
            CLog.e("Failed to enqueue artifact: " + e.getMessage());
        }
    }

    @Override
    public void uploadArtifact(String workUnitId, Artifact artifact) {
        try {
            batchUploadArtifacts(ImmutableList.of(new ArtifactItem(workUnitId, artifact)));
        } catch (StatusRuntimeException e) {
            CLog.e("Failed to upload artifact: " + e.getMessage());
        }
    }

    @Override
    public void finalizeUpload() {
        try {
            // Order is important. Work units need to be uploaded before test results and artifacts.
            // Work unit updates need to be uploaded the last.
            mWorkUnitChannel.finalizeUpload();
            mTestResultChannel.finalizeUpload();
            mArtifactChannel.finalizeUpload();
            mWorkUnitUpdateChannel.finalizeUpload();
        } catch (InterruptedException e) {
            CLog.e("Failed to finalize result or artifact upload: " + e.getMessage());
        }
    }

    private void batchUploadWorkUnits(List<WorkUnitItem> allWorkUnits)
            throws StatusRuntimeException {
        BatchCreateWorkUnitsRequest.Builder request =
                BatchCreateWorkUnitsRequest.newBuilder().setRequestId(UUID.randomUUID().toString());
        for (WorkUnitItem workUnit : allWorkUnits) {
            request.addRequests(
                    CreateWorkUnitRequest.newBuilder()
                            .setParent(
                                    ResultDBUtil.makeWorkUnitName(
                                            mRootInvocationId, workUnit.getParentId()))
                            .setWorkUnitId(workUnit.getWorkUnitId())
                            .setWorkUnit(workUnit.getWorkUnit())
                            .build());
        }
        mStub.batchCreateWorkUnits(request.build());
        CLog.i(
                "Uploaded %d work units to root invocation %s",
                allWorkUnits.size(), mRootInvocationId);
    }

    private void batchUpdateWorkUnits(List<WorkUnitUpdateItem> allWorkUnitUpdates)
            throws StatusRuntimeException {
        BatchUpdateWorkUnitsRequest.Builder request =
                BatchUpdateWorkUnitsRequest.newBuilder().setRequestId(UUID.randomUUID().toString());
        for (WorkUnitUpdateItem workUnitUpdate : allWorkUnitUpdates) {
            request.addRequests(
                    UpdateWorkUnitRequest.newBuilder()
                            .setWorkUnit(workUnitUpdate.getWorkUnit())
                            .setUpdateMask(workUnitUpdate.getUpdateMask())
                            .build());
        }
        // Force upload all pending work units, test results and artifacts.
        mWorkUnitChannel.forceUpload();
        mTestResultChannel.forceUpload();
        mArtifactChannel.forceUpload();
        mStub.batchUpdateWorkUnits(request.build());
        CLog.i(
                "Updated %d work units in invocation %s",
                allWorkUnitUpdates.size(), mRootInvocationId);
    }

    private void batchUploadTestResults(List<TestResultItem> allResults)
            throws StatusRuntimeException {
        BatchCreateTestResultsRequest.Builder requestBuilder =
                BatchCreateTestResultsRequest.newBuilder()
                        .setRequestId(UUID.randomUUID().toString());
        // Add each test result to the request.
        for (TestResultItem item : allResults) {
            CreateTestResultRequest createTestResultRequest =
                    CreateTestResultRequest.newBuilder()
                            .setParent(
                                    ResultDBUtil.makeWorkUnitName(
                                            mRootInvocationId, item.getWorkUnitId()))
                            .setTestResult(item.getTestResult())
                            .build();

            requestBuilder.addRequests(createTestResultRequest);
        }

        // Work units need to be created before test results.
        mWorkUnitChannel.forceUpload();
        mStub.batchCreateTestResults(requestBuilder.build());
        CLog.i("Uploaded %d results to root invocation %s", allResults.size(), mRootInvocationId);
    }

    private void batchUploadArtifacts(List<ArtifactItem> allArtifacts)
            throws StatusRuntimeException {
        BatchCreateArtifactsRequest.Builder requestBuilder =
                BatchCreateArtifactsRequest.newBuilder().setRequestId(UUID.randomUUID().toString());
        for (ArtifactItem item : allArtifacts) {
            CreateArtifactRequest createArtifactRequest =
                    CreateArtifactRequest.newBuilder()
                            .setArtifact(item.getArtifact())
                            .setParent(
                                    ResultDBUtil.makeWorkUnitName(
                                            mRootInvocationId, item.getWorkUnitId()))
                            .build();
            requestBuilder.addRequests(createArtifactRequest);
        }
        // Work units need to be created before test artifacts.
        mWorkUnitChannel.forceUpload();
        mStub.batchCreateArtifacts(requestBuilder.build());
        CLog.i(
                "Uploaded %d artifacts to root invocation %s",
                allArtifacts.size(), mRootInvocationId);
    }

    static class WorkUnitItem {
        private final String mParentId;
        private final String mWorkUnitId;
        private final WorkUnit mWorkUnit;

        public WorkUnitItem(String parentId, String workUnitId, WorkUnit workUnit) {
            mParentId = parentId;
            mWorkUnitId = workUnitId;
            mWorkUnit = workUnit;
        }

        public String getWorkUnitId() {
            return mWorkUnitId;
        }

        public String getParentId() {
            return mParentId;
        }

        public WorkUnit getWorkUnit() {
            return mWorkUnit;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof WorkUnitItem)) {
                return false;
            }
            WorkUnitItem that = (WorkUnitItem) o;
            return mParentId.equals(that.mParentId)
                    && mWorkUnitId.equals(that.mWorkUnitId)
                    && mWorkUnit.equals(that.mWorkUnit);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mParentId, mWorkUnitId, mWorkUnit);
        }

        @Override
        public String toString() {
            return String.format(
                    "WorkUnitItem{parentId=%s, workUnitId=%s, workUnit=%s}",
                    mParentId, mWorkUnitId, mWorkUnit);
        }
    }

    static class WorkUnitUpdateItem {
        private final WorkUnit mWorkUnit;
        private final FieldMask mUpdateMask;

        public WorkUnitUpdateItem(WorkUnit workUnit, FieldMask updateMask) {
            mWorkUnit = workUnit;
            mUpdateMask = updateMask;
        }

        public WorkUnit getWorkUnit() {
            return mWorkUnit;
        }

        public FieldMask getUpdateMask() {
            return mUpdateMask;
        }
    }

    /** A wrapper class for TestResult and its parent work unit id. */
    static class TestResultItem {
        private final String mWorkUnitId;
        private final TestResult mTestResult;

        public TestResultItem(String workUnitId, TestResult testResult) {
            mWorkUnitId = workUnitId;
            mTestResult = testResult;
        }

        public String getWorkUnitId() {
            return mWorkUnitId;
        }

        public TestResult getTestResult() {
            return mTestResult;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestResultItem that = (TestResultItem) o;
            return mWorkUnitId.equals(that.mWorkUnitId) && mTestResult.equals(that.mTestResult);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mWorkUnitId, mTestResult);
        }

        @Override
        public String toString() {
            return "TestResultItem{"
                    + "mWorkUnitId='"
                    + mWorkUnitId
                    + '\''
                    + ", mTestResult="
                    + mTestResult
                    + '}';
        }
    }

    static class ArtifactItem {
        private final String mWorkUnitId;
        private final Artifact mArtifact;

        public ArtifactItem(String workUnitId, Artifact artifact) {
            mWorkUnitId = workUnitId;
            mArtifact = artifact;
        }

        public String getWorkUnitId() {
            return mWorkUnitId;
        }

        public Artifact getArtifact() {
            return mArtifact;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ArtifactItem that = (ArtifactItem) o;
            return mWorkUnitId.equals(that.mWorkUnitId) && mArtifact.equals(that.mArtifact);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mWorkUnitId, mArtifact);
        }

        @Override
        public String toString() {
            return "ArtifactItem{"
                    + "mWorkUnitId='"
                    + mWorkUnitId
                    + '\''
                    + ", mArtifact="
                    + mArtifact
                    + '}';
        }
    }
}
