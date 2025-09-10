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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/* Generic test runner for ART.
 *  - It receives all needed data as one tarball(produced as build step of ART).
 *  - Information/metadata about the individual tests is stored in JSON file(s).
 *  - The tests are exectued as sequence of 'adb push' and 'adb shell' commands.
 *  - In general, we just execute a single BASH file per test using 'adb shell'.
 *  - ART translates any test-specific python steps into a per-test BASH script.
 *
 *  The JSON file might look like the following (subject to change):
 *  {
 *    "001-HelloWorld#1f5cdb07": {
 *       "adb push": [
 *         [ "../target/001-HelloWorld", "/data/local/tmp/art/test/1f5cdb07" ],
 *         [ "001-HelloWorld/1f5cdb07.sh", "/data/local/tmp/art/test/1f5cdb07/run.sh" ]
 *       ],
 *       "adb shell": [
 *         [ "sh", "/data/local/tmp/art/test/1f5cdb07/run.sh" ]
 *       ],
 *       "dependencies": [
 *         "setup#compile-boot-image"
 *       ]
 *    },
 *    "setup#compile-boot-image": {
 *       "adb push": [
 *         [ "setup.sh", "/data/local/tmp/art/setup.sh" ]
 *       ],
 *       "adb shell": [
 *         [ "rm", "-rf", "/data/local/tmp/art/test" ],
 *         [ "sh", "/data/local/tmp/art/setup.sh" ]
 *       ]
 *    }
 *  }
 */
public class ArtTest implements IShardableTest, ITestFilterReceiver {
    protected TestInformation mTestInfo;
    protected File mRunnerDir;
    protected Map<String, JSONObject> mTests = new TreeMap<String, JSONObject>();
    protected Map<String, Boolean> mTestResults = new TreeMap<String, Boolean>();
    protected int mShardIndex = 0;
    protected int mShardCount = 1;

    private Set<String> mIncludeFilters = new TreeSet<>();
    private Set<String> mExcludeFilters = new TreeSet<>();

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
        try {
            try {
                tmp_dir = FileUtil.createTempDir("art-tf-");
                mRunnerDir = extractTarBall(tmp_dir);
                extractZipApex(tmp_dir);
                mTests = loadTests();
                mTestResults.clear(); // TF might reuse the same instance for multiple runs.
            } catch (IOException | JSONException e) {
                throw new RuntimeException(e);
            }

            long startTime = System.currentTimeMillis();
            try {
                clean();
                listener.testRunStarted("ArtTest", mTests.size());
                for (Map.Entry<String, JSONObject> entry : mTests.entrySet()) {
                    runTest(entry.getKey(), entry.getValue(), listener);
                }
                clean();
            } catch (JSONException e) {
                listener.testRunFailed(e.toString());
                throw new RuntimeException(e);
            } catch (DeviceNotAvailableException e) {
                listener.testRunFailed(e.toString());
                throw e;
            } finally {
                listener.testRunEnded(
                        System.currentTimeMillis() - startTime, Collections.emptyMap());
            }
        } finally {
            FileUtil.recursiveDelete(tmp_dir);
        }
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

    TreeMap<String, JSONObject> loadTests() throws IOException, JSONException {
        TreeMap<String, JSONObject> allTests = new TreeMap<String, JSONObject>();
        for (File jsonFile : mRunnerDir.listFiles((d, n) -> n.endsWith(".json"))) {
            JSONObject jsonData = new JSONObject(FileUtil.readStringFromFile(jsonFile));
            for (Iterator<?> it = jsonData.keys(); it.hasNext(); ) {
                String name = (String) it.next();
                allTests.put(name, jsonData.getJSONObject(name));
            }
        }

        TreeMap<String, JSONObject> shardedTests = new TreeMap<String, JSONObject>();
        Queue<String> dependencyQueue = new ArrayDeque<>();

        // Select tests for this shard based on index modulo shardCount
        ArrayList<String> sortedTestNames = new ArrayList<>(allTests.keySet());
        for (int i = 0; i < sortedTestNames.size(); i++) {
            if (i % mShardCount == mShardIndex) {
                String testName = sortedTestNames.get(i);
                if (!shouldIncludeTest(testName)) {
                    continue;
                }
                shardedTests.put(testName, allTests.get(testName));
                dependencyQueue.add(testName);
            }
        }

        // Include dependencies recursively
        while (!dependencyQueue.isEmpty()) {
            String testName = dependencyQueue.remove();
            JSONObject test = allTests.get(testName);
            JSONArray dependencies = test.optJSONArray("dependencies");
            for (int i = 0; dependencies != null && i < dependencies.length(); i++) {
                String depName = dependencies.getString(i);
                if (!shardedTests.containsKey(depName)) {
                    shardedTests.put(depName, allTests.get(depName));
                    dependencyQueue.add(depName);
                }
            }
        }

        return shardedTests;
    }

