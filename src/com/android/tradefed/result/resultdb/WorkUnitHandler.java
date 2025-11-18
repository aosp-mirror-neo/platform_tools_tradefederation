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

import com.android.resultdb.proto.Variant;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.MultiMap;

import com.google.common.collect.ImmutableSet;

import java.util.Map;

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
    private String mCurrentModule;
    private IInvocationContext mModuleContext;

    public void testModuleStarted(IInvocationContext moduleContext) {
        mModuleContext = moduleContext;
        mCurrentModule = moduleContext.getConfigurationDescriptor().getModuleName();
        mModuleVariant = extractModuleVariant(moduleContext.getAttributes());
    }

    public void testModuleEnded() {
        mModuleContext = null;
        mModuleVariant = null;
        mCurrentModule = null;
    }

    public Variant getModuleVariant() {
        return mModuleVariant;
    }

    public String getCurrentModule() {
        return mCurrentModule;
    }

    public IInvocationContext getModuleContext() {
        return mModuleContext;
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
}
