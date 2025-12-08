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

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.SearchArtifactUtil;
import com.android.tradefed.util.TarUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/* Generic test runner for ART.
 *  - It receives all needed data as one tarball(produced as build step of ART).
 *  - Information/metadata about the individual tests is stored in JSON file(s).
 *  - The tests are exectued as sequence of 'adb push' and 'adb shell' commands.
 *  - In general, we just execute a single BASH file per test using 'adb shell'.
 *  - ART translates any test-specific python steps into a per-test BASH script.
 *
 *  The JSON file might look like the following (subject to change):
 *  [{
 *    "name": "001-HelloWorld#1f5cdb07",
 *    "tags": ["armeabi-v7a"],
 *    "deps": ["setup#compile-boot-image"],
 *    "cmds": [{
 *      "kind": "push",
 *      "args": ["../target/001-HelloWorld", "/data/local/tmp/art/test/1f5cdb07"]
 *    }, {
 *      "kind": "push",
 *      "args": ["001-HelloWorld/1f5cdb07.sh", "/data/local/tmp/art/test/1f5cdb07/run.sh"]
 *    }, {
 *      "kind": "shell",
 *      "args": ["sh", "/data/local/tmp/art/test/1f5cdb07/run.sh"]
 *    }]
 *  }, {
 *    "name": "setup#compile-boot-image",
 *    "cmds": [{
 *      "kind": "push",
 *      "args": ["setup.sh", "/data/local/tmp/art/setup.sh"]
 *    }, {
 *      "kind": "shell",
 *      "args": ["rm", "-rf", "/data/local/tmp/art/test"]
 *    }, {
 *      "kind": "shell",
 *      "args": ["sh", "/data/local/tmp/art/setup.sh"]
 *    }]
 *  }]
 */
public class ArtTest implements IShardableTest, IAbiReceiver, ITestFilterReceiver {
    private static final int ADB_TIMEOUT = 900;
    private static final int ADB_RETRIES = 3;

    protected TestInformation mTestInfo;
    protected File mRunnerDir;
    protected List<Test> mTests = new ArrayList<>();
    protected int mShardIndex = 0;
    protected int mShardCount = 1;

    private IAbi mAbi;
    private Set<String> mIncludeFilters = new TreeSet<>();
    private Set<String> mExcludeFilters = new TreeSet<>();

    @Option(name = "testJobs", description = "The number of parallel adb shell commands")
    private int mTestJobs = 0;

    private transient Semaphore mTestSemaphore;

    // Tradefed's push implementation has higher latency than native "adb push" command.
    // Hide the latency by allowing more parallel pushes before each test starts.
    @Option(name = "pushJobs", description = "The number of parallel adb push commands")
    private int mPushJobs = 0;

    private transient Semaphore mPushSemaphore;

    @Option(name = "shuffle", description = "Shuffle test order before running")
    private boolean mShuffle = true;

    @Override
    public void setAbi(IAbi abi) {
        mAbi = abi;
    }

    @Override
    public IAbi getAbi() {
        return mAbi;
    }

    @Override
    public void addIncludeFilter(String filter) {
        mIncludeFilters.add(filter);
    }

    @Override
    public void addAllIncludeFilters(Set<String> filters) {
        mIncludeFilters.addAll(filters);
    }

    @Override
    public void addExcludeFilter(String filter) {
        mExcludeFilters.add(filter);
    }

    @Override
    public void addAllExcludeFilters(Set<String> filters) {
        mExcludeFilters.addAll(filters);
    }

    @Override
    public Set<String> getIncludeFilters() {
        return mIncludeFilters;
    }

    @Override
    public Set<String> getExcludeFilters() {
        return mExcludeFilters;
    }

    @Override
    public void clearIncludeFilters() {
        mIncludeFilters.clear();
    }

    @Override
    public void clearExcludeFilters() {
        mExcludeFilters.clear();
    }

    boolean shouldIncludeTest(String testName) {
        for (String filter : mExcludeFilters) {
            if (testName.matches(filter)) {
                return false;
            }
        }
        for (String filter : mIncludeFilters) {
            if (testName.matches(filter)) {
                return true;
            }
        }
        return mIncludeFilters.isEmpty();
    }

    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        mTestInfo = testInfo;
        File tmp_dir = null;
        ExecutorService executor = null;
        try {
            try {
                tmp_dir = FileUtil.createTempDir("art-tf-");
                mRunnerDir = extractTarBall(tmp_dir);
                extractZipApex(tmp_dir);
                mTests = loadTests();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            long startTime = System.currentTimeMillis();
            try {
                init();
                clean();
                int jobs = mTestJobs + mPushJobs;
                CLog.i("Using %s threads (testJobs:%s, pushJobs:%s)", jobs, mTestJobs, mPushJobs);
                executor = Executors.newFixedThreadPool(jobs);
                final ExecutorService executorRef = executor;
                listener.testRunStarted("ArtTest", mTests.size());

                // Schedule all tests to run.
                List<Future<Boolean>> futures =
                        mTests.stream()
                                .map(test -> executorRef.submit(() -> run(test, listener)))
                                .collect(Collectors.toList());

                // Wait for all tests to complete.
                for (Future<Boolean> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                }

                clean();
            } catch (RuntimeException e) {
                listener.testRunFailed(e.toString());
                throw e;
            } catch (DeviceNotAvailableException e) {
                listener.testRunFailed(e.toString());
                throw e;
            } finally {
                if (executor != null) {
                    executor.shutdown();
                }
                listener.testRunEnded(
                        System.currentTimeMillis() - startTime, Collections.emptyMap());
            }
        } finally {
            FileUtil.recursiveDelete(tmp_dir);
        }
    }

