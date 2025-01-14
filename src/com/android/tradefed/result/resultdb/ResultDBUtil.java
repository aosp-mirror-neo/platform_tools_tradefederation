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

import com.android.resultdb.proto.TestIdentifier;
import com.android.resultdb.proto.Variant;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.skipped.SkipReason;

import com.google.common.base.Strings;

import java.nio.charset.StandardCharsets;

/** Utility class for ResultDB reporter. */
public final class ResultDBUtil {

    /** Converts a byte array to a hexadecimal string. */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Truncates the string to the given max bytes, avoiding breaking up a multi-byte character.
     *
     * @param input the string to truncate
     * @param maxBytes the maximum number of bytes (in utf-8 encoding) to truncate to
     * @return the truncated string
     */
    public static String truncateString(String input, int maxBytes) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return input;
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
        return result.toString();
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
     * Converts a test skip reason to a summary HTML string. ResultDB does not yet have a skip
     * reason field, we put them in the summary HTML field and test artifact for now. The return
     * string can be longer than the summary HTML field limit, caller should truncate it if needed.
     */
    public static String testSkipReasonToSummaryHtml(SkipReason reason, String skipReasonType) {
        StringBuilder summaryHtml = new StringBuilder();
        if (!Strings.isNullOrEmpty(reason.getBugId())) {
            summaryHtml.append("bug_id: ").append(reason.getBugId()).append("<br>");
        }
        if (!Strings.isNullOrEmpty(reason.getTrigger())) {
            summaryHtml.append("trigger: ").append(reason.getTrigger()).append("<br>");
        }
        if (!Strings.isNullOrEmpty(skipReasonType)) {
            summaryHtml.append("skip_reason_type: ").append(skipReasonType).append("<br>");
        }
        if (!Strings.isNullOrEmpty(reason.getReason())) {
            summaryHtml.append("reason: ").append(reason.getReason()).append("<br>");
        }
        return summaryHtml.toString();
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
            String moduleName, Variant moduleVariant, TestDescription testDescription) {

        String fullClassName = testDescription.getClassName();
        int lastDotIndex = fullClassName.lastIndexOf('.');

        String packageName;
        String className;
        if (fullClassName.isEmpty()) {
            packageName = "empty-package";
            className = "empty-class";
        } else if (lastDotIndex == -1) {
            packageName = "empty-package";
            className = fullClassName;
        } else {
            packageName = fullClassName.substring(0, lastDotIndex);
            className = fullClassName.substring(lastDotIndex + 1);
        }
        if (Strings.isNullOrEmpty(moduleName)) {
            moduleName = "empty-module";
        }
        return TestIdentifier.newBuilder()
                .setModuleName(moduleName)
                .setModuleScheme("junit")
                .setModuleVariant(moduleVariant)
                .setCoarseName(packageName)
                .setFineName(className)
                .setCaseName(testDescription.getTestName())
                .build();
    }
}
