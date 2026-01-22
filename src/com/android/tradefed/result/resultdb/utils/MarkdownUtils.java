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

import com.google.common.escape.CharEscaperBuilder;
import com.google.common.escape.Escaper;

/**
 * Utility class for Markdown manipulation.
 *
 * <p>This class is copied from ResultDB codebase for compatibility.
 */
public final class MarkdownUtils {

    // This list of characters is from
    // https://github.com/mattcone/markdown-guide/blob/master/_basic-syntax/escaping-characters.md
    private static final Escaper MARKDOWN_ESCAPER =
            new CharEscaperBuilder()
                    .addEscape('\\', "\\\\")
                    .addEscape('`', "\\`")
                    .addEscape('*', "\\*")
                    .addEscape('_', "\\_")
                    .addEscape('{', "\\{")
                    .addEscape('}', "\\}")
                    .addEscape('[', "\\[")
                    .addEscape(']', "\\]")
                    .addEscape('<', "\\<")
                    .addEscape('>', "\\>")
                    .addEscape('(', "\\(")
                    .addEscape(')', "\\)")
                    .addEscape('#', "\\#")
                    .addEscape('+', "\\+")
                    .addEscape('-', "\\-")
                    .addEscape('.', "\\.")
                    .addEscape('!', "\\!")
                    .addEscape('|', "\\|")
                    .toEscaper();

    /**
     * Escapes markdown characters in the given string.
     *
     * <p>Use this around text literals you don't want to be interpreted as markdown.
     *
     * @param string The string to escape.
     * @return The escaped string.
     * @throws NullPointerException if {@code string} is null
     */
    public static String escape(String string) {
        return MARKDOWN_ESCAPER.escape(string);
    }

    private MarkdownUtils() {}
}
