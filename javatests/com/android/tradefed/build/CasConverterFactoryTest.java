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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CasConverterFactoryTest {

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();
    private Path tempTestDir; // Will store the Path to the temporary folder

    @Mock private IRunUtil mockRunUtil;

    // We will spy on CasConverterFactory to verify calls to its instance methods
    private CasConverterFactory spyCasConverterFactory;

    @Before
    public void setUp() throws IOException {
        tempTestDir = tempFolder.getRoot().toPath();
    }

    /** Helper to create a fake executable script in the parent directory. */
    private Path createFakeConverterScript(Path parentDir, String scriptName, int exitCode)
            throws IOException {
        Path scriptPath = parentDir.resolve(scriptName);
        String scriptContent =
                "#!/bin/bash\n"
                        + "echo \"This is a fake converter script: "
                        + scriptName
                        + "\"\n"
                        + "exit "
                        + exitCode;

        Files.writeString(scriptPath, scriptContent);

        // Make executable for POSIX file systems (Linux)
        Set<PosixFilePermission> perms =
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(scriptPath, perms);

        return scriptPath;
    }

    // Constructor Tests

    @Test
    public void testConstructor_tfJarDirNotSet() {

        try {
            String nullJarDir = null;
            new CasConverterFactory(mockRunUtil, nullJarDir);
            fail("Expected IllegalStateException but none was thrown.");
        } catch (IllegalStateException e) {
            assertEquals("TF_JAR_DIR system property is not set.", e.getMessage());
            // Verify the metrics logging is possible but requires more dependency injection.
        }
    }

    @Test
    public void testConstructor_casConverterNotFound() throws IOException {
        Path fakeJarDir = tempTestDir.resolve("fake_jars_no_converter");
        Files.createDirectories(fakeJarDir);

        try {
            new CasConverterFactory(mockRunUtil, fakeJarDir.toString());
            fail("Expected IllegalStateException but none was thrown.");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Could not find 'casconverter'"));
            // Verify the metrics logging is possible but requires more dependency injection.
        }
    }

    @Test
    public void testConstructor_success() throws IOException {
        Path fakeJarDir = tempTestDir.resolve("fake_jars_with_converter");
        Files.createDirectories(fakeJarDir);
        Path fakeCasConverter = createFakeConverterScript(fakeJarDir, "casconverter", 0);

        CasConverterFactory factoryInstance =
                new CasConverterFactory(mockRunUtil, fakeJarDir.toString());

        // No throws expected.
    }

    // Convert Method Tests

    @Test
    public void testConvert_success() throws IOException {
        Path mockChunkedDirectory = tempTestDir.resolve("chunked_source");
        Files.createDirectories(mockChunkedDirectory);

        // Create a fake `casconverter` executable that returns success (exit code 0)
        Path fakeCasConverter = createFakeConverterScript(tempTestDir, "success_converter", 0);

        // Use the test-only constructor to inject mockRunUtil and a valid converter path
        CasConverterFactory factoryInstance =
                new CasConverterFactory(fakeCasConverter, mockRunUtil);
        spyCasConverterFactory = spy(factoryInstance); // Spy on the instance
        doNothing()
                .when(spyCasConverterFactory)
                .logCasClientVersion(any(Path.class), any(Consumer.class)); // Stub internal logging

        // Mock the CommandResult for a successful conversion
        CommandResult successResult = new CommandResult();
        successResult.setStatus(CommandStatus.SUCCESS);
        successResult.setStdout("Conversion successful");
        successResult.setStderr("");

        when(mockRunUtil.runTimedCmd(
                        eq(CasConverterFactory.CONVERSION_TIMEOUT_IN_MS),
                        eq(fakeCasConverter.toString()),
                        eq("--src-path"),
                        eq(mockChunkedDirectory.toAbsolutePath().toString())))
                .thenReturn(successResult);

        // Perform the conversion - no exception expected
        spyCasConverterFactory.convert(mockChunkedDirectory);

        // Verify runTimedCmd was called
        verify(mockRunUtil)
                .runTimedCmd(
                        eq(CasConverterFactory.CONVERSION_TIMEOUT_IN_MS),
                        eq(fakeCasConverter.toString()),
                        eq("--src-path"),
                        eq(mockChunkedDirectory.toAbsolutePath().toString()));

        // Verify metrics call
        verify(spyCasConverterFactory)
                .addInvocationMetrics(InvocationMetricKey.CAS_CONVERTER_SUCCESS_COUNT, 1);
        verify(spyCasConverterFactory, never())
                .addInvocationMetrics(
                        eq(InvocationMetricKey.CAS_CONVERTER_FAILURE_COUNT), anyInt());
    }

    @Test
    public void testConvert_failure() throws IOException {
        Path mockChunkedDirectory = tempTestDir.resolve("chunked_source");
        Files.createDirectories(mockChunkedDirectory);

        // Create a fake `casconverter` executable that returns failure (exit code 1)
        Path fakeCasConverter = createFakeConverterScript(tempTestDir, "failure_converter", 1);

        // Use the test-only constructor to inject mockRunUtil and a valid converter path
        CasConverterFactory factoryInstance =
                new CasConverterFactory(fakeCasConverter, mockRunUtil);
        spyCasConverterFactory = spy(factoryInstance); // Spy on the instance
        doNothing()
                .when(spyCasConverterFactory)
                .logCasClientVersion(any(Path.class), any(Consumer.class)); // Stub internal logging

        // Mock the CommandResult for a failed conversion
        CommandResult failureResult = new CommandResult();
        failureResult.setStatus(CommandStatus.FAILED);
        failureResult.setStdout("Conversion output");
        failureResult.setStderr("Conversion error: Something went wrong");

        when(mockRunUtil.runTimedCmd(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString())) // Use general matchers for brevity
                .thenReturn(failureResult);

        // Expect an IOException on failure
        try {
            spyCasConverterFactory.convert(mockChunkedDirectory);
            fail("Expected IOException but none was thrown.");
        } catch (IOException thrown) {
            assertTrue(thrown.getMessage().contains("Failed to convert chunked directory"));
            assertTrue(thrown.getMessage().contains("Conversion error: Something went wrong"));
        }

        // Verify runTimedCmd was called
        verify(mockRunUtil)
                .runTimedCmd(
                        eq(CasConverterFactory.CONVERSION_TIMEOUT_IN_MS),
                        eq(fakeCasConverter.toString()),
                        eq("--src-path"),
                        eq(mockChunkedDirectory.toAbsolutePath().toString()));

        // Verify metrics call
        verify(spyCasConverterFactory)
                .addInvocationMetrics(InvocationMetricKey.CAS_CONVERTER_FAILURE_COUNT, 1);
        verify(spyCasConverterFactory, never())
                .addInvocationMetrics(
                        eq(InvocationMetricKey.CAS_CONVERTER_SUCCESS_COUNT), anyInt());
    }
}
