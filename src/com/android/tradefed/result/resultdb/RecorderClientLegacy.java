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
import com.android.resultdb.proto.CreateArtifactRequest;
import com.android.resultdb.proto.CreateInvocationRequest;
import com.android.resultdb.proto.CreateTestResultRequest;
import com.android.resultdb.proto.FinalizeInvocationRequest;
import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.RecorderGrpc;
import com.android.resultdb.proto.TestResult;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

/** ResultDB recorder client that uploads test results to ResultDB legacy invocation. */
public class RecorderClientLegacy implements IRecorderClientLegacy {

    private static final int MAX_BATCH_SIZE = 500;
    private static final String UPDATE_TOKEN_PROVIDER_METHOD_NAME =
            "luci.resultdb.v1.Recorder/CreateInvocation";
    private static final String AUTH_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email";
    // The id of the ResultDB invocation used in upload results, update and finalize invocation
    // request. Currently only one ResultDB invocation per TF invocation is supported.
    private String mInvocationId;
    private String mUpdateToken;

    private final RecorderGrpc.RecorderBlockingStub mStub;
    private final Credentials mCredentials;
    public static final int SERVER_PORT = 443;

    private final BatchChannel<TestResult> mTestResultChannel;
    private final BatchChannel<Artifact> mArtifactChannel;

    private RecorderClientLegacy(Boolean isStaging) {
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
        mTestResultChannel =
                new BatchChannel<TestResult>(
                        MAX_BATCH_SIZE, "test results", this::batchUploadTestResults);

        mArtifactChannel =
                new BatchChannel<Artifact>(MAX_BATCH_SIZE, "artifacts", this::batchUploadArtifacts);
    }

    public static IRecorderClientLegacy createLegacy(
            String invocationId, String updateToken, Boolean isStaging) {
        RecorderClientLegacy client = new RecorderClientLegacy(isStaging);
        client.mInvocationId = invocationId;
        client.mUpdateToken = updateToken;
        return client;
    }

    public static IRecorderClientLegacy createLegacyWithNewInvocation(
            String invocationId, Invocation invocation, Boolean isStaging) {
        RecorderClientLegacy client = new RecorderClientLegacy(isStaging);
        client.mInvocationId = invocationId;
        client.createInvocation(
                CreateInvocationRequest.newBuilder()
                        .setInvocation(invocation)
                        .setInvocationId(invocationId)
                        .build());
        return client;
    }

    private String getServerAddress(Boolean isStaging) {
        if (isStaging) {
            return "staging.results.api.luci.app";
        }
        return "results.api.luci.app";
    }

    private Invocation createInvocation(CreateInvocationRequest request) {
        Invocation invocation = mStub.createInvocation(request);
        CLog.i("Created invocation: %s", invocation.getName());
        return invocation;
    }

    @Override
    public Invocation finalizeInvocation() {
        CLog.i("Finalizing invocation: %s", mInvocationId);
        return mStub.finalizeInvocation(
                FinalizeInvocationRequest.newBuilder()
                        .setName("invocations/" + mInvocationId)
                        .build());
    }

    @Override
    public void enqueueTestResult(TestResult result) {
        try {
            mTestResultChannel.enqueue(result);
        } catch (InterruptedException e) {
            CLog.e("Failed to enqueue test result: " + e.getMessage());
        }
    }

    @Override
    public void enqueueArtifact(Artifact artifact) {
        try {
            mArtifactChannel.enqueue(artifact);
        } catch (InterruptedException e) {
            CLog.e("Failed to enqueue artifact: " + e.getMessage());
        }
    }

    @Override
    public void uploadArtifact(Artifact artifact) {
        try {
            batchUploadArtifacts(ImmutableList.of(artifact));
        } catch (StatusRuntimeException e) {
            CLog.e("Failed to upload artifact: " + e.getMessage());
        }
    }

    @Override
    public void finalizeUpload() {
        try {
            mTestResultChannel.finalizeUpload();
            mArtifactChannel.finalizeUpload();
        } catch (InterruptedException e) {
            CLog.e("Failed to finalize result or artifact upload: " + e.getMessage());
        }
    }

    private void batchUploadTestResults(List<TestResult> allResults) throws StatusRuntimeException {
        BatchCreateTestResultsRequest.Builder request =
                BatchCreateTestResultsRequest.newBuilder()
                        .setInvocation(String.format("invocations/%s", mInvocationId))
                        .setRequestId(UUID.randomUUID().toString());
        for (TestResult result : allResults) {
            request.addRequests(CreateTestResultRequest.newBuilder().setTestResult(result).build());
        }

        mStub.batchCreateTestResults(request.build());
        CLog.i("Uploaded %d results to invocation %s", allResults.size(), mInvocationId);
    }

    private void batchUploadArtifacts(List<Artifact> allArtifacts) throws StatusRuntimeException {
        BatchCreateArtifactsRequest.Builder request =
                BatchCreateArtifactsRequest.newBuilder()
                        .setParent(String.format("invocations/%s", mInvocationId));
        for (Artifact artifact : allArtifacts) {
            request.addRequests(CreateArtifactRequest.newBuilder().setArtifact(artifact).build());
        }

        mStub.batchCreateArtifacts(request.build());
        CLog.i("Uploaded %d artifacts to invocation %s", allArtifacts.size(), mInvocationId);
    }
}
