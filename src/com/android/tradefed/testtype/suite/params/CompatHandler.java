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
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.targetprep.CompatTargetPreparer;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TestAppInstallSetup;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Handler for {@link ModuleParameters#COMPATIBILITY_MODE}. */
public class CompatHandler implements IModuleParameterHandler {

    /** {@inheritDoc} */
    @Override
    public String getParameterIdentifier() {
        return "compatibility_mode";
    }

    /** {@inheritDoc} */
    @Override
    public void applySetup(IConfiguration moduleConfiguration) {
        List<File> testFiles = new ArrayList<>();
        for (IDeviceConfiguration deviceConfig : moduleConfiguration.getDeviceConfig()) {
            for (ITargetPreparer preparer : deviceConfig.getTargetPreparers()) {
                if (preparer instanceof TestAppInstallSetup) {
                    testFiles.addAll(((TestAppInstallSetup) preparer).getTestsFileName());
                }
            }
        }

        if (testFiles.isEmpty()) {
            return;
        }

        CompatTargetPreparer compatPreparer = new CompatTargetPreparer();
        for (File file : testFiles) {
            compatPreparer.addTestFile(file);
        }
        for (IDeviceConfiguration deviceConfig : moduleConfiguration.getDeviceConfig()) {
            deviceConfig.getTargetPreparers().add(compatPreparer);
        }
    }
}
