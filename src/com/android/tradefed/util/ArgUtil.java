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

package com.android.tradefed.util;

import com.android.tradefed.config.OptionDef;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.result.error.InfraErrorIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A util class to parse args formatted as expected into {@link OptionDef}. */
public final class ArgUtil {

    private ArgUtil() {
        throw new AssertionError("ArgUtil is a utility class and should not be instantiated.");
    }

    /**
     * Parse a list of args formatted as expected into {@link OptionDef} to be injected to module
     * configurations.
     *
     * <p>Format: <module name / module id / class runner>:<option name>:[<arg-key>:=]<arg-value>
     */
    public static void parseArgs(List<String> args, Map<String, List<OptionDef>> moduleOptions) {
        for (String arg : args) {
            int moduleSep = arg.indexOf(":");
            if (moduleSep == -1) {
                throw new HarnessRuntimeException(
                        "Expected delimiter ':' for module or class.",
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            String moduleName = arg.substring(0, moduleSep);
            String remainder = arg.substring(moduleSep + 1);
            List<OptionDef> listOption = moduleOptions.get(moduleName);
            if (listOption == null) {
                listOption = new ArrayList<>();
                moduleOptions.put(moduleName, listOption);
            }
            int optionNameSep = remainder.indexOf(":");
            if (optionNameSep == -1) {
                throw new HarnessRuntimeException(
                        "Expected delimiter ':' between option name and values.",
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            String optionName = remainder.substring(0, optionNameSep);
            Pattern pattern = Pattern.compile("\\{(.*)\\}(.*)");
            Matcher match = pattern.matcher(optionName);
            if (match.find()) {
                String alias = match.group(1);
                String name = match.group(2);
                optionName = alias + ":" + name;
            }
            String optionValueString = remainder.substring(optionNameSep + 1);
            // TODO: See if QuotationTokenizer can be improved for multi-character delimiter.
            // or change the delimiter to a single char.
            String[] tokens = optionValueString.split(":=", 2);
            OptionDef option = null;
            if (tokens.length == 1) {
                option = new OptionDef(optionName, tokens[0], moduleName);
            } else if (tokens.length == 2) {
                option = new OptionDef(optionName, tokens[0], tokens[1], moduleName);
            }
            listOption.add(option);
        }
    }
}
