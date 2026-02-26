/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tradefed.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.config.OptionDef;
import com.android.tradefed.error.HarnessRuntimeException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link ArgUtil}. */
@RunWith(JUnit4.class)
public class ArgUtilTest {

    @Test
    public void testParseArgs() {
        List<String> args = new ArrayList<>();
        args.add("com.android.tradefed.util.ArgUtilTest:simple-string:value1");
        args.add(
                "com.android.tradefed.util.ArgUtilTest:empty-string:"); // value is the empty string
        args.add("com.android.tradefed.util.ArgUtilTest:list-string:value2");
        args.add("com.android.tradefed.util.ArgUtilTest:list-string:value3");
        args.add("com.android.tradefed.util.ArgUtilTest:list-string:set-option:moreoption");
        args.add("com.android.tradefed.util.ArgUtilTest:map-string:set-option:=moreoption");
        args.add("com.android.tradefed.util.ArgUtilTest:map-string:empty-option:=");

        Map<String, List<OptionDef>> options = new HashMap<>();
        ArgUtil.parseArgs(args, options);
        assertTrue(options.size() == 1);
        List<OptionDef> optionDefs = options.get("com.android.tradefed.util.ArgUtilTest");
        assertTrue(optionDefs.size() == 7);
        assertEquals("simple-string", optionDefs.get(0).name);
        assertEquals("value1", optionDefs.get(0).value);
        assertEquals("empty-string", optionDefs.get(1).name);
        assertEquals("", optionDefs.get(1).value);
        assertEquals("list-string", optionDefs.get(2).name);
        assertEquals("value2", optionDefs.get(2).value);
        assertEquals("list-string", optionDefs.get(3).name);
        assertEquals("value3", optionDefs.get(3).value);
        assertEquals("list-string", optionDefs.get(4).name);
        assertEquals("set-option:moreoption", optionDefs.get(4).value);
        assertEquals("map-string", optionDefs.get(5).name);
        assertEquals("set-option", optionDefs.get(5).key);
        assertEquals("moreoption", optionDefs.get(5).value);
        assertEquals("map-string", optionDefs.get(6).name);
        assertEquals("empty-option", optionDefs.get(6).key);
        assertEquals("", optionDefs.get(6).value);
    }

    @Test
    public void testParseArgs_alias() {
        List<String> args = new ArrayList<>();
        args.add("com.android.tradefed.util.ArgUtilTest:{alias-name}option-name:value1");

        Map<String, List<OptionDef>> options = new HashMap<>();
        ArgUtil.parseArgs(args, options);
        assertTrue(options.size() == 1);
        List<OptionDef> optionDefs = options.get("com.android.tradefed.util.ArgUtilTest");
        assertTrue(optionDefs.size() == 1);
        assertEquals("alias-name:option-name", optionDefs.get(0).name);
        assertEquals("value1", optionDefs.get(0).value);
    }

    @Test
    public void testParseArgs_zeroColon() throws Exception {
        List<String> args = new ArrayList<>();
        args.add("com.android.tradefed.util.ArgUtilTest");

        try {
            ArgUtil.parseArgs(args, new HashMap<>());
            fail("Should have thrown an exception");
        } catch (HarnessRuntimeException e) {
            assertEquals("Expected delimiter ':' for module or class.", e.getMessage());
        }
    }

    @Test
    public void testParseArgs_oneColon() throws Exception {
        List<String> args = new ArrayList<>();
        args.add("com.android.tradefed.util.ArgUtilTest:option-name");

        try {
            ArgUtil.parseArgs(args, new HashMap<>());
            fail("Should have thrown an exception");
        } catch (HarnessRuntimeException e) {
            assertEquals("Expected delimiter ':' between option name and values.", e.getMessage());
        }
    }
}
