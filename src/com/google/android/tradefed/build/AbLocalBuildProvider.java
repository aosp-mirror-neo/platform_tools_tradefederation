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
package com.google.android.tradefed.build;

import static com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper.BUILD_ENV_INVOCATION_CONTEXT_KEY;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.StubBuildProvider;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.testtype.IInvocationContextReceiver;

import com.google.android.tradefed.result.teststorage.Client;
import com.google.android.tradefed.result.teststorage.ITestStorageClient;
import com.google.api.services.androidbuildinternal.v3.model.Build;
import com.google.api.services.androidbuildinternal.v3.model.Target;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Local build provider that creates a local build on Android build. TODO: Update this to also be
 * able to bootstrap device build info.
 */
public class AbLocalBuildProvider extends StubBuildProvider implements IInvocationContextReceiver {

    public static final String LOCAL_BUILD = "local_build";

    private IInvocationContext mContext;

    @Override
    public void setInvocationContext(IInvocationContext invocationContext) {
        mContext = invocationContext;
    }

    @Override
    public IBuildInfo getBuild() throws BuildRetrievalError {
        IBuildInfo info = super.getBuild();
        try {
            Build localBuild =
                    createLocalBuild(mContext, info.getBuildBranch(), info.getBuildFlavor());
            CLog.d("Created the following local build: %s", localBuild);
            info.setBuildId(localBuild.getBuildId());
        } catch (IOException | GeneralSecurityException e) {
            throw new BuildRetrievalError(
                    "Failed to create storage client", e, InfraErrorIdentifier.UNDETERMINED);
        }
        return info;
    }

    private static ITestStorageClient createTestStorageClient(IInvocationContext context)
            throws GeneralSecurityException, IOException {
        String buildEnvironment = context.getAttribute(BUILD_ENV_INVOCATION_CONTEXT_KEY);
        if (buildEnvironment.isEmpty()) {
            buildEnvironment = "prod";
        }
        return Client.create(buildEnvironment, false, false, false);
    }

    private static Build newBuild(String branch, String buildFlavor) {
        Build build = new Build().setExternalId(UUID.randomUUID().toString());
        build.setCreationTimestamp(System.currentTimeMillis());
        build.setBranch(branch);
        // Build id will be created when build is created
        build.setBuildId("");
        Target target = new Target();
        target.setName(buildFlavor);
        target.setTarget(buildFlavor);
        build.setTarget(target);
        build.setBuildAttemptStatus("complete");
        build.setCompletionTimestamp(System.currentTimeMillis());
        return build;
    }

    /** Create a local build on the remote AB server and returns its reference. */
    public static Build createLocalBuild(
            IInvocationContext context, String branch, String buildFlavor)
            throws IOException, GeneralSecurityException {
        ITestStorageClient client = createTestStorageClient(context);
        Build localBuild = newBuild(branch, buildFlavor);
        return client.createLocalBuild(localBuild);
    }
}
