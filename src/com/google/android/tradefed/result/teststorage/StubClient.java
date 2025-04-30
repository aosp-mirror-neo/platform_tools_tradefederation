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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test storage client that does not upload results. Useful for testing and debugging. */
class StubClient implements ITestStorageClient {

    private List<TestResult> mTestResults = new ArrayList<>();
    private Map<String, WorkUnit> mWorkUnits = new HashMap<>();
    private List<LogAssociation> mAssociations = new ArrayList<>();
    private Invocation mInvocation;
    private Map<String, BuildArtifactMetadata> mArtifacts = new HashMap<>();

    public static StubClient create() {
        return new StubClient();
    }

    StubClient() {
        // ignore
    }

    @Override
    public void uploadResult(TestResult result) {
        String id = String.format("test-result-%03d", mTestResults.size());
        if (result.getTestIdentifier().getModuleParameters().isEmpty()) {
            result.getTestIdentifier().setModuleParameters(new ArrayList<>());
        }
        mTestResults.add(result.clone());
        result.setTestResultId(id);
    }

    @Override
    public void uploadAssociations(List<LogAssociation> associations, int poolSize) {
        for (LogAssociation association : associations) {
            mAssociations.add(association);
        }
    }

    @Override
    public WorkUnit createWorkUnit(WorkUnit workUnit) throws IOException {
        String id = String.format("work-unit-%03d", mWorkUnits.size());
        WorkUnit result = workUnit.clone();
        result.setId(id);
        mWorkUnits.put(id, result);
        return result.clone();
    }

    @Override
    public WorkUnit updateWorkUnit(WorkUnit workUnit) throws IOException {
        WorkUnit result = workUnit.clone();
        if (!mWorkUnits.containsKey(workUnit.getId())) {
            throw new IOException("Can only update created work unit");
        }
        mWorkUnits.put(workUnit.getId(), result);
        return result.clone();
    }

    @Override
    public WorkUnit getWorkUnit(String workUnitId) throws IOException {
        WorkUnit out = mWorkUnits.get(workUnitId);
        if (out == null) {
            throw new IOException(String.format("Could not find work unit %s", workUnitId));
        }
        return out.clone();
    }

    @Override
    public ImmutableList<WorkUnit> createWorkUnits(List<WorkUnit> workUnits) throws IOException {
        ImmutableList.Builder<WorkUnit> result = ImmutableList.builder();
        for (WorkUnit workUnit : workUnits) {
            result.add(createWorkUnit(workUnit));
        }
        return result.build();
    }

    @Override
    public Invocation createInvocation(Invocation invocation) throws IOException {
        Invocation result = invocation.clone();
        result.setInvocationId("stub-invocation-id");
        mInvocation = result.clone();
        return result.clone();
    }

    @Override
    public Invocation updateInvocation(Invocation invocation) throws IOException {
        mInvocation = invocation.clone();
        return invocation.clone();
    }

    @Override
    public Invocation getInvocation(String invocationId) {
        Invocation result = getInvocation();
        if (result == null) {
            return new Invocation().setInvocationId(invocationId);
        }
        return result;
    }

    @Override
    public Build createLocalBuild(Build localBuild) throws IOException {
        return localBuild.clone();
    }

    /** Returns last created or updated invocation. */
    public Invocation getInvocation() {
        if (mInvocation == null) {
            return null;
        }
        return mInvocation.clone();
    }

    public List<TestResult> getTestResults() {
        return mTestResults;
    }

    public Map<String, WorkUnit> getWorkUnits() {
        return mWorkUnits;
    }

    public List<LogAssociation> getAssociations() {
        return mAssociations;
    }

    @Override
    public void finalizeTestResults() {
        // ignore
    }

    @Override
    public TestArtifactListResponse listTestArtifact(String invocationId, String artifactType)
            throws IOException {
        return null;
    }

    @Override
    public BuildArtifactMetadata getTestArtifact(String invocationId, String resourceId)
            throws IOException {
        String key = String.format("%s-%s", invocationId, resourceId);
        if (!mArtifacts.containsKey(key)) {
            throw new IOException(String.format("Artifact %s not found", key));
        }
        return mArtifacts.get(key).clone();
    }

    @Override
    public BuildArtifactMetadata updateTestArtifact(
            String invocationId,
            String workUnitId,
            String testResultId,
            BuildArtifactMetadata artifact,
            long legacyId) {
        String key = String.format("%s-%s", artifact.getInvocationId(), artifact.getName());
        mArtifacts.put(key, artifact.clone());
        return artifact.clone();
    }

    @Override
    public File fetchTestArtifact(String invocationId, String resourceId) throws IOException {
        return null;
    }

    @Override
    public BuildArtifactMetadata updateTestArtifact(
            BuildArtifactMetadata artifact, long legacyId, AbstractInputStreamContent content) {
        return updateTestArtifact(
                artifact.getInvocationId(),
                artifact.getWorkUnitId(),
                artifact.getTestResultId(),
                artifact,
                legacyId);
    }
}
