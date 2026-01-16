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
package com.android.tradefed.result.resultdb;

import com.android.resultdb.proto.ModuleIdentifier;
import com.android.resultdb.proto.StringPair;
import com.android.resultdb.proto.TestIdentifier;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.result.resultdb.utils.AndroidCompatibility;
import com.android.tradefed.result.resultdb.utils.TestIdUtils;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Utility class for ResultDB reporter. */
public final class ResultDBUtil {

    private static final String JUNIT_CLASS_PLACEHOLDER = "no-class-name";

    /** Converts a byte array to a hexadecimal string. */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** A simple class to hold the result of a truncation operation. */
    public static final class TruncationResult {
        private final String truncatedString;
        private final boolean wasTruncated;

        public TruncationResult(String truncatedString, boolean wasTruncated) {
            this.truncatedString = truncatedString;
            this.wasTruncated = wasTruncated;
        }

        public String getTruncatedString() {
            return truncatedString;
        }

        public boolean wasTruncated() {
            return wasTruncated;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TruncationResult that = (TruncationResult) o;
            return wasTruncated == that.wasTruncated
                    && Objects.equals(truncatedString, that.truncatedString);
        }

        @Override
        public int hashCode() {
            return Objects.hash(truncatedString, wasTruncated);
        }
    }

    /**
     * Truncates the string to the given max bytes, avoiding breaking up a multi-byte character.
     *
     * @param input the string to truncate
     * @param maxBytes the maximum number of bytes (in utf-8 encoding) to truncate to
     * @return a {@link TruncationResult} containing the truncated string and a boolean indicating
     *     if truncation happened.
     */
    public static TruncationResult truncateString(String input, int maxBytes) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new TruncationResult(input, false);
        }

        int byteCount = 0;
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < input.length(); ) {
            int codePoint = input.codePointAt(i);
            byte[] codePointBytes =
                    new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);

            if (byteCount + codePointBytes.length <= maxBytes) {
                result.append(Character.toChars(codePoint));
                byteCount += codePointBytes.length;
                i += Character.charCount(codePoint); // Move to the next code point
            } else {
                break;
            }
        }
        return new TruncationResult(result.toString(), true);
    }

    /**
     * Ensure the key is valid for ResultDB.
     *
     * <p>The key must match the regex: ^[a-z][a-z0-9_]*(/[a-z][a-z0-9_]*)*$
     */
    public static String makeValidKey(String key) {
        if (key == null || key.isEmpty()) {
            return "empty_key";
        }

        StringBuilder validKeyBuilder = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                validKeyBuilder.append(c);
            } else if (c >= '0' && c <= '9') {
                validKeyBuilder.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                validKeyBuilder.append((char) (c - 'A' + 'a'));
            } else {
                validKeyBuilder.append('_');
            }
        }
        String validKey = validKeyBuilder.toString();
        if (!validKey.isEmpty() && validKey.charAt(0) >= '0' && validKey.charAt(0) <= '9') {
            validKey = "num_" + validKey;
        }
        return validKey;
    }

    /**
     * Converts a TestDescription to a TestIdentifier.
     *
     * @param moduleName the module name
     * @param moduleVariant the module variant
     * @param testDescription the test description
     * @return the ResultDB test identifier. This test identifier is not guaranteed to be valid
     *     (i.e. ResultDB may reject it, returning a invalid_argument error). This is because
     *     ResultDB has more strict requirements on the test identifier, such as the field length
     *     and character set.
     */
    public static TestIdentifier toTestIdentifier(
            ModuleIdentifier moduleIdentifier, TestDescription testDescription) {

        String fullClassName = testDescription.getClassName();
        int lastDotIndex = fullClassName.lastIndexOf('.');

        String packageName;
        String className;
        if (fullClassName.isEmpty()) {
            packageName = TestIdUtils.JUNIT_PACKAGE_PLACEHOLDER;
            className = JUNIT_CLASS_PLACEHOLDER;
        } else if (lastDotIndex == -1) {
            packageName = TestIdUtils.JUNIT_PACKAGE_PLACEHOLDER;
            className = fullClassName;
        } else {
            packageName = fullClassName.substring(0, lastDotIndex);
            className = fullClassName.substring(lastDotIndex + 1);
        }

        return TestIdentifier.newBuilder()
                .setModuleName(moduleIdentifier.getModuleName())
                .setModuleScheme("junit")
                .setModuleVariant(moduleIdentifier.getModuleVariant())
                .setCoarseName(packageName)
                .setFineName(className)
                .setCaseName(TestIdUtils.escapeCaseName(testDescription.getTestName()))
                .build();
    }

    /**
     * Create work unit resource name from root invocation id and work unit id.
     *
     * @param rootInvocationId The root invocation id.
     * @param workUnitId The work unit id.
     * @return The work unit resource name.
     */
    public static String makeWorkUnitName(String rootInvocationId, String workUnitId) {
        return String.format("rootInvocations/%s/workUnits/%s", rootInvocationId, workUnitId);
    }

    /**
     * Returns a StringPair that can be used to record an error type in the `tags` collection.
     * The value matches AnTS error type values.
     */
    public static StringPair primaryErrorTypeTag(FailureStatus failureStatus) {
        String val;
        switch (failureStatus) {
            case TEST_FAILURE:
                val = "TEST_FAILURE";
                break;
            case TIMED_OUT:
                val = "TIMEOUT";
                break;
            case CANCELLED:
                val = "TEST_CANCELLED";
                break;
            case INFRA_FAILURE:
                val = "INFRA_ERROR";
                break;
            case SYSTEM_UNDER_TEST_CRASHED:
                val = "SYSTEM_UNDER_TEST_CRASHED";
                break;
            case NOT_EXECUTED:
                val = "NOT_EXECUTED";
                break;
            case LOST_SYSTEM_UNDER_TEST:
                val = "LOST_SYSTEM_UNDER_TEST";
                break;
            case DEPENDENCY_ISSUE:
                val = "DEPENDENCY_ISSUE";
                break;
            case CUSTOMER_ISSUE:
                val = "CUSTOMER_ISSUE";
                break;
            default:
                val = "ERROR_TYPE_UNSPECIFIED";
                break;
        }
        return StringPair.newBuilder()
                .setKey(AndroidCompatibility.PRIMARY_ERROR_TYPE_TAG_KEY)
                .setValue(val)
                .build();
    }
}
