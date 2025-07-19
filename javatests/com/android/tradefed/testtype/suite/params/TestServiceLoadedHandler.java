/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.android.tradefed.config.IConfiguration;

/** A test handler implementation to be discovered by {@link java.util.ServiceLoader}. */
public class TestServiceLoadedHandler implements IModuleParameterHandler {

    @Override
    public void addParameterSpecificConfig(IConfiguration moduleConfiguration) {
        // Do nothing.
    }

    @Override
    public String getParameterIdentifier() {
        return "concurrent_foreground_and_visible_background_user";
    }

    @Override
    public void applySetup(IConfiguration moduleConfiguration) {
        // Do nothing.
    }
}
