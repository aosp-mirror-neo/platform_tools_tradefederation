/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.google.common.base.Strings;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility class for escaping strings for common string manipulation. */
public class StringUtil {

    // Regex to match all common ANSI escape sequences
    // \u001B is the ESC character, followed by [ then any characters
    // until an ANSI-terminating character (m, K, H, etc.) is found.
    private static final String ANSI_ESCAPE_REGEX = "\\u001B\\[[\\d;]*[mKHFJ]";

    /**
     * Expand all variables in a given string with their values in the map.
     *
     * <pre>{@code
     * Map<String, String> valueMap = new HashMap<>() {
     *   put("FOO", "trade");
     *   put("BAR", "federation");
     * };
     * String str = StringUtil.expand("${FOO}.${BAR}", valueMap);
     * assert str.equals("trade.federation");
     * }</pre>
     *
     * @param str the source {@link String} to expand
     * @return the map with the variable names and values
     */
    public static String expand(String str, Map<String, String> valueMap) {
        final StringBuffer sb = new StringBuffer();
        final Pattern p = Pattern.compile("\\$\\{([^\\{\\}]+)\\}");
        final Matcher m = p.matcher(str);
        while (m.find()) {
            final String key = m.group(1);
            final String value = valueMap.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Remove all common ANSI escape sequences
     *
     * @param coloredText string that might contain ANSI code for colored text
     * @return a string with all ANSI codes removed
     */
    public static String removeAnsiCodes(String coloredText) {
        if (Strings.isNullOrEmpty(coloredText)) {
            return coloredText;
        }
        return coloredText.replaceAll(ANSI_ESCAPE_REGEX, "");
    }
}
