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

import com.android.resultdb.proto.ModuleIdentifier;
import com.android.resultdb.proto.Variant;
import com.android.resultdb.proto.WorkUnit;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.MultiMap;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.FieldMask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;

/** Handles Module level and test run level states, and work unit reporting for ResultDB. */
class WorkUnitHandler {

    public static final String MODULE_WORK_UNIT_TYPE = "TF_MODULE";
    public static final String TEST_RUN_WORK_UNIT_TYPE = "TF_TEST_RUN";
    public static final String TF_INVOCATION_WORK_UNIT_TYPE = "TF_INVOCATION";
    // Set containing the allowed variant module parameter keys
    private static final ImmutableSet<String> ALLOWED_MODULE_PARAMETERS =
            ImmutableSet.of(ModuleDefinition.MODULE_ABI, ModuleDefinition.MODULE_PARAMETERIZATION);
    // Module level variant for test in the same test module.
    private Variant mModuleVariant;
    private String mCurrentModuleName;
    private IInvocationContext mModuleContext;
    private ModuleIdentifier mCurrentModuleIdentifier;

    // We keep work units in a stack, so that we can build them as a tree.
    // This is only set when the recorder client is not null (i.e. work unit reporting is enabled).
    // When a module or a test run starts, we create a WorkUnit and push it on this stack.
    // When test run or module ends, we pop units off the stack.
    // When uploading results and we want to associate result with the work unit,
    // the correct one is obtained by calling mWorkUnits.peek.
    private Deque<WorkUnitInfo> mWorkUnitInfos = new ArrayDeque<>();

    public void testModuleStarted(
            IInvocationContext moduleContext, String invTopWorkUnitId, IRecorderClient recorder) {
        mModuleContext = moduleContext;
        mCurrentModuleName = moduleContext.getConfigurationDescriptor().getModuleName();
        mModuleVariant = extractModuleVariant(moduleContext.getAttributes());
        if (Strings.isNullOrEmpty(mCurrentModuleName)) {
            mCurrentModuleName = "no-module-name";
        }
        mCurrentModuleIdentifier =
                ModuleIdentifier.newBuilder()
                        .setModuleName(mCurrentModuleName)
                        .setModuleScheme("junit")
                        .setModuleVariant(mModuleVariant)
                        .build();
        // TODO (b/464395641): Once the legacy recorder is deprecated, `recorder` will be non-null
        // unless
        // ResultDB reporting is disabled. This check can then be removed.
        if (recorder == null) {
            // Recorder is null, so we don't create module work units.
            return;
        }
        // Prefixed work unit for update token sharing
        String moduleWorkUnitId =
                String.format("%s:u-%s", invTopWorkUnitId, UUID.randomUUID().toString());
        // Create work unit for this module.
        // TODO(b/463830449): add more properties to the work uni according to the data mapping.
        WorkUnit moduleWorkUnit =
                WorkUnit.newBuilder()
                        .setRealm("android:ants-experiment")
                        .setState(WorkUnit.State.RUNNING)
                        .setKind(MODULE_WORK_UNIT_TYPE)
                        .setModuleId(mCurrentModuleIdentifier)
                        .build();
        String parentWorkUnitId = mWorkUnitInfos.peek().getId();
        recorder.enqueueWorkUnit(parentWorkUnitId, moduleWorkUnitId, moduleWorkUnit);
        mWorkUnitInfos.push(new WorkUnitInfo(moduleWorkUnitId, MODULE_WORK_UNIT_TYPE));
    }

    public void testModuleEnded(String rootInvocationId, IRecorderClient recorder) {
        mModuleContext = null;
        mCurrentModuleName = null;
        mModuleVariant = null;
        mCurrentModuleIdentifier = null;
        // TODO (b/464395641): Once the legacy recorder is deprecated, `recorder` will be non-null
        // unless
        // ResultDB reporting is disabled. This check can then be removed.
        if (recorder == null) {
            // Recorder is null, so we don't create module work units.
            return;
        }
        WorkUnitInfo workUnitInfo = mWorkUnitInfos.peek();
        if (!workUnitInfo.getKind().equals(MODULE_WORK_UNIT_TYPE)) {
            CLog.w(
                    "Unexpected work unit in testModuleEnded %s, expected %s.",
                    workUnitInfo, MODULE_WORK_UNIT_TYPE);
            CLog.w("Skipping ResultDB work unit update.");
            return;
        }
        mWorkUnitInfos.pop();
        // TODO(b/463830449): Updated properties for work unit according to the data mapping.
        WorkUnit workUnitUpdate =
                WorkUnit.newBuilder()
                        .setName(
                                ResultDBUtil.makeWorkUnitName(
                                        rootInvocationId, workUnitInfo.getId()))
                        .setState(WorkUnit.State.SUCCEEDED)
                        .build();
        recorder.enqueueWorkUnitUpdate(
                workUnitUpdate, FieldMask.newBuilder().addPaths("state").build());
    }

