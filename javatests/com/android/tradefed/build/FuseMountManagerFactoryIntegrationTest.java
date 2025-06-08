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

package com.android.tradefed.build;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

// FuseMountManagerFactory has dependencies on static methods (System.getProperty,
// RunUtil.getDefault, FileUtil.createTempDir). Creating unit tests would require a test framework
// that supports static methods, like PowerMock, in Tradefed.

/** Integration tests for {@link FuseMountManagerFactory} that rely on a real filesystem. */
@RunWith(JUnit4.class)
public class FuseMountManagerFactoryIntegrationTest {

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    private String mOriginalTfJarDir;

    @Before
    public void setUp() {
        // Save the original system property so we can restore it later
        mOriginalTfJarDir = System.getProperty("TF_JAR_DIR");
    }

    @After
    public void tearDown() throws Exception {
        // Restore the original system property to avoid side-effects on other tests
        if (mOriginalTfJarDir == null) {
            System.clearProperty("TF_JAR_DIR");
        } else {
            System.setProperty("TF_JAR_DIR", mOriginalTfJarDir);
        }

        // FuseMountManagerFactory creats a parent directory for all mount points. We need to clean
        // it up to avoid leaving artifacts behind.
        FuseMountManagerFactory factoryInstance = FuseMountManagerFactory.getInstance();
        if (factoryInstance != null) {
            // Delete the mount root directory created by the factory.
            Path mountRoot = factoryInstance.getMountRoot();
            FileUtil.recursiveDelete(mountRoot.toFile());
        }

        // Reset the singleton instance in the factory to ensure test isolation
        resetFactorySingleton();
    }

    @Test
    public void testCreateCasManager_makesClientExecutable() throws IOException {
        // 1. Create a fake casviewer file in our temporary directory.
        File casClientFile = tempFolder.newFile("casviewer");

        // 2. Explicitly make the file NOT executable to simulate the problem scenario.
        casClientFile.setExecutable(false, false);
        assertFalse(
                "Pre-condition failed: casviewer file should not be executable yet.",
                Files.isExecutable(casClientFile.toPath()));

        // 3. Point the system property to our temporary directory.
        System.setProperty("TF_JAR_DIR", tempFolder.getRoot().getAbsolutePath());

        // Get an instance of the factory, which will read the system property.
        // Then call the creation method, which will trigger getCasClientPath().
        FuseMountManagerFactory factory = FuseMountManagerFactory.getInstance();
        FuseMountManager manager = factory.createCasFuseMountManager();

        assertNotNull(manager);
        assertTrue(
                "getCasClientPath should make the casviewer client executable.",
                Files.isExecutable(casClientFile.toPath()));
    }

    /**
     * Uses reflection to reset the private static singleton instance in the factory. This is
     * crucial for ensuring tests are isolated when dealing with singletons.
     */
    private void resetFactorySingleton() throws Exception {
        Field instanceField = FuseMountManagerFactory.class.getDeclaredField("sInstance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }
}
