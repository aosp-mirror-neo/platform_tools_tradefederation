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

package com.android.tradefed.util.avd;

import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandResultWith;

import java.util.Map;

/**
 * Interfacce for interacting with a Cuttlefish device with either `cvd` or the Cuttlefish host
 * orchestrator.
 */
public interface CuttlefishControl {

    public CommandResultWith<Integer> addDisplay(int width, int height, int dpi, int refreshRateHz);

    public static class CuttlefishDisplay {
        public int width;
        public int height;
        public int dpi;
        public int refreshRateHz;
    }

    public static class CuttlefishDisplays {
        public Map<Integer, CuttlefishDisplay> displays;
    }

    public CommandResultWith<CuttlefishDisplays> listDisplays();

    public CommandResult removeDisplay(int displayNumber);

    public static class CuttlefishScreenshot {
        public String screenshotMimeType;
        public byte[] screenshotBytes;
    }

    public CommandResultWith<CuttlefishScreenshot> screenshotDisplay(int displayNumber);
}