    void init() throws DeviceNotAvailableException {
        if (mTestJobs == 0) {
            mTestJobs = (int) getDeviceCpuCount();
        }
        if (mPushJobs == 0) {
            mPushJobs = mTestJobs * 8;
        }
        mTestSemaphore = new Semaphore(mTestJobs, true);
        mPushSemaphore = new Semaphore(mPushJobs, true);
    }

    long getDeviceCpuCount() throws DeviceNotAvailableException {
        String stdout = mTestInfo.getDevice().executeShellCommand("cat /proc/cpuinfo");
        if (stdout != null) {
            return Stream.of(stdout.split("\\n")).filter(l -> l.startsWith("processor")).count();
        }
        CLog.e("Failed to get device CPU count");
        return 4;
    }

    File extractTarBall(File dst_dir) throws IOException {
        File archive = SearchArtifactUtil.searchFile("art-test-target.tgz", false);
        TarUtil.unTar(TarUtil.unGzip(archive, dst_dir), dst_dir);
        return new File(dst_dir, "runner");
    }

    void extractZipApex(File dst_dir) throws IOException {
        File archive = SearchArtifactUtil.searchFile("art-test-target-apex.zip", false);
        if (archive != null) {
            ZipUtil.extractZip(new ZipFile(archive), dst_dir);
        }
    }

    List<Test> loadTests() throws IOException {
        // Load and parse JSON files.
        Map<String, Test> allTests = new TreeMap<>();
        for (File jsonFile : mRunnerDir.listFiles((d, n) -> n.endsWith(".json"))) {
            Gson gson = new Gson();
            for (Test test : gson.fromJson(FileUtil.readStringFromFile(jsonFile), Test[].class)) {
                allTests.put(test.mName, test);
            }
        }

        // Finish object initialization.
        for (Test test : allTests.values()) {
            Stream<String> deps = test.mDependencies.stream();
            test.mResolvedDependencies =
                    deps.map(depName -> allTests.get(depName)).toArray(Test[]::new);
            test.mResult = Optional.empty();
        }

        // Filter tests by name and abi.
        List<Test> tests =
                allTests.values().stream()
                        .sorted(Comparator.comparing(a -> a.mName))
                        .filter(test -> shouldIncludeTest(test.mName))
                        .filter(test -> mAbi == null || test.mTags.contains(mAbi.getName()))
                        .collect(Collectors.toList());

        // Shuffle tests in a deterministic and well-distributed way.
        // This will uniformly spread expensive tests over time and over shards.
        if (mShuffle) {
            ToIntFunction<Integer> hash = i -> (i * 0x9E3779B1); // Prime near golden ratio.
            Stream<Integer> indices = IntStream.range(0, tests.size()).boxed();
            Stream<Integer> permutation = indices.sorted(Comparator.comparingInt(hash));
            final List<Test> testsCopy = tests; // Effectively final copy for lambda
            tests = permutation.map(i -> testsCopy.get(i)).collect(Collectors.toList());
        }

        // Filter tests for this shard.
        if (mShardCount > 1) {
            Stream<Integer> indices = IntStream.range(0, tests.size()).boxed();
            Stream<Integer> shard = indices.filter(i -> i % mShardCount == mShardIndex);
            final List<Test> testsCopy = tests; // Effectively final copy for lambda
            tests = shard.map(i -> testsCopy.get(i)).collect(Collectors.toList());
        }

        // Also include all dependencies recursively.
        Queue<Test> todo = new ArrayDeque<>(tests);
        Set<String> names = tests.stream().map(t -> t.mName).collect(Collectors.toSet());
        while (!todo.isEmpty()) {
            Test test = todo.remove();
            for (Test dep : test.mResolvedDependencies) {
                if (names.add(dep.mName)) {
                    tests.add(dep);
                    todo.add(dep);
                }
            }
        }

        return tests;
    }

    static class Command {
        @SerializedName("kind")
        String mKind;

        @SerializedName("args")
        List<String> mArgs = new ArrayList<>();

        Command() {}

        Command(String kind, String... args) {
            mKind = kind;
            mArgs.addAll(Arrays.asList(args));
        }
    }

    static class Test {
        @SerializedName("name")
        String mName;

        @SerializedName("tags")
        Set<String> mTags = new TreeSet<>();

        @SerializedName("deps")
        List<String> mDependencies = new ArrayList<>();

        @SerializedName("cmds")
        List<Command> mCommands = new ArrayList<>();

