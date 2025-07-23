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

import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CasUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** A singleton class for converting a FastCDC-chunked directory to its original directory. */
public class CasConverterFactory {

    private final IRunUtil mRunUtil;
    private static final String CAS_CONVERTER_PATH = "casconverter";
    private final Path mCasConverterPath;
    // The median conversion time is ~7 seconds. Allow extra long time as this is a fallback.
    protected static final long CONVERSION_TIMEOUT_IN_MS = 10 * 60 * 1000; // 10 minutes.

    private CasConverterFactory() {
        this(RunUtil.getDefault(), System.getProperty("TF_JAR_DIR"));
    }

    @VisibleForTesting
    CasConverterFactory(IRunUtil runUtil, String jarDir) {
        this.mRunUtil = runUtil;
        if (Strings.isNullOrEmpty(jarDir)) {
            logAndThrow("TF_JAR_DIR system property is not set.");
        }

        Path casConverterPath = Path.of(jarDir).resolve(CAS_CONVERTER_PATH);
        if (!Files.exists(casConverterPath)) {
            logAndThrow(String.format("Could not find '%s' in '%s'.", CAS_CONVERTER_PATH, jarDir));
        }

        // Ensure `casconverter` is executable. It can be distributed without the execute bit set.
        FileUtil.ensureGroupRWX(casConverterPath.toFile());
        logCasClientVersion(
                casConverterPath,
                version -> {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.CAS_CONVERTER_VERSION, version);
                });
        this.mCasConverterPath = casConverterPath;
    }

    @VisibleForTesting
    CasConverterFactory(Path casConverterPath, IRunUtil runUtil) {
        this.mRunUtil = runUtil;
        this.mCasConverterPath = casConverterPath;
    }

    @VisibleForTesting
    void logCasClientVersion(Path casConverterPath, Consumer<String> versionConsumer) {
        CasUtil.logCasClientVersion(casConverterPath, versionConsumer);
    }

    @VisibleForTesting
    void addInvocationMetrics(InvocationMetricKey key, int value) {
        InvocationMetricLogger.addInvocationMetrics(key, value);
    }

    private void logAndThrow(String message) {
        CLog.e(message);
        addInvocationMetrics(InvocationMetricKey.CAS_CONVERTER_UNAVAILABLE_COUNT, 1);
        throw new IllegalStateException(message);
    }

    private static class LazyHolder {
        static final CasConverterFactory INSTANCE = new CasConverterFactory();
    }

    public static CasConverterFactory getInstance() {
        return LazyHolder.INSTANCE;
    }

    /**
     * Converts a FastCDC-chunked directory back to its source directory. The conversion is in-place
     * and the original directory will be overwritten.
     *
     * <p>The method is synchronized to prevent concurrent conversions from running at the same
     * time. This is rare in practice, but possible. For example, a DeviceBuildInfo and its clone
     * shares the same device image and may attempt to convert it at the same time and there can be
     * a race condition with unpredictable result.
     *
     * @param chunkedDirectory The directory containing the FastCDC chunks and will become the
     *     converted directory on return.
     * @throws IOException If an I/O error occurs.
     */
    public synchronized void convert(Path chunkedDirectory) throws IOException {
        String pathString = chunkedDirectory.toAbsolutePath().toString();
        CommandResult cr =
                mRunUtil.runTimedCmd(
                        CONVERSION_TIMEOUT_IN_MS,
                        mCasConverterPath.toString(), // already absolute path
                        "--src-path",
                        pathString);
        if (cr.getStatus() != CommandStatus.SUCCESS) {
            addInvocationMetrics(InvocationMetricKey.CAS_CONVERTER_FAILURE_COUNT, 1);
            throw new IOException(
                    String.format(
                            "Failed to convert chunked directory %s. Status: %s\n"
                                    + "Stdout: %s\n"
                                    + "Stderr: %s",
                            pathString, cr.getStatus(), cr.getStdout(), cr.getStderr()));
        }
        addInvocationMetrics(InvocationMetricKey.CAS_CONVERTER_SUCCESS_COUNT, 1);
    }
}
