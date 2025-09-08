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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;

public class CuttlefishUtils {

    public static CuttlefishControl.CuttlefishDisplays parseCvdListDisplayOutput(String raw)
            throws JSONException {
        CuttlefishControl.CuttlefishDisplays cuttlefishDisplays =
                new CuttlefishControl.CuttlefishDisplays();
        cuttlefishDisplays.displays = new HashMap<>();

        JSONObject json = new JSONObject(raw);
        JSONObject jsonDisplays = json.getJSONObject("displays");
        for (Iterator<String> keyIt = jsonDisplays.keys(); keyIt.hasNext(); ) {
            String displayNumberString = keyIt.next();

            JSONObject jsonDisplay = jsonDisplays.getJSONObject(displayNumberString);
            JSONObject jsonDisplayMode = jsonDisplay.getJSONObject("mode");
            JSONArray jsonDisplayModeWindowed = jsonDisplayMode.getJSONArray("windowed");
            JSONArray jsonDisplayDpi = jsonDisplay.getJSONArray("dpi");

            int id = Integer.parseInt(displayNumberString);
            int w = jsonDisplayModeWindowed.getInt(0);
            int h = jsonDisplayModeWindowed.getInt(1);
            int dpi = jsonDisplayDpi.getInt(0);
            int refreshRateHz = jsonDisplay.getInt("refresh-rate");

            CuttlefishControl.CuttlefishDisplay cuttlefishDisplay =
                    new CuttlefishControl.CuttlefishDisplay();
            cuttlefishDisplay.width = w;
            cuttlefishDisplay.height = h;
            cuttlefishDisplay.dpi = dpi;
            cuttlefishDisplay.refreshRateHz = refreshRateHz;
            cuttlefishDisplays.displays.put(id, cuttlefishDisplay);
        }

        return cuttlefishDisplays;
    }
}
