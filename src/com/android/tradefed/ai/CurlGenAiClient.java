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
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.RunUtil;

import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;

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
        GEMINI_2_0_FLASH("gemini-2.0-flash");

        private final String modelName;

        Model(String modelName) {
            this.modelName = modelName;
        }
    }

    /**
     * Request a prompt to execute.
     *
     * @param apiKey The API_KEY to be used for the query.
     * @param prompt The prompt being used.
     */
    public void runPrompt(String apiKey, String prompt) {
        runPrompt(
                Model.GEMINI_2_0_FLASH, apiKey, prompt, new ArrayList<ContentRequest.InlineData>());
    }

    /**
     * Request a prompt to execute.
     *
     * @param apiKey The API_KEY to be used for the query.
     * @param prompt The prompt being used.
     * @param data The inline file data to be associated with the prompt.
     */
    public void runPrompt(String apiKey, String prompt, List<InlineData> data) {
        runPrompt(Model.GEMINI_2_0_FLASH, apiKey, prompt, data);
    }

    /**
     * Request a prompt to execute.
     *
     * @param model The model to be used.
     * @param apiKey The API_KEY to be used for the query.
     * @param prompt The prompt being used.
     * @param data The inline file data to be associated with the prompt.
     */
    public void runPrompt(Model model, String apiKey, String prompt, List<InlineData> data) {
        String query = buildQuery(model, apiKey);

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
                                    createJsonQuery(prompt, data)
                                });
        if (CommandStatus.SUCCESS.equals(results.getStatus())) {
            String outputJson = results.getStdout();
            CLog.d("output: %s", outputJson);
            try {
                JSONObject jsonObject = new JSONObject(outputJson);
                if (jsonObject.has("error")) {
                    CLog.e("error with the query");
                } else {
                    Gson gson = new Gson();
                    ApiResponse rep = gson.fromJson(outputJson, ApiResponse.class);
                    CLog.d("%s", rep);
                }
            } catch (JSONException je) {
                CLog.e(je);
            }
        } else {
            CLog.e("Issue when running the query:");
            CLog.e("stdout: %s", results.getStdout());
            CLog.e("stderr: %s", results.getStderr());
        }
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