    public void testRunStarted(
            String runName,
            int testCount,
            int attemptNumber,
            long startTime,
            String invTopWorkUnitId,
            IRecorderClient recorder) {
        // TODO (b/464395641): Once the legacy recorder is deprecated, `recorder` will be non-null
        // unless
        // ResultDB reporting is disabled. This check can then be removed.
        if (recorder == null) {
            // Recorder is null, so we don't create test run work units.
            return;
        }
        String parentWorkUnitId = mWorkUnitInfos.peek().getId();
        // Prefixed work unit for update token sharing.
        String workUnitId =
                String.format("%s:u-%s", invTopWorkUnitId, UUID.randomUUID().toString());
        WorkUnit workUnit =
                WorkUnit.newBuilder()
                        .setRealm("android:ants-experiment")
                        .setState(WorkUnit.State.RUNNING)
                        .setKind(TEST_RUN_WORK_UNIT_TYPE)
                        .build();
        recorder.enqueueWorkUnit(parentWorkUnitId, workUnitId, workUnit);
        mWorkUnitInfos.push(new WorkUnitInfo(workUnitId, TEST_RUN_WORK_UNIT_TYPE));
    }

    public void testRunEnded(
            String rootInvocationId, String invTopWorkUnitId, IRecorderClient recorder) {
        // TODO (b/464395641): Once the legacy recorder is deprecated, `recorder` will be non-null
        // unless
        // ResultDB reporting is disabled. This check can then be removed.
        if (recorder == null) {
            // Recorder is null, so we don't create test run work units.
            return;
        }
        WorkUnitInfo workUnitInfo = mWorkUnitInfos.peek();
        if (!workUnitInfo.getKind().equals(TEST_RUN_WORK_UNIT_TYPE)) {
            CLog.w(
                    "Unexpected work unit in testRunEnded %s, expected %s.",
                    workUnitInfo, TEST_RUN_WORK_UNIT_TYPE);
            CLog.w("Skipping ResultDB work unit update.");
            return;
        }
        mWorkUnitInfos.pop();
        // TODO(b/463830449): Updated properties for work unit according to the data mapping.
        WorkUnit workUnitUpdate =
                WorkUnit.newBuilder()
                        .setName(
                                ResultDBUtil.makeWorkUnitName(
                                        rootInvocationId, workUnitInfo.getId()))
                        .setState(WorkUnit.State.SUCCEEDED)
                        .build();
        recorder.enqueueWorkUnitUpdate(
                workUnitUpdate, FieldMask.newBuilder().addPaths("state").build());
    }

    public IInvocationContext getModuleContext() {
        return mModuleContext;
    }

    public Variant getModuleVariant() {
        return mModuleVariant;
    }

    public String getCurrentModule() {
        return mCurrentModuleName;
    }

    public void setTopWorkUnitInfo(String topWorkUnitId, String workUnitType) {
        if (!mWorkUnitInfos.isEmpty()) {
            throw new IllegalStateException(
                    "work unit stack is not empty, cannot set top work unit info.");
        }
        mWorkUnitInfos.push(new WorkUnitInfo(topWorkUnitId, workUnitType));
    }

    /*
     * Only module-abi and module-param are used in the variant, so filter other values.
     */
    private Variant extractModuleVariant(MultiMap<String, String> properties) {
        Variant.Builder variantBuilder = Variant.newBuilder();
        for (Map.Entry<String, String> property : properties.entries()) {
            if (ALLOWED_MODULE_PARAMETERS.contains(property.getKey())) {
                variantBuilder.putDef(
                        ResultDBUtil.makeValidKey(property.getKey()), property.getValue());
            }
        }
        return variantBuilder.build();
    }

    private static class WorkUnitInfo {
        private final String mId;
        private final String mKind;

        private WorkUnitInfo(String id, String kind) {
            mId = id;
            mKind = kind;
        }

        private String getId() {
            return mId;
        }

        private String getKind() {
            return mKind;
        }
    }
}
