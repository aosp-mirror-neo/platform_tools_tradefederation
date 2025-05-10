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

import java.util.ArrayList;
import java.util.List;

/** Class to build the request to the API. */
public class ContentRequest {
    private List<Content> contents;

    public ContentRequest(String textPrompt) {
        this(textPrompt, new ArrayList<>());
    }

    public ContentRequest(String textPrompt, List<InlineData> inlineData) {
        contents = new ArrayList<ContentRequest.Content>();
        List<Part> parts = new ArrayList<ContentRequest.Part>();
        parts.add(new Part(textPrompt));
        for (InlineData data : inlineData) {
            parts.add(new Part(data));
        }
        Content content = new Content(parts);
        contents.add(content);
    }

    public class Content {
        private List<Part> parts;

        public Content(Part part) {
            parts = new ArrayList<ContentRequest.Part>();
            parts.add(part);
        }

        public Content(List<Part> parts) {
            this.parts = new ArrayList<ContentRequest.Part>();
            this.parts.addAll(parts);
        }

        @Override
        public String toString() {
            return "Content [parts=" + parts + "]";
        }
    }

    public class Part {
        private String text;
        private InlineData inline_data;

        public Part(String text) {
            this.text = text;
        }

        public Part(InlineData data) {
            this.inline_data = data;
        }

        @Override
        public String toString() {
            return "Part [text=" + text + ", inline_data=" + inline_data + "]";
        }
    }

    public static class InlineData {
        public String mime_type;
        public String data;

        public InlineData(String mimeType, String data) {
            this.mime_type = mimeType;
            this.data = data;
        }

        @Override
        public String toString() {
            return "InlineData [mime_type=" + mime_type + ", data=" + data + "]";
        }
    }
}
