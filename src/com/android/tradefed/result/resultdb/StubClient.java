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
import com.android.resultdb.proto.WorkUnit;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.FieldMask;

import java.util.ArrayList;
import java.util.List;

/** Stub implementation of IRecorderClient for testing. */
class StubClient implements IRecorderClient, IRecorderClientLegacy {
    private List<TestResult> mTestResults = new ArrayList<>();
    private List<Artifact> mArtifacts = new ArrayList<>();
    private Invocation mInvocation;
    private List<RecorderClient.WorkUnitItem> mWorkUnitItems = new ArrayList<>();
    private List<WorkUnit> mWorkUnitUpdates = new ArrayList<>();
    private List<RecorderClient.TestResultItem> mTestResultItems = new ArrayList<>();
    private List<RecorderClient.ArtifactItem> mArtifactItems = new ArrayList<>();

    public static StubClient create() {
        return new StubClient();
    }

    private StubClient() {}

    //  -------------------- IRecorderClientLegacy methods ---------------------

    @Override
    public Invocation finalizeInvocation() {
        return Invocation.getDefaultInstance();
    }

    @Override
    public void enqueueTestResult(TestResult result) {
        mTestResults.add(result);
    }

    @Override
    public void enqueueArtifact(Artifact artifact) {
        mArtifacts.add(artifact);
    }

    @Override
    public void uploadArtifact(Artifact artifact) {
        mArtifacts.add(artifact);
    }

    public ImmutableList<TestResult> getTestResults() {
        return ImmutableList.copyOf(mTestResults);
    }

    public ImmutableList<Artifact> getArtifacts() {
        return ImmutableList.copyOf(mArtifacts);
    }

    public Invocation getInvocation() {
        return mInvocation;
    }

    //  -------------------- IRecorderClient methods ---------------------

    @Override
    public void enqueueWorkUnit(String parentId, String id, WorkUnit workUnit) {
        mWorkUnitItems.add(new RecorderClient.WorkUnitItem(parentId, id, workUnit));
    }

    @Override
    public void enqueueWorkUnitUpdate(WorkUnit workUnit, FieldMask updateMask) {
        mWorkUnitUpdates.add(workUnit);
    }

    @Override
    public void updateWorkUnit(WorkUnit workUnit, FieldMask updateMask) {
        mWorkUnitUpdates.add(workUnit);
    }

    @Override
    public void enqueueTestResult(String workUnitId, TestResult result) {
        mTestResultItems.add(new RecorderClient.TestResultItem(workUnitId, result));
    }

    @Override
    public void enqueueArtifact(String workUnitId, Artifact artifact) {
        mArtifactItems.add(new RecorderClient.ArtifactItem(workUnitId, artifact));
    }

    @Override
    public void uploadArtifact(String workUnitId, Artifact artifact) {
        mArtifactItems.add(new RecorderClient.ArtifactItem(workUnitId, artifact));
    }

    public ImmutableList<WorkUnit> getWorkUnitUpdates() {
        return ImmutableList.copyOf(mWorkUnitUpdates);
    }

    public ImmutableList<RecorderClient.TestResultItem> getTestResultItems() {
        return ImmutableList.copyOf(mTestResultItems);
    }

    public ImmutableList<RecorderClient.ArtifactItem> getArtifactItems() {
        return ImmutableList.copyOf(mArtifactItems);
    }

    public ImmutableList<RecorderClient.WorkUnitItem> getWorkUnitItems() {
        return ImmutableList.copyOf(mWorkUnitItems);
    }

    @Override
    public void finalizeUpload() {
        // Do nothing.
    }
}
