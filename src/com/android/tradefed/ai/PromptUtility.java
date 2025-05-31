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

import com.android.tradefed.ai.ContentRequest.InlineData;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ResourceUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A utility to craft prompts from context. */
public class PromptUtility {

    /** Reference to prompt template stored in resources */
    public enum PromptTemplate {
        ON_DEVICE("/prompts/on_device.txt"), // TEST_NAME, STACK_TRACE, LOGCAT
        HOST_DRIVEN("/prompts/host_driven.txt");

        private final String templateResource;

        PromptTemplate(String resource) {
            this.templateResource = resource;
        }

        public String getTemplateResource() {
            return templateResource;
        }
    }

    /**
     * Craft a prompt based on a template
     *
     * @param apiKey
     * @param template
     * @param description
     * @param stacktrace
     * @param contextFiles
     */
    public static PromptResponse runPromptTemplate(
            String apiKey,
            PromptTemplate template,
            TestDescription description,
            String stacktrace,
            Map<String, File> contextFiles) {
        CurlGenAiClient client = new CurlGenAiClient();
        File templatePrompt = null;
        try {
            // Load template
            templatePrompt = FileUtil.createTempFile("prompt", ".txt");
            ResourceUtil.extractResourceToFile(template.getTemplateResource(), templatePrompt);
            String templatePromptString = FileUtil.readStringFromFile(templatePrompt);
            // Replace template
            // TODO: Avoid doing this in-memory
            templatePromptString =
                    templatePromptString.replace("{TEST_NAME}", description.toString());
            templatePromptString = templatePromptString.replace("{STACK_TRACE}", stacktrace);
            List<InlineData> data = new ArrayList<ContentRequest.InlineData>();
            if (contextFiles.containsKey("LOGCAT")) {
                File logcat = contextFiles.get("LOGCAT");
                // InlineData id = new InlineData("text/plain", encodeFileToBase64(logcat));
                // data.add(id);
                templatePromptString =
                        templatePromptString.replace(
                                "{LOGCAT}", FileUtil.readStringFromFile(logcat));
            }
            // Execute
            PromptResponse response = client.runPrompt(apiKey, templatePromptString, data);
            CLog.d("Prompt response: %s", response.getResponse());
            return response;
        } catch (IOException e) {
            CLog.e(e);
        } finally {
            FileUtil.deleteFile(templatePrompt);
        }
        return null;
    }
}
