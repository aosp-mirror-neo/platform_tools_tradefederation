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

import com.android.tradefed.log.LogUtil.CLog;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.services.androidbuildinternal.v3.Androidbuildinternal;
import com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata;
import com.google.api.services.androidbuildinternal.v3.model.Invocation;
import com.google.api.services.androidbuildinternal.v3.model.WorkUnit;

import java.io.IOException;

/**
 * Helper class to manage updates to AnTS objects.
 *
 * <p>When updating an AnTS object first we need to make sure that we are updating the correct
 * version. The helper class will automatically fetch the latest version of the object and will
 * automatically update it retrying in case there are errors and/or versions conflicts.
 */
public class ObjectUpdater {

    /**
     * Interface to encapsulte an update. The doUpdate method will be called with the latest version
     * of the WorkUnit in the api. Will be automatically retried if there are any errors in the
     * update or the version changes.
     *
     * <p>Must return a work unit that will be committed to the AnTS api.
     */
    public interface WorkUnitUpdate {
        public WorkUnit doUpdate(WorkUnit current);
    }

    /** Perform an update of the provided invocation. Works same as the WorkUnitUpdate. */
    public interface InvocationUpdate {
        public Invocation doUpdate(Invocation current);
    }

    /**
     * Perform an update on the returned BuildArtifact. Works same as the other Update interfaces.
     * Since update call is used to both create and update artifacts, a null argument means that
     * there is no current artifact and a new artifact will be created.
     */
    public abstract static class TestArtifactUpdate {
        public abstract BuildArtifactMetadata doUpdate(BuildArtifactMetadata current);

        /** Return the content that will be uploaded. */
        public AbstractInputStreamContent getContent() {
            return null;
        }
    }

    private final ITestStorageClient mClient;

    private ObjectUpdater(ITestStorageClient client) {
        mClient = client;
    }

    public static ObjectUpdater create(ITestStorageClient client) {
        return new ObjectUpdater(client);
    }

    public static ObjectUpdater create(Androidbuildinternal apiClient) {
        return new ObjectUpdater(Client.create(apiClient));
    }

    /**
     * Executes the provided update operation. Takes an id of the work unit that needs to be
     * updated. Will fetch the latest version and will retry the update operation if there are any
     * errors.
     */
    public WorkUnit runUpdate(String id, WorkUnitUpdate update) throws IOException {
        IOException exception = null;
        for (int i = 0; i < 3; i++) {
            exception = null;
            WorkUnit workUnit = mClient.getWorkUnit(id);
            WorkUnit newWorkUnit = update.doUpdate(workUnit);
            if (newWorkUnit == null) {
                return null;
            }
            try {
                return mClient.updateWorkUnit(newWorkUnit);
            } catch (GoogleJsonResponseException e) {
                exception = e;
                if (e.getStatusCode() == 409) {
                    CLog.w("Detected a conflict when updating work unit %s", id);
                    continue;
                }
                CLog.w("Error updating work unit %s", newWorkUnit);
                CLog.w(e);
            } catch (IOException e) {
                exception = e;
                CLog.w("Error updating work unit %s", newWorkUnit);
                CLog.w(e);
            }
        }

        throw exception;
    }

    /**
     * Executes the provided update operation. Takes an id of the invocation that needs to be
     * updated. Will fetch the latest version and will retry the update operation if there are any
     * errors.
     */
    public Invocation runUpdate(String id, InvocationUpdate update) throws IOException {
        IOException exception = null;
        for (int i = 0; i < 3; i++) {
            exception = null;
            Invocation latest = mClient.getInvocation(id);
            Invocation updated = update.doUpdate(latest);
            if (updated == null) {
                return null;
            }
            try {
                return mClient.updateInvocation(updated);
            } catch (GoogleJsonResponseException e) {
                exception = e;
                if (e.getStatusCode() == 409) {
                    CLog.w("Detected a conflict when updating invocation %s", id);
                    continue;
                }
                CLog.w("Error updating invocation %s", updated);
                CLog.w(e);
            } catch (IOException e) {
                exception = e;
                CLog.w("Error updating invocation %s", updated);
                CLog.w(e);
            }
        }

        throw exception;
    }

    public BuildArtifactMetadata runUpdate(
            String invocationId, String resourceId, long unusedLegacyId, TestArtifactUpdate update)
            throws IOException {
        IOException exception = null;
        for (int i = 0; i < 3; i++) {
            exception = null;
            BuildArtifactMetadata latest = null;
            try {
                latest = mClient.getTestArtifact(invocationId, resourceId);
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() != 404) {
                    exception = e;
                    CLog.w("Error retrieving test artifact %s %s", invocationId, resourceId);
                    continue;
                }
                // Else we got a 404 which is expected, ignore it.
            }

            String workUnitId = latest == null ? "" : latest.getWorkUnitId();
            String testResultId = latest == null ? "" : latest.getTestResultId();
            BuildArtifactMetadata updated = update.doUpdate(latest);
            if (updated == null) {
                return null;
            }
            AbstractInputStreamContent content = update.getContent();
            try {
                if (content == null) {
                    return mClient.updateTestArtifact(
                            invocationId, workUnitId, testResultId, updated, -1L);
                } else {
                    return mClient.updateTestArtifact(updated, -1L, content);
                }
            } catch (GoogleJsonResponseException e) {
                exception = e;
                if (e.getStatusCode() == 413) {
                    CLog.w("Payload is too big. aborting upload of %s", resourceId);
                    CLog.w(e);
                    // Don't retry since file above a certain limit will never succeed
                    break;
                }
                if (e.getStatusCode() == 409) {
                    CLog.w("Detected a conflict when updating test artifact %s", resourceId);
                    continue;
                }
                CLog.w("Error updating test artifact %s", resourceId);
                CLog.w(e);
            } catch (IOException e) {
                exception = e;
                CLog.w("Error updating test artifact %s", resourceId);
                CLog.w(e);
            }
        }
        throw exception;
    }
}
