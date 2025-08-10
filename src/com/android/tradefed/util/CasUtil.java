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

package com.android.tradefed.util;

import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CasUtil {

    public static final String CHUNKS_DIR_NAME = "_chunks";
    public static final String CHUNKS_INDEX_FILENAME = "_chunks_index.json";
    public static final String BOOT_IMAGE_FILENAME = "boot.img";
    public static final String CAS_LOG_ROOT_NAME = "tf-cas-fuse-log-root";
    private static final Pattern LOG_LINE_PATTERN =
            Pattern.compile(
                    "^[IWEF]\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\s+\\d+\\s+[^:]+:\\d+].*");

    private static final IRunUtil mRunUtil = RunUtil.getDefault();

    private CasUtil() {
        throw new AssertionError("CasUtil is a utility class and should not be instantiated.");
    }

    /**
     * Validates the given path is a valid FastCDC-chunked directory.
     *
     * <pre>
     *   device_image.zip/
     *     _chunks/
     *       _chunks_index.json
     *       chunk1.img
     *     _chunks_index.json
     * </pre>
     *
     * Only contents of the source data path are checked for validity. The _chunks_index.json must
     * be present either in the _chunks directory or as a sibling of _chunks (for backwards
     * compatibility).
     *
     * @param path The path to the chunked directory.
     * @throws IOException If the path is not a valid FastCDC-chunked directory.
     */
    public static void validateChunkedDir(Path path) throws IOException {
        if (path == null) {
            throw new IOException("path is null");
        }
        if (!Files.isDirectory(path)) {
            throw new IOException(String.format("'%s' is not a directory", path));
        }
        Path chunksDirPath = path.resolve(CHUNKS_DIR_NAME);
        if (!Files.isDirectory(chunksDirPath)) {
            throw new IOException(
                    String.format(
                            "subdirectory '%s' not found in directory: %s", CHUNKS_DIR_NAME, path));
        }
        List<Path> chunksIndexFilePaths =
                Arrays.asList(
                        chunksDirPath.resolve(CHUNKS_INDEX_FILENAME),
                        path.resolve(CHUNKS_INDEX_FILENAME));
        if (chunksIndexFilePaths.stream().filter(Files::exists).count() == 0) {
            throw new IOException(
                    String.format(
                            "File '%s' not found in directory: '%s'", CHUNKS_INDEX_FILENAME, path));
        }
    }

    /** Returns true if the given path is a valid FastCDC-chunked directory. */
    public static boolean isChunkedDir(Path path) {
        try {
            validateChunkedDir(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns true if the given path is a valid uncompressed device image.
     *
     * @param path The absolute path to the device image.
     * @return True if the path is a valid uncompressed device image, false otherwise.
     */
    public static boolean isUncompressedDeviceImage(Path path) {
        // All device images are expected to have a boot image file.
        return Files.exists(path.resolve(BOOT_IMAGE_FILENAME));
    }

    public static void logCasClientVersion(Path clientPath, Consumer<String> versionHandlerFn) {
        if (clientPath == null) {
            return;
        }

        String clientName = clientPath.getFileName().toString();
        CommandResult cr = mRunUtil.runTimedCmd(5000, clientPath.toString(), "--version");
        if (cr.getStatus() != CommandStatus.SUCCESS) {
            CLog.e(
                    "Failed to extract '%s' version. Status: %s\nStdout: %s\nStderr: %s",
                    clientName, cr.getStatus(), cr.getStdout(), cr.getStderr());
            return;
        }

        String prefix = "version: ";
        String version = cr.getStdout().trim();
        if (version.startsWith(prefix)) {
            version = version.substring(prefix.length());
        } else {
            version = "unknown";
        }
        CLog.i("%s, version: %s", clientName, version);

        if (versionHandlerFn != null) {
            versionHandlerFn.accept(version);
        }
    }

    public static void logCasClientVersion(Path clientPath) {
        logCasClientVersion(clientPath, null);
    }

    public static Path createUniqueCasLogDir(Path logDirRootPath) throws IOException {
        if (logDirRootPath == null) {
            logDirRootPath = Path.of(System.getProperty("java.io.tmpdir"), CAS_LOG_ROOT_NAME);
        }
        Path logDirPath = logDirRootPath.resolve(UUID.randomUUID().toString());
        // The path will be logged by the caller, logging it here would be spammy.
        Files.createDirectories(logDirPath);
        return logDirPath;
    }

    /**
     * Reads the CAS log file from the given directory.
     *
     * @param logDirPath The directory path to read the CAS log file from.
     * @param clientName The name of the CAS client.
     * @param tag The tag of the CAS log file, like "INFO", "WARNING", "ERROR", etc.
     * @param removeLogDirAfterRead Whether to remove the log directory after reading the log file.
     * @return The CAS log file content.
     * @throws IOException If the CAS log file cannot be read.
     */
    public static String getCasLogFromDir(
            Path logDirPath, String clientName, String tag, boolean removeLogDirAfterRead)
            throws IOException {
        if (logDirPath == null) {
            throw new IOException("logDirPath is null");
        }
        String log = readCasLogFileFromDir(logDirPath, clientName, tag);
        if (removeLogDirAfterRead) {
            FileUtil.recursiveDelete(logDirPath.toFile());
        }
        return log;
    }

    /**
     * Reads the CAS log file from the given directory.
     *
     * @param logDirPath The directory path to read the CAS log file from.
     * @param clientName The name of the CAS client.
     * @param tag The tag of the CAS log file, like "INFO", "WARNING", "ERROR", etc.
     * @return The CAS log file content.
     * @throws IOException If the CAS log file cannot be read.
     */
    private static String readCasLogFileFromDir(Path logDirPath, String clientName, String tag)
            throws IOException {
        Path logFilePath = logDirPath.resolve(clientName + "." + tag);
        if (!Files.exists(logFilePath)) {
            CLog.w("CAS log file not found: %s", logFilePath);
            throw new IOException(String.format("CAS log file not found: %s", logFilePath));
        }
        return removeLogHeader(Files.readString(logFilePath));
    }

    /**
     * Reads the CAS log file "<clientName>.INFO" from the given directory and remove the log
     * directory. This is a convenience method that wraps the above method with default parameters.
     * It also handles the IOException and returns null if the log file cannot be read.
     *
     * @param logDirPath The directory path to read the CAS log file from.
     * @param clientName The name of the CAS client.
     * @return The CAS log file content or "" if the log file cannot be read.
     */
    public static String getCasLogFromDir(Path logDirPath, String clientName) {
        try {
            return getCasLogFromDir(logDirPath, clientName, "INFO", true);
        } catch (IOException e) {
            CLog.e(e);
            return null;
        }
    }

    /**
     * Removes the log header from the given log string.
     *
     * <p>The log header are a group of lines before the first log line that conforms to the log
     * line format. There isn't a documented way to suppress the header, so this is a workaround to
     * remove the header which is usually not useful.
     *
     * @param logString The log string to remove the header from.
     * @return The log string with the header removed or the original string if it is null or empty.
     */
    private static String removeLogHeader(String logString) {
        if (logString == null || logString.isEmpty()) {
            return logString;
        }

        String[] lines = logString.split("\\r?\\n");
        StringBuilder resultBuilder = new StringBuilder();
        boolean headerSkipped = false;

        for (String line : lines) {
            String trimmedLine = line.trim();

            if (!headerSkipped) {
                Matcher matcher = LOG_LINE_PATTERN.matcher(trimmedLine);
                if (matcher.matches()) {
                    headerSkipped = true; // Found the first log line, stop skipping header
                } else {
                    // This is a header line, skip it
                    continue;
                }
            }
            // If header has been skipped (or this is the first line that made headerSkipped true),
            // append the current line.
            // Add a newline ONLY if resultBuilder is not empty (i.e., not the very first line being
            // appended).
            if (resultBuilder.length() > 0) {
                resultBuilder.append("\n");
            }
            resultBuilder.append(line);
        }

        return resultBuilder.toString();
    }

    public static void ensureCasClientExecutable(
            Path casClientPath, boolean logMetricsIfNotExecutable) {
        if (!Files.isExecutable(casClientPath)) {
            FileUtil.ensureGroupRWX(casClientPath.toFile());
            CLog.w(
                    "CAS client '%s' is not executable. Set execute bit (successful = %s)",
                    casClientPath, Files.isExecutable(casClientPath));

            if (logMetricsIfNotExecutable) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.CAS_CLIENT_NOT_EXECUTABLE_COUNT, 1);
            }

            CommandResult cr = mRunUtil.runTimedCmd(5000, "ls", "-l", casClientPath.toString());
            CLog.d(
                    "ls -l '%s': Status: %s\nStdout:\n%s\nStderr:\n%s",
                    casClientPath, cr.getStatus(), cr.getStdout(), cr.getStderr());
        }
    }
}
