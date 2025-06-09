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

import java.lang.reflect.InvocationTargetException;

/** Enumeration describing which processor can automatically be handled by the harness. */
public enum AutoLogProcessor {
    GEMINI_AI_DEBUGGING(GeminiDebuggingPostProcessor.class);

    private Class<?> mClass;

    private AutoLogProcessor(Class<? extends BasePostProcessor> className) {
        mClass = className;
    }

    /** Returns the instance of processor associated with the {@link AutoLogProcessor} value. */
    public BasePostProcessor getInstanceForValue() {
        try {
            Object o = mClass.getDeclaredConstructor().newInstance();
            return (BasePostProcessor) o;
        } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException
                | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
