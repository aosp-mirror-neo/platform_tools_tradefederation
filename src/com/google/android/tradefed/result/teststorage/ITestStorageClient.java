/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.services.androidbuildinternal.v3.model.Build;
import com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata;
import com.google.api.services.androidbuildinternal.v3.model.Invocation;
import com.google.api.services.androidbuildinternal.v3.model.TestArtifactListResponse;
import com.google.api.services.androidbuildinternal.v3.model.TestResult;
import com.google.api.services.androidbuildinternal.v3.model.WorkUnit;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Interface for communicating with Android Test Storage backend. The interface contains methods to
 * create and update invocations and work units and upload test results. In order to create or
 * update work units or test results, they must belong to an invocation that was either created by
 * this client or the invocation must be added using addInvocation method.
 */
public interface ITestStorageClient {

    /**
     * Creates an invocation in Test Storage using the data in the provided invocation.
     *
     * @param invocation - describes the invocation to be created.
     * @return invocation that was created in Test Storage.
     * @throws IOException
     */
    public Invocation createInvocation(Invocation invocation) throws IOException;

    /**
     * Updates an invocation in Test Storage using the data in the provided invocation.
     *
     * @param invocation - invocation with updated data.
     * @return an updated invocation.
     * @throws IOException
     */
    public Invocation updateInvocation(Invocation invocation) throws IOException;

    /**
     * Returns an invocation that has the provided invocation id.
     *
     * @param invocationId - id of the invocation to return.
     * @throws IOException - if the provided invocation is not found or if there is an error
     *     communicating with the back end.
     */
    public Invocation getInvocation(String invocationId) throws IOException;

    /**
     * Upload provided test result to Test Storage. Test result must be part of a previously created
     * invocation.
     *
     * @param result - result to upload.
     * @throws IOException
     */
    public void uploadResult(TestResult result);

    /**
     * Uploads associations between logs and tests/work units. Associations allow Android Test
     * Storage to keep track of which test produced a log.
     *
     * @param associations a list of log files and their associations.
     * @param poolSize The size of the worker pool used to parallel upload. 0 for no parallel
     *     upload.
     * @throws IOException
     */
    public void uploadAssociations(List<LogAssociation> associations, int poolSize)
            throws IOException;

    /**
     * Creates a work unit in Test Storage.
     *
     * @param workUnit - describes the work unit to create.
     * @return a new work unit.
     * @throws IOException
     */
    public WorkUnit createWorkUnit(WorkUnit workUnit) throws IOException;

    /**
     * Updates a provided work unit in Test Storage.
     *
     * @param workUnit - work unit to update.
     * @return updated work unit.
     * @throws IOException
     */
    public WorkUnit updateWorkUnit(WorkUnit workUnit) throws IOException;

    /**
     * Returns a workunit matching the provided id.
     *
     * @param workUnitId - id of the work unit.
     * @throws IOException - if the provided work unit is not found or there is an error
     *     communicating with backend services.
     */
    public WorkUnit getWorkUnit(String workUnitId) throws IOException;

    /**
     * Creates all work units in a provided list in TestStorage.
     *
     * @param workUnits - list of work units to create.
     * @return a list of workunits that were created.
     * @throws IOException
     */
    public ImmutableList<WorkUnit> createWorkUnits(List<WorkUnit> workUnits) throws IOException;

    /** Waits until all test results are uploaded. */
    public void finalizeTestResults();

    /**
     * Insert a local build in the backend.
     *
     * @param localBuild description of the local build
     * @return a {@link Build}
     * @throws IOException
     */
    public Build createLocalBuild(Build localBuild) throws IOException;

    /** Searches and list all test artifacts matching the type provided. */
    public TestArtifactListResponse listTestArtifact(String invocationId, String artifactType)
            throws IOException;

    /** Returns a test artifact matching provided invocation and resource id. */
    public BuildArtifactMetadata getTestArtifact(String invocationId, String resourceId)
            throws IOException;

    /**
     * Updates the provided artifact. This method only updates the association and does not modify
     * the content. When calling this method provide the original invocation id, work unit id, and
     * test result id as the arguments. Put the new work unit, and test result id in the
     * BuildArtifactMetadata object. When work unit id and/or test resutlt as not used in the
     * association they should be set to empty string ("").
     */
    public BuildArtifactMetadata updateTestArtifact(
            String invocationId,
            String workUnitId,
            String testResultId,
            BuildArtifactMetadata artifact,
            long legacyId)
            throws IOException;

    /** Fetches and return the actually test artifact file requested. */
    public File fetchTestArtifact(String invocationId, String resourceId) throws IOException;

    /**
     * Updates the provided artifact and uploads provided content. When uploading content, it is not
     * possible to update association data. Do not modify the work unit id and test result id
     * already set in the BuildArtifactMetadata argument.
     */
    public BuildArtifactMetadata updateTestArtifact(
            BuildArtifactMetadata artifact, long legacyId, AbstractInputStreamContent content)
            throws IOException;
}
