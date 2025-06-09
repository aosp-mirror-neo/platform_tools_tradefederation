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
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;

import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Provide a central client to interact with GenAi API and make prompt requests. This requires a
 * valid API_KEY to be set.
 */
public class CurlGenAiClient {

    private static final String API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    /** List of model available to be used. */
    public enum Model {
        GEMINI_2_0_FLASH("gemini-2.0-flash"),
        GEMINI_2_5_FLASH("gemini-2.5-flash-preview-05-20");

        private final String modelName;

        Model(String modelName) {
            this.modelName = modelName;
        }
    }

    /**
     * Request a prompt to execute.
     *
     * @param apiKey The API_KEY to be used for the query.
     * @param logPromptOnly returns the prompt only without executing it.
     * @param prompt The prompt being used.
     */
    public PromptResponse runPrompt(String apiKey, boolean logPromptOnly, String prompt) {
        return runPrompt(
                Model.GEMINI_2_5_FLASH,
                apiKey,
                logPromptOnly,
                prompt,
                new ArrayList<ContentRequest.InlineData>());
    }

    /**
     * Request a prompt to execute.
     *
     * @param apiKey The API_KEY to be used for the query.
     * @param logPromptOnly returns the prompt only without executing it.
     * @param prompt The prompt being used.
     * @param data The inline file data to be associated with the prompt.
     */
    public PromptResponse runPrompt(
            String apiKey, boolean logPromptOnly, String prompt, List<InlineData> data) {
        return runPrompt(Model.GEMINI_2_5_FLASH, apiKey, logPromptOnly, prompt, data);
    }

    /**
     * Request a prompt to execute.
     *
     * @param model The model to be used.
     * @param apiKey The API_KEY to be used for the query.
     * @param logPromptOnly returns the prompt only without executing it.
     * @param prompt The prompt being used.
     * @param data The inline file data to be associated with the prompt.
     */
    public PromptResponse runPrompt(
            Model model,
            String apiKey,
            boolean logPromptOnly,
            String prompt,
            List<InlineData> data) {
        try {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.PROMPT_REQUEST_COUNT, 1);
            File request = FileUtil.createTempFile("curl-request", ".json");
            FileUtil.writeToFile(createJsonQuery(prompt, data), request);
            if (logPromptOnly) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.LOG_ONLY_PROMPT_REQUEST_COUNT, 1);
                return new PromptResponse(request, null);
            }
            if (apiKey == null) {
                CLog.d("No API key specified.");
                return new PromptResponse(request, null);
            }
            String query = buildQuery(model, apiKey);
            File response = FileUtil.createTempFile("curl-response", ".json");
            CommandResult results =
                    RunUtil.getDefault()
                            .runTimedCmd(
                                    0,
                                    new String[] {
                                        "curl",
                                        query,
                                        "-H",
                                        "Content-Type: application/json",
                                        "-X",
                                        "POST",
                                        "-d",
                                        "@" + request.getAbsolutePath(),
                                        "-o",
                                        response.getAbsolutePath()
                                    });
            // TODO: Throw errors in case of errors
            if (CommandStatus.SUCCESS.equals(results.getStatus())) {
                String outputJson = FileUtil.readStringFromFile(response);
                CLog.d("output: %s", outputJson);
                try {
                    JSONObject jsonObject = new JSONObject(outputJson);
                    if (jsonObject.has("error")) {
                        CLog.e("error with the query");
                        return null;
                    } else {
                        Gson gson = new Gson();
                        ApiResponse rep = gson.fromJson(outputJson, ApiResponse.class);
                        CLog.d("%s", rep);
                        return new PromptResponse(request, response);
                    }
                } catch (JSONException je) {
                    CLog.e(je);
                }
            } else {
                CLog.e("Issue when running the query:");
                CLog.e("stdout: %s", results.getStdout());
                CLog.e("stderr: %s", results.getStderr());
            }
        } catch (Exception e) {
            CLog.e(e);
        }
        return null;
    }

    private String createJsonQuery(String prompt, List<InlineData> inlineData) {
        ContentRequest req = new ContentRequest(prompt, inlineData);
        Gson gson = new Gson();
        String json = gson.toJson(req);
        return json;
    }

    private String buildQuery(Model model, String apiKey) {
        String url = API_URL + model.modelName + ":generateContent?key=" + apiKey;
        return url;
    }
}
