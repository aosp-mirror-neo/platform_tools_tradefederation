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


package com.android.tradefed.testtype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;

import com.google.gson.Gson;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Unit tests for {@link ArtTest}. */
@RunWith(JUnit4.class)
public class ArtTestTest {
    private ArtTest mTest;

    @Mock ITestInvocationListener mListener;
    @Mock ITestDevice mDevice;
    @Mock TestInformation mTestInfo;

    static class ArtTestForTesting extends ArtTest {
        public static final List<String> TAGS = Arrays.asList("root", "no-root");

        Test[] mockInput() {
            return new Test[] {
                new Test(
                        "001-HelloWorld#1f5cdb07",
                        TAGS,
                        Arrays.asList("setup#compile-boot-image"),
                        new Command(
                                "push",
                                "../target/001-HelloWorld",
                                "/data/local/tmp/art/test/1f5cdb07"),
                        new Command(
                                "push",
                                "001-HelloWorld/1f5cdb07.sh",
                                "/data/local/tmp/art/test/1f5cdb07/run.sh"),
                        new Command("shell", "sh", "/data/local/tmp/art/test/1f5cdb07/run.sh")),
                new Test("002-Foo#1f5cdb08", TAGS, new ArrayList<>()),
                new Test("003-Bar#1f5cdb09", TAGS, new ArrayList<>()),
                new Test(
                        "setup#compile-boot-image",
                        TAGS,
                        new ArrayList<>(),
                        new Command("push", "setup.sh", "/data/local/tmp/art/setup.sh"),
                        new Command("shell", "rm", "-rf", "/data/local/tmp/art/test"),
                        new Command("shell", "sh", "/data/local/tmp/art/setup.sh")),
            };
        }

        @Override
        File extractTarBall(File dst_dir) throws IOException {
            File runnerDir = new File(dst_dir, "runner");
            runnerDir.mkdirs();
            try (FileWriter fw = new FileWriter(new File(runnerDir, "tests.json"))) {
                new Gson().toJson(mockInput(), fw);
            }
            return runnerDir;
        }
    }

    @Before
    public void setUp() throws IOException {
        mTest = new ArtTestForTesting();
        mListener = mock(ITestInvocationListener.class);
        mDevice = mock(ITestDevice.class);
    }

    @Test
    public void testRunTests() throws DeviceNotAvailableException {
        // Create mock test info.
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mDevice);
        TestInformation info = TestInformation.newBuilder().setInvocationContext(context).build();

        // Implement mocked methods by just always returning SUCCESS (no device is needed).
        CommandResult success = new CommandResult(CommandStatus.SUCCESS);
        when(mDevice.executeShellCommand(eq("cat /proc/cpuinfo"))).thenReturn("processor: 8\n");
        when(mDevice.pushFile(any(), any())).thenReturn(true);
        when(mDevice.pushDir(any(), any())).thenReturn(true);
        when(mDevice.executeShellV2Command(any(), anyLong(), any(), anyInt())).thenReturn(success);
        when(mDevice.executeShellV2Command(any())).thenReturn(success);

        // Run the test.
        mTest.run(info, mListener);
        for (ArtTest.Test test : mTest.mTests) {
            assertTrue(test.mResult.isPresent());
            assertTrue(test.mResult.get());
        }

