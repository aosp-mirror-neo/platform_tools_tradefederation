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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
    public void setUp() throws Exception {
        // Reset the singleton from any previous test runs to ensure a clean slate.
        resetFactorySingleton();

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

        // Reset the singleton instance in the factory to ensure test isolation
        resetFactorySingleton();
    }

    @Test
    public void createCasManager_makesClientExecutable() throws Exception {
        // 1. Create a fake casviewer file in our temporary directory.
        File casClientFile = tempFolder.newFile("casviewer");

        // 2. Explicitly make the file NOT executable.
        casClientFile.setExecutable(false, false);
        String oldPerms = getFilePermissions(casClientFile);
        assertFalse(
                "Pre-condition failed: casviewer file should not be executable yet.",
                Files.isExecutable(casClientFile.toPath()));

        // 3. Point the system property to our temporary directory.
        System.setProperty("TF_JAR_DIR", tempFolder.getRoot().getAbsolutePath());

        // 4. Instantiate FuseMountManagerFactory;
        FuseMountManagerFactory factory = new FuseMountManagerFactory();
        FuseMountManager manager = factory.createCasFuseMountManager();

        assertNotNull(manager);
        String newPerms = getFilePermissions(casClientFile);
        String message =
                String.format(
                        "casviewer file should be executable after getCasClientPath (old perms: %s,"
                                + " new perms: %s).",
                        oldPerms, newPerms);
        assertTrue(message, Files.isExecutable(casClientFile.toPath()));
    }

    @Test
    public void removeStaleMountPoints_removesOldMountPoints() throws Exception {
        // The tempFolder will act as our controlled mount root for this test.
        File staleMountPoint = tempFolder.newFolder("stale_mount");
        File recebtMountPoint = tempFolder.newFolder("recent_mount");
        // Set the modification time to 25 hours ago.
        staleMountPoint.setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25));

        assertTrue("Pre-condition: stale mount point should exist.", staleMountPoint.exists());
        assertTrue("Pre-condition: recent mount point should exist.", recebtMountPoint.exists());

        // Invoke the static method directly and pass our temporary test directory.
        FuseMountManagerFactory.removeStaleMountPoints(tempFolder.getRoot().toPath());

        // Verify that only the stale file was removed.
        assertFalse("Stale mount point should have been deleted.", staleMountPoint.exists());
        assertTrue("Recent mount point should not have been deleted.", recebtMountPoint.exists());
    }

    private String getFilePermissions(File file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file.toPath());
            return PosixFilePermissions.toString(perms);
        } catch (IOException e) {
            return String.format("<%s>", e.getMessage());
        }
    }

    /**
     * Uses reflection to reset the private static state in the factory. This is crucial for
     * ensuring tests are isolated.
     */
    private void resetFactorySingleton() throws Exception {
        // Clear the list of live managers to ensure no stale mounts from previous tests persist.
        Field managersField = FuseMountManagerFactory.class.getDeclaredField("mManagers");
        managersField.setAccessible(true);
        List<?> managersList = (List<?>) managersField.get(null);
        managersList.clear();

        // Set the fuse availability field to true to avoid the check (ensure tests are isolated).
        Field isFuseAvailableField =
                FuseMountManagerFactory.class.getDeclaredField("mIsFuseAvailable");
        isFuseAvailableField.setAccessible(true);
        isFuseAvailableField.set(null, Boolean.TRUE);
    }
}
