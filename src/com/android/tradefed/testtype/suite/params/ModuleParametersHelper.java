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
package com.android.tradefed.testtype.suite.params;

import static com.android.tradefed.testtype.suite.params.ModuleParameters.ALL_FOLDABLE_STATES;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.INSTANT_APP;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.MULTIUSER;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.MULTI_ABI;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.NOT_INSTANT_APP;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.NOT_MULTI_ABI;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.NOT_RUN_ON_SDK_SANDBOX;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.NOT_SECONDARY_USER;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.NOT_SECONDARY_USER_ON_DEFAULT_DISPLAY;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.NOT_SECONDARY_USER_ON_SECONDARY_DISPLAY;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.NO_FOLDABLE_STATES;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.RUN_ON_CLONE_PROFILE;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.RUN_ON_PRIVATE_PROFILE;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.RUN_ON_SDK_SANDBOX;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.RUN_ON_SECONDARY_USER;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.RUN_ON_WORK_PROFILE;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.SECONDARY_USER;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.SECONDARY_USER_ON_DEFAULT_DISPLAY;
import static com.android.tradefed.testtype.suite.params.ModuleParameters.SECONDARY_USER_ON_SECONDARY_DISPLAY;

import com.android.tradefed.testtype.suite.params.multiuser.RunOnCloneProfileParameterHandler;
import com.android.tradefed.testtype.suite.params.multiuser.RunOnPrivateProfileParameterHandler;
import com.android.tradefed.testtype.suite.params.multiuser.RunOnSecondaryUserParameterHandler;
import com.android.tradefed.testtype.suite.params.multiuser.RunOnWorkProfileParameterHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Helper to get the {@link IModuleParameterHandler} associated with the parameter. */
public final class ModuleParametersHelper {

    private static final Map<ModuleParameters, IModuleParameterHandler> sHandlerMap =
            new LinkedHashMap<>();

    static {
        sHandlerMap.put(INSTANT_APP, new InstantAppHandler());
        sHandlerMap.put(NOT_INSTANT_APP, new NegativeHandler());

        sHandlerMap.put(MULTI_ABI, new NegativeHandler());
        sHandlerMap.put(NOT_MULTI_ABI, new NotMultiAbiHandler());

        sHandlerMap.put(RUN_ON_WORK_PROFILE, new RunOnWorkProfileParameterHandler());
        sHandlerMap.put(RUN_ON_SECONDARY_USER, new RunOnSecondaryUserParameterHandler());

        sHandlerMap.put(NO_FOLDABLE_STATES, new NegativeHandler());
        sHandlerMap.put(ALL_FOLDABLE_STATES, new FoldableExpandingHandler());
        sHandlerMap.put(RUN_ON_CLONE_PROFILE, new RunOnCloneProfileParameterHandler());
        sHandlerMap.put(RUN_ON_PRIVATE_PROFILE, new RunOnPrivateProfileParameterHandler());
    }

    private static final Set<ModuleParameters> PROFILE_PARAMETERS = new LinkedHashSet<>();

    static {
        PROFILE_PARAMETERS.add(RUN_ON_WORK_PROFILE);
        PROFILE_PARAMETERS.add(RUN_ON_SECONDARY_USER);
        PROFILE_PARAMETERS.add(RUN_ON_CLONE_PROFILE);
        PROFILE_PARAMETERS.add(RUN_ON_PRIVATE_PROFILE);
    }

    private static final Map<ModuleParameters, Set<ModuleParameters>> sGroupMap =
            new LinkedHashMap<>();

    static {
        sGroupMap.put(MULTIUSER, PROFILE_PARAMETERS);
    }

    /**
     * Optional parameters are params that will not automatically be created when the module
     * parameterization is enabled. They will need to be explicitly enabled. They represent a second
     * set of parameterization that is less commonly requested to run. They could be upgraded to
     * main parameters in the future by moving them above.
     */
    private static final Map<ModuleParameters, IModuleParameterHandler> sOptionalHandlerMap =
            new LinkedHashMap<>();

    static {
        sOptionalHandlerMap.put(SECONDARY_USER, new SecondaryUserHandler());
        sOptionalHandlerMap.put(NOT_SECONDARY_USER, new NegativeHandler());
        sOptionalHandlerMap.put(
                SECONDARY_USER_ON_SECONDARY_DISPLAY, new SecondaryUserOnSecondaryDisplayHandler());
        sOptionalHandlerMap.put(NOT_SECONDARY_USER_ON_SECONDARY_DISPLAY, new NegativeHandler());
        sOptionalHandlerMap.put(
                SECONDARY_USER_ON_DEFAULT_DISPLAY, new SecondaryUserOnDefaultDisplayHandler());
        sOptionalHandlerMap.put(NOT_SECONDARY_USER_ON_DEFAULT_DISPLAY, new NegativeHandler());
        sOptionalHandlerMap.put(RUN_ON_SDK_SANDBOX, new RunOnSdkSandboxHandler());
        sOptionalHandlerMap.put(NOT_RUN_ON_SDK_SANDBOX, new NegativeHandler());
    }

    // NOTE: sOptionalGroupMap is currently empty, but used on resolveParam(), so don't remove it
    private static Map<ModuleParameters, Set<ModuleParameters>> sOptionalGroupMap =
            Collections.emptyMap();

    /**
     * Get the all {@link ModuleParameters} which are sub-params of a given {@link
     * ModuleParameters}.
     *
     * <p>This will recursively resolve sub-groups and will only return {@link ModuleParameters}
     * which are not groups.
     *
     * <p>If {@code param} is not a group then a singleton set containing {@code param} will be
     * returned itself, regardless of {@code withOptional}.
     *
     * @param withOptional Whether or not to also check optional param groups.
     */
    public static Map<ModuleParameters, IModuleParameterHandler> resolveParam(
            ModuleParameters param, boolean withOptional) {
        Set<ModuleParameters> mappedParams = sGroupMap.get(param);
        if (mappedParams == null && withOptional) {
            mappedParams = sOptionalGroupMap.get(param);
        }
        if (mappedParams == null) {
            IModuleParameterHandler handler = getParameterHandler(param, withOptional);
            if (handler == null) {
                // If the handler is not supported yet (for example, optional params) skip the
                // param.
                return new HashMap<>();
            }
            Map<ModuleParameters, IModuleParameterHandler> returnMap = new LinkedHashMap<>();
            returnMap.put(param, getParameterHandler(param, withOptional));
            return returnMap;
        }
        // If the parameter is a group, expand it.
        Map<ModuleParameters, IModuleParameterHandler> resolvedParams = new LinkedHashMap<>();
        for (ModuleParameters moduleParameters : mappedParams) {
            resolvedParams.put(moduleParameters, sHandlerMap.get(moduleParameters));
        }
        return resolvedParams;
    }

    /**
     * Returns the {@link IModuleParameterHandler} associated with the requested parameter.
     *
     * @param withOptional Whether or not to also check optional params.
     */
    private static IModuleParameterHandler getParameterHandler(
            ModuleParameters param, boolean withOptional) {
        IModuleParameterHandler value = sHandlerMap.get(param);
        if (value == null && withOptional) {
            return sOptionalHandlerMap.get(param);
        }
        return value;
    }
}
