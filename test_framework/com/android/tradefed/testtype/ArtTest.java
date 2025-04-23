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

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.Log;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.SearchArtifactUtil;
import com.android.tradefed.util.TarUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
public class ArtTest implements IRemoteTest {
    private static final String TAG = "ArtTest";
    private TestInformation mTestInfo;
    private File mRunnerDir;
    private Map<String, JSONObject> mTests = new TreeMap<String, JSONObject>();
    private Set<String> mExecutedTestNames = new HashSet<String>();

    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        mTestInfo = testInfo;
        File tmp_dir = null;
        try {
            try {
                tmp_dir = FileUtil.createTempDir("art-tf-");
                mRunnerDir = extractTarBall(tmp_dir);
                mTests = loadTests();
            } catch (IOException | JSONException e) {
                throw new RuntimeException(e);
            }

            long startTime = System.currentTimeMillis();
            try {
                listener.testRunStarted("ArtTest", mTests.size());
                for (Map.Entry<String, JSONObject> entry : mTests.entrySet()) {
                    runTest(entry.getKey(), entry.getValue(), listener);
                }
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

    TreeMap<String, JSONObject> loadTests() throws IOException, JSONException {
        TreeMap<String, JSONObject> result = new TreeMap<String, JSONObject>();
        for (File jsonFile : mRunnerDir.listFiles((d, n) -> n.endsWith(".json"))) {
            JSONObject jsonData = new JSONObject(FileUtil.readStringFromFile(jsonFile));
            for (Iterator<String> it = jsonData.keys(); it.hasNext(); ) {
                String name = it.next();
                result.put(name, jsonData.getJSONObject(name));
            }
        }
        return result;
    }

    void runTest(String name, JSONObject test, ITestInvocationListener listener)
            throws JSONException, DeviceNotAvailableException {
        if (mExecutedTestNames.contains(name)) {
            return;
        }
        mExecutedTestNames.add(name);

        JSONArray dependencies = test.optJSONArray("dependencies");
        for (int i = 0; dependencies != null && i < dependencies.length(); i++) {
            String dep = dependencies.getString(i);
            runTest(dep, mTests.get(dep), listener);
        }

        TestDescription testId = TestDescription.fromString(name);
        try {
            listener.testStarted(testId);
            clean(test);
            adbPush(test);
            String error = adbShell(test);
            if (error != null) {
                listener.testFailed(testId, error);
            }
            clean(test);
        } catch (JSONException | DeviceNotAvailableException e) {
            listener.testFailed(testId, e.toString());
            throw e;
        } finally {
            listener.testEnded(testId, Collections.emptyMap());
        }
    }

    void clean(JSONObject test) throws JSONException, DeviceNotAvailableException {
        JSONArray cmds = test.optJSONArray("adb push");
        for (int i = 0; cmds != null && i < cmds.length(); i++) {
            JSONArray cmd = cmds.getJSONArray(i);
            if (cmd.length() != 2) {
                throw new RuntimeException("Invalid adb push command: " + cmd.toString());
            }
            String dst = cmd.getString(1);

            ITestDevice device = mTestInfo.getDevice();
            device.deleteFile(dst);
        }
    }

    void adbPush(JSONObject test) throws JSONException, DeviceNotAvailableException {
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
                device.pushDir(src, dst);
            } else {
                device.pushFile(src, dst);
            }
        }
    }

    String adbShell(JSONObject test) throws JSONException, DeviceNotAvailableException {
        JSONArray cmds = test.optJSONArray("adb shell");
        for (int i = 0; cmds != null && i < cmds.length(); i++) {
            JSONArray args = cmds.getJSONArray(i);
            String cmd = args.getString(0);
            for (int j = 1; j < args.length(); j++) {
                cmd += " " + args.getString(j);
            }
            Log.i(TAG, cmd);
            ITestDevice device = mTestInfo.getDevice();
            CommandResult res = device.executeShellV2Command(cmd);
            if (res.getStatus() != CommandStatus.SUCCESS) {
                CLog.i("Stdout: %s", res.getStdout());
                CLog.e("Stderr: %s", res.getStderr());
                CLog.e("Failed: %s", cmd);
                return res.getStderr();
            }
        }
        return null;
    }
}
