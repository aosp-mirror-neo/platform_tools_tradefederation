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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Unit tests for {@link FuseMountManager}. */
@RunWith(MockitoJUnitRunner.class)
public class FuseMountManagerTest {

    @Mock private IFuseDriverProfile mMockDriverProfile;
    @Mock private IRunUtil mMockRunUtil;
    @Mock private Process mMockProcess;
    @Mock private CommandResult mMockCommandResult;

    private FuseMountManager mManager;
    private Path mSourcePath;
    private Path mMountPoint;

    @Before
    public void setUp() throws IOException {
        mManager = new FuseMountManager(mMockDriverProfile, mMockRunUtil);

        mSourcePath = new File("/test/source").toPath();
        mMountPoint = new File("/test/mount").toPath();

        doNothing().when(mMockDriverProfile).validateSourceDataPath(any(Path.class));
        when(mMockDriverProfile.buildMountCommand(any(Path.class), any(Path.class)))
                .thenReturn(Arrays.asList("fuse-driver", "arg1", "arg2"));
    }

    @Test
    public void testMount_success() throws Exception {
        when(mMockRunUtil.runCmdInBackground(any(List.class), any(ByteArrayOutputStream.class))).thenReturn(mMockProcess);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class)))
                .thenReturn(false); // Does not exit
        when(mMockDriverProfile.getMountTimeoutInMs()).thenReturn(500L);
        when(mMockDriverProfile.isMountPointReady(mMountPoint)).thenReturn(true);

        mManager.mount(mSourcePath, mMountPoint);

        // Infer success by verifying no exceptions were thrown.
    }

    @Test
    public void testMount_alreadyMounted_throwsException() throws Exception {
        when(mMockRunUtil.runCmdInBackground(any(List.class), any(ByteArrayOutputStream.class))).thenReturn(mMockProcess);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(mMockDriverProfile.getMountTimeoutInMs()).thenReturn(500L);
        when(mMockDriverProfile.isMountPointReady(mMountPoint)).thenReturn(true);
        mManager.mount(mSourcePath, mMountPoint); // First mount succeeds

        assertThrows(
                "Should throw IllegalStateException when mounting to an existing mount point.",
                IllegalStateException.class,
                () -> mManager.mount(mSourcePath, mMountPoint));
    }

    @Test
    public void testMount_invalidSource_throwsException() throws IOException {
        doThrow(new IOException()).when(mMockDriverProfile).validateSourceDataPath(any(Path.class));

        assertThrows(
                "Should throw IllegalArgumentException for an invalid source.",
                IllegalArgumentException.class,
                () -> mManager.mount(mSourcePath, mMountPoint));
    }

    @Test
    public void testMount_processExitsImmediately_throwsException() throws Exception {
        when(mMockRunUtil.runCmdInBackground(any(List.class), any(ByteArrayOutputStream.class))).thenReturn(mMockProcess);
        when(mMockDriverProfile.getMountTimeoutInMs()).thenReturn(500L);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true); // Exited
        when(mMockProcess.exitValue()).thenReturn(1);

        assertThrows(
                "Should throw IOException if the mount process exits immediately.",
                IOException.class,
                () -> mManager.mount(mSourcePath, mMountPoint));
    }

    @Test
    public void testMount_mountPointNotReady_throwsExceptionAndCleansUp() throws Exception {
        when(mMockRunUtil.runCmdInBackground(any(List.class), any(ByteArrayOutputStream.class))).thenReturn(mMockProcess);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class)))
                .thenReturn(false); // Still running
        when(mMockDriverProfile.getMountTimeoutInMs()).thenReturn(500L);
        when(mMockDriverProfile.isMountPointReady(mMountPoint)).thenReturn(false); // Not ready

        assertThrows(
                "Should throw IOException if mount point is not ready.",
                IOException.class,
                () -> mManager.mount(mSourcePath, mMountPoint));

        verify(mMockProcess).destroyForcibly();
    }

    @Test
    public void testMount_interrupted_throwsExceptionAndCleansUp() throws Exception {
        when(mMockRunUtil.runCmdInBackground(any(List.class), any(ByteArrayOutputStream.class))).thenReturn(mMockProcess);
        when(mMockDriverProfile.getMountTimeoutInMs()).thenReturn(500L);
        when(mMockDriverProfile.isMountPointReady(mMountPoint)).thenReturn(false); // Not ready
        doThrow(new InterruptedException())
                .when(mMockProcess)
                .waitFor(anyLong(), any(TimeUnit.class));

        assertThrows(
                "Should throw IOException on InterruptedException.",
                IOException.class,
                () -> mManager.mount(mSourcePath, mMountPoint));

        verify(mMockProcess).destroyForcibly();
        assertTrue(
                "Thread interrupted status should be set.", Thread.currentThread().isInterrupted());
    }

    @Test
    public void testUnmount_gracefulSuccess() throws Exception {
        // Mock a successful mount first.
        when(mMockRunUtil.runCmdInBackground(any(List.class), any(ByteArrayOutputStream.class))).thenReturn(mMockProcess);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(mMockDriverProfile.isMountPointReady(mMountPoint)).thenReturn(true);
        mManager.mount(mSourcePath, mMountPoint);

        // Process is alive before fusermount and not alive after.
        when(mMockProcess.isAlive()).thenReturn(true).thenReturn(false);
        when(mMockRunUtil.runTimedCmd(anyLong(), eq("fusermount"), eq("-u"), any(String.class)))
                .thenReturn(mMockCommandResult);
        when(mMockCommandResult.getStatus()).thenReturn(CommandStatus.SUCCESS);

        mManager.unmount(mMountPoint);

        verify(mMockProcess, never()).destroyForcibly();
    }

    @Test
    public void testUnmount_gracefulFail_forcefulSuccess() throws Exception {
        // Mock a successful mount first.
        when(mMockRunUtil.runCmdInBackground(any(List.class), any(ByteArrayOutputStream.class))).thenReturn(mMockProcess);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(mMockDriverProfile.isMountPointReady(mMountPoint)).thenReturn(true);
        mManager.mount(mSourcePath, mMountPoint);

        // Process is alive before and after fusermount.
        when(mMockProcess.isAlive()).thenReturn(true);
        when(mMockRunUtil.runTimedCmd(anyLong(), eq("fusermount"), eq("-u"), any(String.class)))
                .thenReturn(mMockCommandResult);
        when(mMockCommandResult.getStatus()).thenReturn(CommandStatus.FAILED); // fusermount fails
        when(mMockProcess.waitFor(2, TimeUnit.SECONDS))
                .thenReturn(false); // process still doesn't die

        mManager.unmount(mMountPoint);

        verify(mMockProcess).destroyForcibly();
    }

    @Test
    public void testUnmount_notMounted_doesNothing() {
        mManager.unmount(mMountPoint);

        verify(mMockRunUtil, never()).runTimedCmd(anyLong(), any());
    }

    @Test
    public void testUnmountAll_concurrentModificationSafe() throws Exception {
        Path mountPoint1 = new File("/test/mount1").toPath();
        Path mountPoint2 = new File("/test/mount2").toPath();

        // Mock successful mounts for two different points
        when(mMockRunUtil.runCmdInBackground(any(List.class), any(ByteArrayOutputStream.class))).thenReturn(mMockProcess);
        when(mMockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(mMockDriverProfile.isMountPointReady(mountPoint1)).thenReturn(true);
        when(mMockDriverProfile.isMountPointReady(mountPoint2)).thenReturn(true);
        mManager.mount(mSourcePath, mountPoint1);
        mManager.mount(mSourcePath, mountPoint2);

        // Mock the unmount behavior
        when(mMockProcess.isAlive()).thenReturn(true);
        when(mMockRunUtil.runTimedCmd(anyLong(), eq("fusermount"), eq("-u"), any(String.class)))
                .thenReturn(mMockCommandResult);
        when(mMockCommandResult.getStatus()).thenReturn(CommandStatus.SUCCESS);

        mManager.unmountAll();
        mManager.unmount(mountPoint1);

        // Assert that the internal map is now empty. Test by trying to unmount again, which should
        // do nothing.
        // fusermount is called twice, once for each mount point in unmountAll().
        // fusermount is not called in the unmount() call above.
        verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        anyLong(),
                        eq("fusermount"),
                        eq("-u"),
                        eq(mountPoint1.toAbsolutePath().toString()));
        verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        anyLong(),
                        eq("fusermount"),
                        eq("-u"),
                        eq(mountPoint2.toAbsolutePath().toString()));
    }
}
