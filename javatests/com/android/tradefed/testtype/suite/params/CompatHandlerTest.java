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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.targetprep.CompatTargetPreparer;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TestAppInstallSetup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;

/** Unit tests for {@link CompatHandler}. */
@RunWith(JUnit4.class)
public class CompatHandlerTest {

    private CompatHandler mHandler;
    private IConfiguration mModuleConfig;

    @Before
    public void setUp() {
        mHandler = new CompatHandler();
        mModuleConfig = new Configuration("test", "test");
    }

    /** Test that applySetup adds CompatTargetPreparer when TestAppInstallSetup is present. */
    @Test
    public void testApplySetup() {
        TestAppInstallSetup installSetup = new TestAppInstallSetup();
        installSetup.addTestFile(new File("test.apk"));
        mModuleConfig.setTargetPreparer(installSetup);

        mHandler.applySetup(mModuleConfig);

        // Should have 2 preparers: TestAppInstallSetup + CompatTargetPreparer
        assertEquals(2, mModuleConfig.getTargetPreparers().size());
        ITargetPreparer secondPreparer = mModuleConfig.getTargetPreparers().get(1);
        assertTrue(secondPreparer instanceof CompatTargetPreparer);

        CompatTargetPreparer compatPreparer = (CompatTargetPreparer) secondPreparer;
        // Verify it copied the test file
        assertEquals(1, compatPreparer.getTestsFileName().size());
        assertEquals(new File("test.apk"), compatPreparer.getTestsFileName().get(0));
    }

    /** Test that applySetup does nothing if no TestAppInstallSetup is present. */
    @Test
    public void testApplySetup_noInstaller() {
        mHandler.applySetup(mModuleConfig);

        // Should have 0 preparers
        assertEquals(0, mModuleConfig.getTargetPreparers().size());
    }

    /** Test that applySetup does nothing if TestAppInstallSetup has no files. */
    @Test
    public void testApplySetup_emptyInstaller() {
        TestAppInstallSetup installSetup = new TestAppInstallSetup();
        mModuleConfig.setTargetPreparer(installSetup);

        mHandler.applySetup(mModuleConfig);

        // Should still have only 1 preparer (the original TestAppInstallSetup)
        assertEquals(1, mModuleConfig.getTargetPreparers().size());
        assertTrue(mModuleConfig.getTargetPreparers().get(0) instanceof TestAppInstallSetup);
    }
}
