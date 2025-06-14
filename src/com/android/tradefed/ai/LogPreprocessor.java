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
package com.android.tradefed.ai;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ResourceUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.IOException;

/** Preprocessor entry point for logs that will be ingested by AI tools. */
public class LogPreprocessor {

    private static final String LOGCAT_PROCESSOR_RES = "/preprocessor/preprocess_logcat.sh";

    public static File preprocessLogcat(File originalFile) {
        File logcatProcessorScript = null;
        try {
            File processedLogcat =
                    FileUtil.createTempFile(originalFile.getName() + "_preprocessed", ".txt");
            // Unpack the resource
            logcatProcessorScript = FileUtil.createTempFile("preprocess_logcat", ".sh");
            ResourceUtil.extractResourceToFile(LOGCAT_PROCESSOR_RES, logcatProcessorScript);
            logcatProcessorScript.setExecutable(true);
            CommandResult preprocessStatus =
                    RunUtil.getDefault()
                            .runTimedCmd(
                                    20000L,
                                    logcatProcessorScript.getAbsolutePath(),
                                    "-i",
                                    originalFile.getAbsolutePath(),
                                    "-o",
                                    processedLogcat.getAbsolutePath());
            CLog.d("stdout: %s", preprocessStatus.getStdout());
            CLog.d("stderr: %s", preprocessStatus.getStderr());
            if (CommandStatus.SUCCESS.equals(preprocessStatus.getStatus())) {
                return processedLogcat;
            }
        } catch (IOException | RuntimeException e) {
            CLog.e(e);
        } finally {
            FileUtil.deleteFile(logcatProcessorScript);
        }
        return originalFile;
    }
}