    Boolean runTest(String name, JSONObject test, ITestInvocationListener listener)
            throws JSONException, DeviceNotAvailableException {
        if (mTestResults.containsKey(name)) {
            return mTestResults.get(name);
        }

        String error = null;
        JSONArray dependencies = test.optJSONArray("dependencies");
        for (int i = 0; dependencies != null && i < dependencies.length(); i++) {
            String dep = dependencies.getString(i);
            if (!runTest(dep, mTests.get(dep), listener)) {
                error = "Dependency '" + dep + "' failed";
                break;
            }
        }

        TestDescription testId = TestDescription.fromString(name);
        try {
            if (error == null) {
                CLog.i("Running ART test %s", name);
            }
            listener.testStarted(testId);
            if (error == null) {
                error = adbPush(test);
            }
            if (error == null) {
                error = adbShell(test);
            }
        } catch (JSONException | DeviceNotAvailableException e) {
            error = e.toString();
            throw e;
        } finally {
            // Check if it might be an issue with the device.
            if (error != null) {
                if (!adbPing()) {
                    error = "Device unavailable\n---\n" + error;
                    CLog.e("Device unavailable, sleep...");
                    try {
                        TimeUnit.SECONDS.sleep(100);
                    } catch (InterruptedException e) {
                        CLog.e("InterruptedException");
                    }
                    if (!adbPing()) {
                        // Still unavailable, give up on remaining tests.
                        throw new DeviceNotAvailableException();
                    }
                }
                listener.testFailed(testId, FailureDescription.create(error));
            }

            listener.testEnded(testId, Collections.emptyMap());
            mTestResults.put(name, (error == null));
        }
        return error == null;
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

    String adbPush(JSONObject test) throws JSONException, DeviceNotAvailableException {
        JSONArray cmds = test.optJSONArray("adb push");
        for (int i = 0; cmds != null && i < cmds.length(); i++) {
            JSONArray cmd = cmds.getJSONArray(i);
            if (cmd.length() != 2) {
                throw new RuntimeException("Invalid adb push command: " + cmd.toString());
            }
            File src = new File(mRunnerDir, cmd.getString(0));
            String dst = cmd.getString(1);

            ITestDevice device = mTestInfo.getDevice();
            if (src.isDirectory()) {
                if (!device.pushDir(src, dst)) {
                    return "Failed to push directory '" + src + "'";
                }
            } else {
                if (!device.pushFile(src, dst)) {
                    return "Failed to push file '" + src + "'";
                }
            }
        }
        return null;
    }

    String adbShell(JSONObject test) throws JSONException, DeviceNotAvailableException {
        JSONArray cmds = test.optJSONArray("adb shell");
        for (int i = 0; cmds != null && i < cmds.length(); i++) {
            JSONArray args = cmds.getJSONArray(i);
            StringBuilder cmdBuilder = new StringBuilder();
            for (int j = 0; j < args.length(); j++) {
                if (j > 0) {
                    cmdBuilder.append(" ");
                }
                cmdBuilder.append(args.getString(j));
            }
            String cmd = cmdBuilder.toString();
            CommandResult res = mTestInfo.getDevice().executeShellV2Command(cmd);
            if (res.getStatus() != CommandStatus.SUCCESS) {
                return String.format("stdout:\n%s\nstderr:\n%s", res.getStdout(), res.getStderr());
            }
        }
        return null;
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
            shards.add(shard);
        }
        return shards;
    }
}
