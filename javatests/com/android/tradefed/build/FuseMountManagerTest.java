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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/** Unit tests for {@link FuseMountManager}. */
@RunWith(MockitoJUnitRunner.class)
public class FuseMountManagerTest {

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    @Mock private IFuseDriverProfile mMockDriverProfile;
    @Mock private IRunUtil mMockRunUtil;
    @Mock private Process mMockProcess;
    @Mock private CommandResult mMockCommandResult;

    private FuseMountManager mManager;
    private Path mSourceDir;
    private Path mMountRoot;

    @Before
    public void setUp() throws Exception {
        mSourceDir = tempFolder.newFolder("source-dir").toPath();
        mMountRoot = tempFolder.newFolder("mount-root").toPath();

        mManager = new FuseMountManager(mMockDriverProfile, mMockRunUtil, mMountRoot);

        // Default mock behaviors for success cases
        doNothing().when(mMockDriverProfile).validateSourceDataPath(any(Path.class));
        when(mMockDriverProfile.buildMountCommand(any(Path.class), any(Path.class)))
                .thenReturn(Arrays.asList("fuse-driver", "arg1"));
        when(mMockDriverProfile.getMountTimeoutInMs()).thenReturn(500L); // 500ms timeout for tests
        when(mMockRunUtil.runCmdInBackground(anyList(), any(OutputStream.class)))
                .thenReturn(mMockProcess);
    }

    @After
    public void tearDown() {
        // Ensure all created temp dirs are cleaned up if a test fails mid-mount
        mManager.unmountAll();
    }

    // Tests for isValidSourceData()

    @Test
    public void testIsValidSourceData_whenProfileSucceeds_returnsTrue() throws Exception {
        // Configure the profile to execute its validation method successfully (i.e., not throw)
        doNothing().when(mMockDriverProfile).validateSourceDataPath(mSourceDir);

        boolean result = mManager.isValidSourceData(mSourceDir);

        assertTrue("Should return true when profile validation succeeds.", result);
    }

    @Test
    public void testIsValidSourceData_whenProfileThrowsIOException_returnsFalse() throws Exception {
        // Configure the profile to throw an IOException, simulating a failed validation
        doThrow(new IOException("Simulated validation failure"))
                .when(mMockDriverProfile)
                .validateSourceDataPath(mSourceDir);

        boolean result = mManager.isValidSourceData(mSourceDir);

        assertFalse("Should return false when profile validation throws an IOException.", result);
    }

    @Test
    public void testIsValidSourceData_withNullInput_returnsFalse() {
        boolean result = mManager.isValidSourceData(null);

        assertFalse("Should return false for a null input path.", result);
    }

    // Tests for mount()

    @Test
    public void testMount_successBeforePolling() throws Exception {
        when(mMockDriverProfile.isMountPointReady(any(Path.class))).thenReturn(true);

        Path mountPoint = mManager.mount(mSourceDir);

        assertNotNull(mountPoint);
        assertTrue(mountPoint.startsWith(mMountRoot));
        // Verify we never wait for the process to exit.
        verify(mMockProcess, never()).waitFor(anyLong(), any(TimeUnit.class));
    }

    @Test
    public void testMount_success_whenTimeoutIsZero() throws Exception {
        when(mMockDriverProfile.getMountTimeoutInMs()).thenReturn(0L); // Override to 0ms.
        when(mMockDriverProfile.isMountPointReady(any(Path.class))).thenReturn(true);

        Path mountPoint = mManager.mount(mSourceDir);

        assertNotNull(mountPoint);
        assertTrue(mountPoint.startsWith(mMountRoot));
    }

    @Test
    public void testMount_mountPointHasDeviceNameAsPrefix() throws Exception {
        Path sourceDir = tempFolder.newFolder("husky-img-12345678.zip").toPath();
        when(mMockDriverProfile.isMountPointReady(any(Path.class))).thenReturn(true);

        Path mountPoint = mManager.mount(sourceDir);

        assertNotNull(mountPoint);
        assertTrue(mountPoint.startsWith(mMountRoot));
        assertTrue(mountPoint.getFileName().toString().startsWith("husky-img-12345678"));
    }

    @Test
    public void testMount_successAfterPolling() throws Exception {
        when(mMockDriverProfile.isMountPointReady(any(Path.class)))
                .thenReturn(false, false, true); // Succeeds on the 3rd check
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false); // Still running

        Path mountPoint = mManager.mount(mSourceDir);