        transient Optional<Boolean> mResult = Optional.empty();
        transient Test[] mResolvedDependencies = new Test[0];

        Test() {}

        Test(String name, List<String> deps, Command... commands) {
            mName = name;
            mDependencies.addAll(deps);
            mCommands.addAll(Arrays.asList(commands));
        }
    }

    Boolean run(Test test, ITestInvocationListener listener) throws DeviceNotAvailableException {
        synchronized (test) {
            // Check if the test has already completed (e.g., as a dependency of another test)
            if (test.mResult.isPresent()) {
                return test.mResult.get();
            }

            String error = null;
            for (Test dep : test.mResolvedDependencies) {
                if (!run(dep, listener)) {
                    error = "Dependency '" + dep + "' failed";
                    break;
                }
            }

            TestDescription testId = TestDescription.fromString(test.mName);
            long startTime = System.currentTimeMillis();
            try {
                if (error == null) {
                    CLog.i("Running ART test %s", test.mName);
                }
                if (error == null) {
                    try {
                        error = executeCommands(test.mCommands);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            } catch (DeviceNotAvailableException e) {
                error = e.toString();
                throw e;
            } finally {
                long endTime = System.currentTimeMillis();
                // Check if it might be an issue with the device.
                if (error != null) {
                    if (!adbPing()) {
                        error = "Device unavailable\n---\n" + error;
                        CLog.e("Device unavailable, sleep...");
                        try {
                            TimeUnit.SECONDS.sleep(100);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        if (!adbPing()) {
                            // Still unavailable, give up on remaining tests.
                            throw new DeviceNotAvailableException();
                        }
                    }
                }
                synchronized (listener) {
                    listener.testStarted(testId, startTime);
                    if (error != null) {
                        listener.testFailed(testId, FailureDescription.create(error));
                    }
                    listener.testEnded(testId, endTime, Collections.emptyMap());
                }
                test.mResult = Optional.of(error == null);
            }
            return error == null;
        }
    }

    String executeCommands(List<Command> commands)
            throws DeviceNotAvailableException, InterruptedException {
        ITestDevice device = mTestInfo.getDevice();
        for (Command cmd : commands) {
            if (cmd.mKind.equals("push")) {
                if (cmd.mArgs.size() != 2) {
                    throw new RuntimeException("Invalid adb push command: " + cmd.mArgs);
                }
                File src = new File(mRunnerDir, cmd.mArgs.get(0));
                String dst = cmd.mArgs.get(1);

                mPushSemaphore.acquire();
                try {
                    if (src.isDirectory()) {
                        if (!device.pushDir(src, dst)) {
                            return "Failed to push directory '" + src + "'";
                        }
                    } else {
                        if (!device.pushFile(src, dst)) {
                            return "Failed to push file '" + src + "'";
                        }
                    }
                } finally {
                    mPushSemaphore.release();
                }
            } else if (cmd.mKind.equals("shell")) {
                mTestSemaphore.acquire();
                try {
                    String shellCmd = String.join(" ", cmd.mArgs);
                    CommandResult res =
                            device.executeShellV2Command(
                                    shellCmd, ADB_TIMEOUT, TimeUnit.SECONDS, ADB_RETRIES);
                    if (res.getStatus() != CommandStatus.SUCCESS) {
                        return String.format(
                                "stdout:\n%s\nstderr:\n%s", res.getStdout(), res.getStderr());
                    }
                } finally {
                    mTestSemaphore.release();
                }
            } else {
                throw new RuntimeException("Unknown command kind: " + cmd.mKind);
            }
        }
        return null;
    }

    void clean() throws DeviceNotAvailableException {
        ITestDevice device = mTestInfo.getDevice();
        device.enableAdbRoot(); // Ensure we have the rights to delete everything.
        device.deleteFile("/data/local/tmp/art");
    }

    // Check device health by executing trivial shell command.
    Boolean adbPing() {
        try {
            String cmd = "echo ping";
            CommandResult res = mTestInfo.getDevice().executeShellV2Command(cmd);
            String stdout = res.getStdout().strip();
            return res.getStatus() == CommandStatus.SUCCESS && stdout.equals("ping");
        } catch (DeviceNotAvailableException e) {
            return false;
        }
    }

    @Override
    public Collection<IRemoteTest> split(int shardCount) {
        Collection<IRemoteTest> shards = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            // Clone this class, even if it is derived class (used for testing).
            ArtTest shard;
            try {
                shard = this.getClass().getDeclaredConstructor().newInstance();
            } catch (InstantiationException
                    | IllegalAccessException
                    | InvocationTargetException
                    | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
            try {
                OptionCopier.copyOptions(this, shard);
            } catch (ConfigurationException e) {
                CLog.e("failed to copy ART test options: %s", e.getMessage());
                throw new RuntimeException(e);
            }
            shard.mShardIndex = i;
            shard.mShardCount = shardCount;
            shard.mAbi = this.mAbi;
            shard.mIncludeFilters = new TreeSet<>(this.mIncludeFilters);
            shard.mExcludeFilters = new TreeSet<>(this.mExcludeFilters);
            shards.add(shard);
        }
        return shards;
    }
}
