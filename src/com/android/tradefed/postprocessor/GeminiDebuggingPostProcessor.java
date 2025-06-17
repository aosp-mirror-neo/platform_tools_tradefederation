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
package com.android.tradefed.postprocessor;

import com.android.tradefed.ai.ApiResponse;
import com.android.tradefed.ai.LogPreprocessor;
import com.android.tradefed.ai.PromptResponse;
import com.android.tradefed.ai.PromptUtility;
import com.android.tradefed.ai.PromptUtility.PromptTemplate;
import com.android.tradefed.config.Option;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric.Builder;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.FileUtil;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/** Post processor creating prompt queries for debugging errors. */
public class GeminiDebuggingPostProcessor extends BasePostProcessor {

    @Option(
            name = "log-prompt-only",
            description = "Whether to only log the prompt without executing it.")
    private boolean mLogOnlyPrompt = true;

    @Override
    public Map<String, Builder> processRunMetricsAndLogs(
            HashMap<String, Metric> rawMetrics, Map<String, LogFile> runLogs) {
        // Ignore for now
        return new HashMap<String, Builder>();
    }

    @Override
    public Map<String, Builder> processTestMetricsAndLogs(
            TestDescription testDescription,
            HashMap<String, Metric> testMetrics,
            Map<String, LogFile> testLogs) {
        String stackTrace = getStackTrace();
        // Only process failures
        if (stackTrace == null) {
            return new HashMap<String, Builder>();
        }
        PromptTemplate templatePrompt = getPromptTemplateForTestType();
        if (PromptTemplate.UNSUPPORTED.equals(templatePrompt)) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.PROMPT_UNSUPPORTED_TEST_TYPE_COUNT, 1);
            return new HashMap<String, Builder>();
        }
        processTestCaseFailure(testDescription, stackTrace, templatePrompt, testLogs);
        return new HashMap<String, Builder>();
    }

    private void processTestCaseFailure(
            TestDescription testDescription,
            String stackTrace,
            PromptTemplate templatePrompt,
            Map<String, LogFile> testLogs) {
        PromptResponse promptResponse = null;
        boolean logOnly = mLogOnlyPrompt;
        if (!getConfiguration().getCommandOptions().isGeminiLogOnlyMode()) {
            logOnly = false;
        }
        try {
            // Extract logs
            Map<String, File> logMap = mapLogsToPlaceholder(testLogs);
            promptResponse =
                    PromptUtility.runPromptTemplate(
                            getConfiguration().getCommandOptions().getGeminiApiKey(),
                            templatePrompt,
                            logOnly,
                            testDescription,
                            stackTrace,
                            logMap);
            if (promptResponse == null) {
                CLog.e("No prompt response.");
                return;
            }
            try (InputStreamSource geminiRequest =
                    new FileInputStreamSource(promptResponse.getRequest())) {
                testLog("gemini-debugging-request", LogDataType.GEMINI_PROMPT, geminiRequest);
            }
            if (promptResponse.getResponse() != null) {
                // Extract response
                String outputJson = FileUtil.readStringFromFile(promptResponse.getResponse());
                Gson gson = new Gson();
                ApiResponse rep = gson.fromJson(outputJson, ApiResponse.class);
                try (InputStreamSource geminiSource =
                        new ByteArrayInputStreamSource(rep.toString().getBytes())) {
                    testLog("gemini-debugging-output", LogDataType.GEMINI_RESPONSE, geminiSource);
                }
            }
        } catch (IOException | RuntimeException e) {
            CLog.e(e);
        } finally {
            if (promptResponse != null) {
                promptResponse.clean();
            }
        }
    }

    private Map<String, File> mapLogsToPlaceholder(Map<String, LogFile> testLogs) {
        Map<String, File> logMap = new HashMap<>();
        for (Entry<String, LogFile> log : testLogs.entrySet()) {
            LogFile logFileValue = log.getValue();
            if (logFileValue.getType().equals(LogDataType.LOGCAT)) {
                logMap.put(
                        "LOGCAT",
                        LogPreprocessor.preprocessLogcat(new File(logFileValue.getPath())));
            }
            if (logFileValue.getType().equals(LogDataType.HOST_LOG)) {
                logMap.put("HOST_LOG", new File(logFileValue.getPath()));
            }
        }
        return logMap;
    }

    private PromptUtility.PromptTemplate getPromptTemplateForTestType() {
        switch (CurrentInvocation.getCurrentRunner()) {
            case "com.android.tradefed.testtype.AndroidJUnitTest":
                return PromptUtility.PromptTemplate.ON_DEVICE;
            case "com.android.tradefed.testtype.JarHostTest":
            case "com.android.tradefed.testtype.HostTest":
                return PromptUtility.PromptTemplate.HOST_DRIVEN;
            default:
                return PromptUtility.PromptTemplate.UNSUPPORTED;
        }
    }
}
