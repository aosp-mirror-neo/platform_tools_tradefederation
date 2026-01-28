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
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SdkVersionUtil}. */
@RunWith(JUnit4.class)
public class SdkVersionUtilTest {

    @Test
    public void testParseFullVersion_majorOnly() {
        assertEquals(3300000, SdkVersionUtil.parseFullVersion("33"));
    }

    @Test
    public void testParseFullVersion_majorMinor() {
        assertEquals(3300001, SdkVersionUtil.parseFullVersion("33.1"));
    }

    @Test
    public void testParseFullVersion_malformed() {
        assertThrows(
                IllegalArgumentException.class, () -> SdkVersionUtil.parseFullVersion("invalid"));
    }

    @Test
    public void testParseFullVersion_negativeMajor() {
        assertThrows(
                IllegalArgumentException.class, () -> SdkVersionUtil.parseFullVersion("-33.1"));
    }

    @Test
    public void testParseFullVersion_largeMajor() {
        assertThrows(
                IllegalArgumentException.class, () -> SdkVersionUtil.parseFullVersion("21474.0"));
    }

    @Test
    public void testParseFullVersion_negativeMinor() {
        assertThrows(
                IllegalArgumentException.class, () -> SdkVersionUtil.parseFullVersion("33.-1"));
    }

    @Test
    public void testParseFullVersion_largeMinor() {
        assertThrows(
                IllegalArgumentException.class, () -> SdkVersionUtil.parseFullVersion("33.100000"));
    }

    @Test
    public void testGetMajorSdkVersion() {
        assertEquals(33, SdkVersionUtil.getMajorSdkVersion(3300001));
    }

    @Test
    public void testGetMinorSdkVersion() {
        assertEquals(1, SdkVersionUtil.getMinorSdkVersion(3300001));
    }
}
