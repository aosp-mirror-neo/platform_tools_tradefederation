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

import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.WorkUnit;

import com.google.protobuf.FieldMask;

/**
 * Interface for communicating with ResultDB recorder backend. The interface contains methods to
 * create and update work units and upload test results under work unit.
 */
public interface IRecorderClient {

    /**
     * Enqueue a work unit to report to ResultDB.
     *
     * @param parentId The parent ID of the work unit.
     * @param id The ID of the work unit.
     * @param workUnit The work unit to upload in the CreateWorkUnitRequest.
     */
    public void enqueueWorkUnit(String parentId, String id, WorkUnit workUnit);

    /**
     * Enqueue a work unit update to report to ResultDB.
     *
     * @param workUnit The work unit to update in the UpdateWorkUnitRequest.
     * @param updateMask The field mask for the update.
     */
    public void enqueueWorkUnitUpdate(WorkUnit workUnit, FieldMask updateMask);

    /**
     * Update a work unit in ResultDB.
     *
     * @param workUnit The work unit to update in the UpdateWorkUnitRequest.
     * @param updateMask The field mask for the update.
     */
    public void updateWorkUnit(WorkUnit workUnit, FieldMask updateMask);

    /**
     * Enqueue a test result to report to ResultDB.
     *
     * @param workUnitId The work unit ID of the test result.
     * @param result The test result to upload in the CreateTestResultRequest.
     */
    public void enqueueTestResult(String workUnitId, TestResult result);

    /**
     * Finalize the upload of test results, artifacts and work units. This method should be called
     * after all resources have been enqueued. Any enqueueXXX calls after this method will be
     * ignored.
     */
    public void finalizeUpload();
}
