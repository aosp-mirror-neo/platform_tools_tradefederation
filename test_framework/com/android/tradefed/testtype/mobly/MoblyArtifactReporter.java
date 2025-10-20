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

package com.android.tradefed.testtype.mobly;

import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;

import java.io.File;

/** Mobly artifact reporter. */
public class MoblyArtifactReporter {
    private final File mLogDir;

    public MoblyArtifactReporter(File logDir) {
        mLogDir = logDir;
    }

    public void reportRunArtifacts(ITestInvocationListener listener) {
        reportArtifactsForRecord(listener, null);
    }

    public void reportArtifactsForRecord(
            ITestInvocationListener listener, MoblyYamlResultRecordHandler.Record record) {
        reportArtifactsForRecord(mLogDir, listener, record);
    }

    private void reportArtifactsForRecord(
            File logDir,
            ITestInvocationListener listener,
            MoblyYamlResultRecordHandler.Record record) {
        for (File subFile : logDir.listFiles()) {
            if (subFile.isDirectory()) {
                reportArtifactsForRecord(subFile, listener, record);
            } else {
                if (!subFile.exists()) {
                    continue;
                }

                if (record != null) {
                    String absolutePath = subFile.getAbsolutePath();
                    if (absolutePath != null
                            && absolutePath.contains(record.getTestClass())
                            && absolutePath.contains(record.getTestName())) {
                        reportFile(subFile, listener);
                    }
                } else {
                    reportFile(subFile, listener);
                }
            }
        }
    }

    private void reportFile(File file, ITestInvocationListener listener) {
        if (!file.exists()) {
            return;
        }

        // Report and delete the file.
        try (InputStreamSource dataStream = new FileInputStreamSource(file, true)) {
            String cleanName = file.getName().replace(",", "_");
            LogDataType type = LogDataType.TEXT;
            if (cleanName.contains("trace")) {
                type = LogDataType.PERFETTO;
            } else if (cleanName.contains("logcat")) {
                type = LogDataType.LOGCAT;
            } else if (cleanName.contains("btsnoop")) {
                type = LogDataType.BT_SNOOP_LOG;
            } else if (cleanName.contains("mp4")) {
                type = LogDataType.MP4;
            } else if (cleanName.contains("bugreport") && cleanName.endsWith("zip")) {
                type = LogDataType.BUGREPORTZ;
            } else if (cleanName.endsWith("png")) {
                type = LogDataType.PNG;
            } else if (cleanName.endsWith("jpg") || cleanName.endsWith("jpeg")) {
                type = LogDataType.JPEG;
            }

            listener.testLog(cleanName, type, dataStream);
        }
    }
}