        assertNotNull(mountPoint);
        assertTrue(mountPoint.startsWith(mMountRoot));
        // Verify we polled until it was ready
        verify(mMockDriverProfile, times(3)).isMountPointReady(any(Path.class));
    }

    @Test
    public void testMount_idempotency_alreadyMounted() throws Exception {
        // Perform one successful mount
        when(mMockDriverProfile.isMountPointReady(any(Path.class))).thenReturn(true);
        Path firstMountPoint = mManager.mount(mSourceDir);
        assertNotNull(firstMountPoint);

        // Mount the same source directory again
        Path secondMountPoint = mManager.mount(mSourceDir);

        assertEquals(
                "Should return the same mount point for an already mounted source.",
                firstMountPoint,
                secondMountPoint);
        // Verify that the expensive mount process was only ever run ONCE.
        verify(mMockRunUtil, times(1)).runCmdInBackground(anyList(), any(OutputStream.class));
    }

    @Test
    public void testMount_processExitsPrematurely_throwsException() throws Exception {
        when(mMockDriverProfile.isMountPointReady(any(Path.class))).thenReturn(false);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true); // Exited!
        when(mMockProcess.exitValue()).thenReturn(1);

        IOException thrown =
                assertThrows(IOException.class, () -> mManager.mount(mSourceDir));
        assertTrue(thrown.getMessage().contains("failed on startup"));
    }

    @Test
    public void testMount_timeout_throwsExceptionAndCleansUp() throws Exception {
        // The mount point is never ready
        when(mMockDriverProfile.isMountPointReady(any(Path.class))).thenReturn(false);
        // The process never exits on its own
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);

        IOException thrown =
                assertThrows(IOException.class, () -> mManager.mount(mSourceDir));
        assertTrue(thrown.getMessage().contains("timed out"));

        // Verify that the orphaned process was killed
        verify(mMockProcess).destroyForcibly();
    }

    @Test
    public void testMount_invalidSource_throwsException() throws Exception {
        doThrow(new IOException("Source is invalid!"))
                .when(mMockDriverProfile)
                .validateSourceDataPath(mSourceDir);

        assertThrows(IllegalArgumentException.class, () -> mManager.mount(mSourceDir));
    }

    // Tests for unmount()

    @Test
    public void testUnmount_successful() throws Exception {
        // Perform a successful mount to populate the map.
        when(mMockDriverProfile.isMountPointReady(any(Path.class))).thenReturn(true);
        Path mountPoint = mManager.mount(mSourceDir);

        // Arrange for unmount
        // Process is alive before fusermount and not alive after.
        when(mMockProcess.isAlive()).thenReturn(true, false);
        when(mMockRunUtil.runTimedCmd(
                        anyLong(), eq("fusermount"), eq("-u"), eq(mountPoint.toString())))
                .thenReturn(mMockCommandResult);
        when(mMockCommandResult.getStatus()).thenReturn(CommandStatus.SUCCESS);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);

        mManager.unmount(mSourceDir);

        verify(mMockProcess, never()).destroyForcibly();
        // Check that the mount point directory was deleted
        assertFalse(new File(mountPoint.toString()).exists());
    }

    @Test
    public void testUnmount_gracefulFail_forcefulSuccess() throws Exception {
        // Perform a successful mount.
        when(mMockDriverProfile.isMountPointReady(any(Path.class))).thenReturn(true);
        Path mountPoint = mManager.mount(mSourceDir);

        // Arrange for unmount where fusermount fails and process stays alive.
        when(mMockProcess.isAlive()).thenReturn(true);
        when(mMockRunUtil.runTimedCmd(
                        anyLong(), eq("fusermount"), eq("-u"), eq(mountPoint.toString())))
                .thenReturn(mMockCommandResult);
        when(mMockCommandResult.getStatus()).thenReturn(CommandStatus.FAILED);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);

        mManager.unmount(mSourceDir);

        verify(mMockProcess).destroyForcibly();
    }

    // Tests for unmountAll()

    @Test
    public void testUnmountAll() throws Exception {
        // Mount two different source paths
        Path sourceDir2 = tempFolder.newFolder("source-dir-2").toPath();
        when(mMockDriverProfile.isMountPointReady(any(Path.class))).thenReturn(true);
        mManager.mount(mSourceDir);
        mManager.mount(sourceDir2);

        // Create a spy to verify calls to the public unmount() method
        FuseMountManager spyManager = spy(mManager);

        spyManager.unmountAll();

        // Verify that unmount() was called once for each source path.
        verify(spyManager, times(1)).unmount(mSourceDir);
        verify(spyManager, times(1)).unmount(sourceDir2);
    }
}