        // Check the recorded behaviour of test.  We expected the following commands to be executed:
        verify(mDevice)
                .executeShellV2Command(
                        eq("rm -rf /data/local/tmp/art/test"), anyLong(), any(), anyInt());
        verify(mDevice)
                .executeShellV2Command(
                        eq("sh /data/local/tmp/art/setup.sh"), anyLong(), any(), anyInt());
        verify(mDevice)
                .executeShellV2Command(
                        eq("sh /data/local/tmp/art/test/1f5cdb07/run.sh"),
                        anyLong(),
                        any(),
                        anyInt());
    }

    @Test
    public void testSharding() throws Exception {
        ArtTestForTesting mainTest = new ArtTestForTesting();

        Collection<IRemoteTest> shards = mainTest.split(2);
        assertEquals(2, shards.size());

        File tempDir = null;
        try {
            tempDir = FileUtil.createTempDir("art-test-sharding");

            // Shard 0
            ArtTest shard0 = (ArtTest) ((List<IRemoteTest>) shards).get(0);
            assertEquals(0, shard0.mShardIndex);
            assertEquals(2, shard0.mShardCount);
            shard0.mRunnerDir = shard0.extractTarBall(new File(tempDir, "shard0_basedir"));
            List<ArtTest.Test> testsShard0 = shard0.loadTests();
            Set<String> names0 = testsShard0.stream().map(t -> t.mName).collect(Collectors.toSet());
            assertEquals(3, names0.size());
            assertTrue(names0.contains("001-HelloWorld#1f5cdb07"));
            assertTrue(names0.contains("002-Foo#1f5cdb08"));
            assertTrue(names0.contains("setup#compile-boot-image"));

            // Shard 1
            ArtTest shard1 = (ArtTest) ((List<IRemoteTest>) shards).get(1);
            assertEquals(1, shard1.mShardIndex);
            assertEquals(2, shard1.mShardCount);
            shard1.mRunnerDir = shard1.extractTarBall(new File(tempDir, "shard1_basedir"));
            List<ArtTest.Test> testsShard1 = shard1.loadTests();
            Set<String> names1 = testsShard1.stream().map(t -> t.mName).collect(Collectors.toSet());
            assertEquals(2, names1.size());
            assertTrue(names1.contains("003-Bar#1f5cdb09"));
            assertTrue(names1.contains("setup#compile-boot-image"));

        } finally {
            FileUtil.recursiveDelete(tempDir);
        }
    }

    @Test
    public void testIncludeFilter() throws Exception {
        ArtTest test = new ArtTest();
        test.addIncludeFilter("test1");
        assertTrue(test.shouldIncludeTest("test1"));
        assertTrue(!test.shouldIncludeTest("test2"));

        test = new ArtTestForTesting();
        test.addIncludeFilter("001-HelloWorld#1f5cdb07");
        File tempDir = null;
        try {
            tempDir = FileUtil.createTempDir("art-test-sharding");
            test.mRunnerDir = test.extractTarBall(tempDir);
            List<ArtTest.Test> tests = test.loadTests();
            Set<String> names = tests.stream().map(t -> t.mName).collect(Collectors.toSet());
            assertEquals(2, tests.size());
            assertTrue(names.contains("001-HelloWorld#1f5cdb07"));
            assertTrue(!names.contains("002-Foo#1f5cdb08"));
            assertTrue(!names.contains("003-Bar#1f5cdb09"));
            assertTrue(names.contains("setup#compile-boot-image"));
        } finally {
            FileUtil.recursiveDelete(tempDir);
        }
    }

    @Test
    public void testExcludeFilter() throws Exception {
        ArtTest test = new ArtTest();
        test.addExcludeFilter("test1");
        assertTrue(!test.shouldIncludeTest("test1"));
        assertTrue(test.shouldIncludeTest("test2"));

        test = new ArtTestForTesting();
        test.addExcludeFilter("001-HelloWorld#1f5cdb07");
        File tempDir = null;
        try {
            tempDir = FileUtil.createTempDir("art-test-sharding");
            test.mRunnerDir = test.extractTarBall(tempDir);
            List<ArtTest.Test> tests = test.loadTests();
            Set<String> names = tests.stream().map(t -> t.mName).collect(Collectors.toSet());
            assertEquals(3, tests.size());
            assertTrue(!names.contains("001-HelloWorld#1f5cdb07"));
            assertTrue(names.contains("002-Foo#1f5cdb08"));
            assertTrue(names.contains("003-Bar#1f5cdb09"));
            assertTrue(names.contains("setup#compile-boot-image"));
        } finally {
            FileUtil.recursiveDelete(tempDir);
        }
    }
}
