/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tradefed.result.resultdb.utils;

/**
 * A utility class for sanitizing test IDs for ResultDB.
 *
 * <p>This class is copied from ResultDB codebase for compatibility.
 */
public final class TestIdUtils {

    // Should be used for coarse_name if the JUnit test is not in a package.
    public static final String JUNIT_PACKAGE_PLACEHOLDER = "no-package-name";
    // Should be used for coarse_name if the pyunit test is not in a package.
    public static final String PYUNIT_PACKAGE_PLACEHOLDER = "__main__";

    // SPECIAL_ESCAPING_SUFFIX is appended to case names which require special escaping (e.g.
    // contain
    // non-printable characters or certain disallowed leading characters).
    private static final String SPECIAL_ESCAPING_SUFFIX = "⁑";

    private TestIdUtils() {}

    /**
     * Escapes a case name into a valid ResultDB case name. This escaping does NOT support case name
     * with ResultDB's extended depth hierarchy feature, which treats colon as a separator for case
     * name parts.
     *
     * <p>The escaping is done as follows:
     *
     * <ol>
     *   <li>If the case name contains non-printable characters, a disallowed leading character ([
     *       !"#$%&'()*+,]), or ends with "⁑", it undergoes special escaping:
     *       <ol>
     *         <li>Backslashes (`\`) are escaped to `\\`.
     *         <li>A leading character is escaped with a backslash if it is one of [ !"#$%&'()*+,]
     *             (U+0020 to U+002C).
     *         <li>Non-printable characters are converted to their literal unicode escape sequence
     *             (\\uXXXX or \\UXXXXXXXX).
     *         <li>A special suffix "⁑" is appended.
     *       </ol>
     *   <li>On the result of step 1 (or the original string if step 1 was skipped), the following
     *       replacements are made:
     *       <ol>
     *         <li>Backslashes (`\`) are escaped to `\\`.
     *         <li>Colons (`:`) are escaped to `\:`.
     *       </ol>
     * </ol>
     *
     * <p>This means backslashes can be double-escaped.
     *
     * <p>DON'T USE this function if you are using ResultDB's extended depth hierarchy feature.
     *
     * <p>This method is the Java equivalent of the {@code EscapeCaseName} function in {@code
     * chrome/engprod/resultdb/go/utils/test_id.go}. The round-trip capability with the Go {@code
     * UnescapeCaseName} function is tested in the Go codebase.
     */
    public static String escapeCaseName(String caseName) {
        // Stage 1: apply special escape if needed.
        String escapedCaseName = caseName;
        if (needsSpecialEscaping(caseName)) {
            escapedCaseName = specialEscape(caseName);
            escapedCaseName = escapedCaseName + SPECIAL_ESCAPING_SUFFIX;
        }

        // Stage 2: escape backslash and colon.
        escapedCaseName = escapedCaseName.replace("\\", "\\\\").replace(":", "\\:");
        return escapedCaseName;
    }

    // specialEscape escapes backlash, disallowed leading character and non-printable characters
    // with
    // backslash.
    private static String specialEscape(String caseName) {
        String escapedCaseName = caseName.replace("\\", "\\\\");
        // Escape the leading characters if it is one of [ !"#$%&'()*+,] (i.e. U+0020 to U+002C).
        if (!escapedCaseName.isEmpty() && !escapedCaseName.equals("*fixture")) {
            char firstChar = escapedCaseName.charAt(0);
            if (firstChar >= 0x20 && firstChar <= 0x2c) {
                escapedCaseName = "\\" + escapedCaseName;
            }
        }
        // Escape all non-printable characters.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < escapedCaseName.length(); ) {
            int codePoint = escapedCaseName.codePointAt(i);
            if (isPrintable(codePoint) && codePoint != 0xFFFD) {
                sb.appendCodePoint(codePoint);
            } else {
                if (codePoint <= 0xFFFF) {
                    sb.append(String.format("\\u%04X", codePoint));
                } else {
                    sb.append(String.format("\\U%08X", codePoint));
                }
            }
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    // needsSpecialEscaping returns true if the case name needs special escaping.
    private static boolean needsSpecialEscaping(String caseName) {
        // Check if leading characters is one of [ !"#$%&'()*+,] (i.e. U+0020 to U+002C).
        if (!caseName.isEmpty() && !caseName.equals("*fixture")) {
            char firstChar = caseName.charAt(0);
            if (firstChar >= 0x20 && firstChar <= 0x2c) {
                return true;
            }
        }
        // Check if the case name contains non-printable characters.
        for (int i = 0; i < caseName.length(); ) {
            int codePoint = caseName.codePointAt(i);
            if (!isPrintable(codePoint) || codePoint == 0xFFFD) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        // Check if the case name ends with the special suffix.
        return caseName.endsWith(SPECIAL_ESCAPING_SUFFIX);
    }

    /**
     * Checks if a Unicode code point is printable according to Go's {@code unicode.IsPrint}
     * definition.
     *
     * <p>This custom implementation is necessary because there is no direct equivalent in Java's
     * standard library. Go's definition of "printable" includes the Unicode categories for letters
     * (L), marks (M), numbers (N), punctuation (P), symbols (S), and the ASCII space character.
     * This method replicates that logic.
     *
     * @see <a href="https://pkg.go.dev/unicode#IsPrint">Go unicode.IsPrint documentation</a>
     */
    private static boolean isPrintable(int codePoint) {
        // Space is a printable character.
        if (codePoint == ' ') {
            return true;
        }
        int type = Character.getType(codePoint);
        switch (type) {
            // L (Letter).
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            // M (Mark).
            case Character.COMBINING_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.NON_SPACING_MARK:
            // N (Number).
            case Character.DECIMAL_DIGIT_NUMBER:
            case Character.LETTER_NUMBER:
            case Character.OTHER_NUMBER:
            // P (Punctuation).
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.START_PUNCTUATION:
            // S (Symbol).
            case Character.CURRENCY_SYMBOL:
            case Character.MATH_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }
}
