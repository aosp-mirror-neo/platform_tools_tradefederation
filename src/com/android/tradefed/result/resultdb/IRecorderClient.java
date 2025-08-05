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
import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.UpdateInvocationRequest;

/**
 * Interface for communicating with ResultDB recorder backend. The interface contains methods to
 * create and update invocations and upload test results.
 */
public interface IRecorderClient {

    public Invocation updateInvocation(UpdateInvocationRequest request);

    /**
     * Finalize the invocation. This method should be called after all test results and artifacts
     * have been uploaded.
     */
    public Invocation finalizeInvocation();

    /**
     * Enqueue a test result to upload to ResultDB.
     *
     * @param result The test result to upload.
     */
    public void enqueueTestResult(TestResult result);

    /**
     * Enqueue an artifact to upload to ResultDB.
     *
     * @param artifact The artifact to upload.
     */
    public void enqueueArtifact(Artifact artifact);

    /**
     * Upload an artifact to ResultDB. This is a blocking call. Prefer to use {@link
     * #enqueueArtifact(Artifact)} if possible.
     *
     * @param artifact The artifact to upload.
     */
    public void uploadArtifact(Artifact artifact);

    /**
     * Finalize the upload of test results and artifacts. This method should be called after all
     * test results and artifacts have been enqueued. Any enqueueTestResult and enqueueArtifact
     * calls after this method will be ignored.
     */
    public void finalizeUpload();
}
