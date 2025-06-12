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

import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ResourceUtil;

import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/** A utility to craft prompts from context. */
public class PromptUtility {

    /** Reference to prompt template stored in resources */
    public enum PromptTemplate {
        UNSUPPORTED("", Collections.emptySet()),

        ON_DEVICE(
                "/prompts/on_device.txt",
                ImmutableSet.of("LOGCAT")), // TEST_NAME, STACK_TRACE, LOGCAT
        HOST_DRIVEN(
                "/prompts/host_driven.txt",
                ImmutableSet.of("LOGCAT", "HOST_LOG")); // TEST_NAME, STACK_TRACE, LOGCAT, HOST_LOG

        private final String templateResource;
        private final Set<String> requiredLogs;

        PromptTemplate(String resource, Set<String> requiredLogs) {
            this.templateResource = resource;
            this.requiredLogs = requiredLogs;
        }

        public String getTemplateResource() {
            return templateResource;
        }

        public Set<String> requiredLogs() {
            return requiredLogs;
        }
    }

    public static PromptResponse runPromptTemplate(
            String apiKey,
            PromptTemplate template,
            boolean logPromptOnly,
            TestDescription description,
            String stacktrace,
            Map<String, File> contextFiles) {
        if (PromptTemplate.UNSUPPORTED.equals(template)) {
            return null;
        }
        File templatePrompt = null;
        try {
            templatePrompt = FileUtil.createTempFile("prompt", ".txt");
            ResourceUtil.extractResourceToFile(template.getTemplateResource(), templatePrompt);
            return runPromptTemplate(
                    apiKey,
                    templatePrompt,
                    template.requiredLogs(),
                    logPromptOnly,
                    description,
                    stacktrace,
                    contextFiles);
        } catch (IOException e) {
            CLog.e(e);
        } finally {
            FileUtil.deleteFile(templatePrompt);
        }
        return null;
    }

    /**
     * Craft a prompt based on a template
     *
     * @param apiKey
     * @param templatePrompt
     * @param requiredLogs
     * @param description
     * @param stacktrace
     * @param contextFiles
     */
    public static PromptResponse runPromptTemplate(
            String apiKey,
            File templatePrompt,
            Set<String> requiredLogs,
            boolean logPromptOnly,
            TestDescription description,
            String stacktrace,
            Map<String, File> contextFiles) {
        Set<String> requiredMet = new HashSet<>(requiredLogs);
        requiredMet.removeAll(contextFiles.keySet());
        if (!requiredMet.isEmpty()) {
            // Missing some logs
            CLog.w("We do not have a logs for the following placeholder: %s", requiredMet);
            return null;
        }
        try (CloseableTraceScope ignored = new CloseableTraceScope("run_prompt")) {
            // TODO: Do replacement without having to load everything in memory.
            String templatePromptString = FileUtil.readStringFromFile(templatePrompt);
            // Replace template
            templatePromptString =
                    templatePromptString.replace("{TEST_NAME}", description.toString());
            templatePromptString = templatePromptString.replace("{STACK_TRACE}", stacktrace);
            // Replace logs placeholders by their content
            for (Entry<String, File> availableLog : contextFiles.entrySet()) {
                templatePromptString =
                        templatePromptString.replace(
                                String.format("{%s}", availableLog.getKey()),
                                FileUtil.readStringFromFile(availableLog.getValue()));
            }
            // Execute the prompt
            CurlGenAiClient client = new CurlGenAiClient();
            PromptResponse response = client.runPrompt(apiKey, logPromptOnly, templatePromptString);
            if (response != null) {
                CLog.d("Prompt response: %s", response.getResponse());
            }
            return response;
        } catch (IOException e) {
            CLog.e(e);
        }
        return null;
    }
}
