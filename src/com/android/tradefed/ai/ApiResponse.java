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

import com.android.tradefed.ai.ContentRequest.Content;

import java.util.List;

/** Class to parse the return value of the API. */
public class ApiResponse {
    public List<Candidate> candidates;
    public UsageMetadata usageMetadata;

    public class Candidate {
        public Content content;

        @Override
        public String toString() {
            return "Candidate [content=" + content + "]";
        }
    }

    public class UsageMetadata {
        public long promptTokenCount;
        public long candidatesTokenCount;
        public long totalTokenCount;

        @Override
        public String toString() {
            return "UsageMetadata [promptTokenCount="
                    + promptTokenCount
                    + ", candidatesTokenCount="
                    + candidatesTokenCount
                    + ", totalTokenCount="
                    + totalTokenCount
                    + "]";
        }
    }

    @Override
    public String toString() {
        return "ApiResponse [candidates=" + candidates + ", usageMetadata=" + usageMetadata + "]";
    }
}
