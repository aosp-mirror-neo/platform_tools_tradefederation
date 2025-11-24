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
import com.android.resultdb.proto.BatchUpdateWorkUnitsRequest;
import com.android.resultdb.proto.CreateArtifactRequest;
import com.android.resultdb.proto.CreateInvocationRequest;
import com.android.resultdb.proto.CreateRootInvocationRequest;
import com.android.resultdb.proto.CreateRootInvocationResponse;
import com.android.resultdb.proto.CreateTestResultRequest;
import com.android.resultdb.proto.FinalizeInvocationRequest;
import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.RecorderGrpc;
import com.android.resultdb.proto.RootInvocation;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.UpdateInvocationRequest;
import com.android.resultdb.proto.UpdateWorkUnitRequest;
import com.android.resultdb.proto.WorkUnit;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.FieldMask;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

/** ResultDB recorder client that uploads test results to ResultDB. */
public class RecorderClient implements IRecorderClient, IRecorderClientLegacy {

    // The key for the update token used to create/update resources undera ResultDB invocation.
    private static final Metadata.Key<String> UPDATE_TOKEN_METADATA_KEY =
            Metadata.Key.of("update-token", Metadata.ASCII_STRING_MARSHALLER);
    // The id of the ResultDB invocation used in upload results, update and finalize invocation
    // request. Currently only one ResultDB invocation per TF invocation is supported.
    private String mInvocationId;
    // The id of the ResultDB root invocation used in upload results.
    private String mRootInvocationId;
    private String mUpdateToken;

    private final RecorderGrpc.RecorderBlockingStub mStub;
    private final Credentials mCredentials;
    public static final int SERVER_PORT = 443;

    private final BatchChannel<TestResult> mTestResultChannel;

    private final BatchChannel<Artifact> mArtifactChannel;

    private RecorderClient(Boolean isStaging) {
        try {
            mCredentials =
                    GoogleCredentials.getApplicationDefault()
                            .createScoped("https://www.googleapis.com/auth/userinfo.email");
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
                        .withInterceptors(recorderInterceptor());
        mStub = stub;
        mTestResultChannel =
                new BatchChannel<TestResult>(500, "test results", this::batchUploadTestResults);

        mArtifactChannel = new BatchChannel<Artifact>(500, "artifacts", this::batchUploadArtifacts);
    }

    public static IRecorderClientLegacy createLegacy(
            String invocationId, String updateToken, Boolean isStaging) {
        RecorderClient client = new RecorderClient(isStaging);
        client.mInvocationId = invocationId;
        client.mUpdateToken = updateToken;
        return client;
    }

    public static IRecorderClientLegacy createLegacyWithNewInvocation(
            String invocationId, Invocation invocation, Boolean isStaging) {
        RecorderClient client = new RecorderClient(isStaging);
        client.mInvocationId = invocationId;
        client.createInvocation(
                CreateInvocationRequest.newBuilder()
                        .setInvocation(invocation)
                        .setInvocationId(invocationId)
                        .build());
        return client;
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

    // Interceptor that adds the update token to requests.
    private ClientInterceptor recorderInterceptor() {
        List<String> updateTokenRpc =
                List.of(
                        "luci.resultdb.v1.Recorder/CreateInvocation",
                        "luci.resultdb.v1.Recorder/CreateRootInvocation");
        ClientInterceptor clientInterceptor =
                new ClientInterceptor() {
                    @Override
                    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                            MethodDescriptor<ReqT, RespT> method,
                            CallOptions callOptions,
                            Channel next) {
                        ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
                        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                                delegate) {
                            @Override
                            public void start(Listener<RespT> responseListener, Metadata headers) {
                                if (!Strings.isNullOrEmpty(mUpdateToken)) {
                                    // Add update token to request header.
                                    headers.put(UPDATE_TOKEN_METADATA_KEY, mUpdateToken);
                                }

                                super.start(
                                        new SimpleForwardingClientCallListener<RespT>(
                                                responseListener) {
                                            @Override
                                            public void onHeaders(Metadata headers) {
                                                String fullMethodName = method.getFullMethodName();
                                                if (updateTokenRpc.contains(fullMethodName)) {
                                                    String updateToken =
                                                            headers.get(UPDATE_TOKEN_METADATA_KEY);

                                                    if (!Strings.isNullOrEmpty(updateToken)) {
                                                        // Retrieve the update token from the
                                                        // response header.
                                                        mUpdateToken = updateToken;
                                                    }
                                                }
                                                super.onHeaders(headers);
                                            }
                                        },
                                        headers);
                            }
                        };
                    }
                };
        return clientInterceptor;
    }

    private Invocation createInvocation(CreateInvocationRequest request) {
        Invocation invocation = mStub.createInvocation(request);
        CLog.i("Created invocation: %s", invocation.getName());
        return invocation;
    }

    private CreateRootInvocationResponse createRootInvocation(CreateRootInvocationRequest request) {
        CreateRootInvocationResponse response = mStub.createRootInvocation(request);
        CLog.i(
                "Created root invocation: %s, and root work unit: %s",
                response.getRootInvocation().getName(), response.getRootWorkUnit().getName());
        return response;
    }

    @Override
    public Invocation updateInvocation(UpdateInvocationRequest request) {
        // TODO: Call recorder grpc client to update invocation.
        CLog.i("Updating invocation: %s", request.toString());
        return request.getInvocation();
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
    public void updateWorkUnit(WorkUnit workUnit, FieldMask updateMask) {
        try {
            batchUpdateWorkUnits(List.of(workUnit), updateMask);
        } catch (StatusRuntimeException e) {
            CLog.e("Failed to update work unit: " + e.getMessage());
        }
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

    private void batchUpdateWorkUnits(List<WorkUnit> allWorkUnits, FieldMask updateMask)
            throws StatusRuntimeException {
        BatchUpdateWorkUnitsRequest.Builder request =
                BatchUpdateWorkUnitsRequest.newBuilder().setRequestId(UUID.randomUUID().toString());
        for (WorkUnit workUnit : allWorkUnits) {
            request.addRequests(
                    UpdateWorkUnitRequest.newBuilder()
                            .setWorkUnit(workUnit)
                            .setUpdateMask(updateMask)
                            .build());
        }
        mStub.batchUpdateWorkUnits(request.build());
        CLog.i("Updated %d work units in invocation %s", allWorkUnits.size(), mRootInvocationId);
    }
}
