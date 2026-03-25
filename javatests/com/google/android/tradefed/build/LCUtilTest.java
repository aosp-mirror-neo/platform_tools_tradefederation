// Copyright 2011 Google Inc. All Rights Reserved.

package com.google.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Map;

/** Helper class to parse a Launch Control path into its constituent parts */
@RunWith(JUnit4.class)
public class LCUtilTest {
    @Test
    public void testParseAttributeLine() throws Exception {
        final String line = "userdata:git_main-linux-yakju-tests/242537/userdata.img";
        Map<String, String> m = LCUtil.parseAttributeLine(line);
        assertEquals(6, m.size());
        assertFalse(m.containsKey("kernel"));
        assertEquals("userdata", m.get("key"));
        assertEquals("git_main", m.get("branch"));
        assertEquals("linux", m.get("os"));
        assertEquals("yakju-tests", m.get("flavor"));
        assertEquals("242537", m.get("bid"));
        assertEquals("userdata.img", m.get("filename"));
    }

    @Test
    public void testParseAttributeLine_branchWithDash() throws Exception {
        final String line = "ub-gcore-parmesan-release-linux-GmsCore/1821559/GmsCore.apk";
        Map<String, String> m = LCUtil.parseAttributeLine(line);
        assertEquals(6, m.size());
        assertFalse(m.containsKey("kernel"));
        assertNull(m.get("key"));
        assertEquals("ub-gcore-parmesan-release", m.get("branch"));
        assertEquals("linux", m.get("os"));
        assertEquals("GmsCore", m.get("flavor"));
        assertEquals("1821559", m.get("bid"));
        assertEquals("GmsCore.apk", m.get("filename"));
    }

    @Test
    public void testParseAttributeLine_fastbuild() throws Exception {
        final String line =
                "git_main-release-fastbuild_linux-hammerhead-userdebug/1834480/bootloader.img";
        Map<String, String> m = LCUtil.parseAttributeLine(line);
        assertEquals(6, m.size());
        assertFalse(m.containsKey("kernel"));
        assertNull(m.get("key"));
        assertEquals("git_main-release", m.get("branch"));
        assertEquals("fastbuild_linux", m.get("os"));
        assertEquals("hammerhead-userdebug", m.get("flavor"));
        assertEquals("1834480", m.get("bid"));
        assertEquals("bootloader.img", m.get("filename"));
    }

    @Test
    public void testParseAttributeLine_trainBuildId() throws Exception {
      final String line = "git_mainline-module-train-builds-external-linux-train_build/T1834480/mainline_modules_arm64/something.apex";
      Map<String, String> m = LCUtil.parseAttributeLine(line);
      assertEquals(6, m.size());
      assertFalse(m.containsKey("kernel"));
      assertNull(m.get("key"));
      assertEquals("git_mainline-module-train-builds-external", m.get("branch"));
      assertEquals("linux", m.get("os"));
      assertEquals("train_build", m.get("flavor"));
      assertEquals("T1834480", m.get("bid"));
      assertEquals("mainline_modules_arm64/something.apex", m.get("filename"));
    }

    @Test
    public void testParseAttributeLine_pendingBuildId() throws Exception {
      final String line = "git_mainline-module-train-builds-external-linux-train_build/P1834480/mainline_modules_arm64/something.apex";
      Map<String, String> m = LCUtil.parseAttributeLine(line);
      assertEquals(6, m.size());
      assertFalse(m.containsKey("kernel"));
      assertNull(m.get("key"));
      assertEquals("git_mainline-module-train-builds-external", m.get("branch"));
      assertEquals("linux", m.get("os"));
      assertEquals("train_build", m.get("flavor"));
      assertEquals("P1834480", m.get("bid"));
      assertEquals("mainline_modules_arm64/something.apex", m.get("filename"));
    }

    @Test
    public void testParseAttributeLine_twoLetterPrefix() throws Exception {
      final String line = "git_mainline-module-train-builds-external-linux-train_build/PT1834480/mainline_modules_arm64/something.apex";
      // Doesn't match, should return null.
      Map<String, String> m = LCUtil.parseAttributeLine(line);
      assertNull(m);
    }

    @Test
    public void testParseAttributeLine_badPrefix() throws Exception {
      final String line = "git_mainline-module-train-builds-external-linux-train_build/-1834480/mainline_modules_arm64/something.apex";
      // Doesn't match, should return null.
      Map<String, String> m = LCUtil.parseAttributeLine(line);
      assertNull(m);
    }

    @Test
    public void testParseKernelAttributeLine() throws Exception {
        final String line =
                "android-omap-tuna-3.0-main/1e843e8996f61a22794bf54da25b008c6886c140/kernel";
        Map<String, String> m = LCUtil.parseAttributeLine(line);
        assertEquals(4, m.size());
        assertTrue(m.containsKey("kernel"));
        assertEquals("android-omap-tuna-3.0-main", m.get("branch"));
        assertEquals("1e843e8996f61a22794bf54da25b008c6886c140", m.get("bid"));
        assertEquals("kernel", m.get("filename"));
    }

    @Test
    public void testParseResolvablePath() throws Exception {
        final String line = "ab:/aosp_main/yakju-userdebug/P123/device-tests.zip";
        Map<String, String> m = LCUtil.parseAttributeLine(line);
        assertEquals(4, m.size());
        assertEquals("aosp_main", m.get("branch"));
        assertEquals("yakju-userdebug", m.get("flavor"));
        assertEquals("P123", m.get("bid"));
        assertEquals("device-tests.zip", m.get("filename"));
    }

    @Test
    public void testCreatesolvableFilePath() throws Exception {
        String resolvablePath =
                LCUtil.createResolvableFilePath(
                        "aosp_main-linux-yakju-userdebug/P123/device-tests.zip");
        assertEquals("ab:/aosp_main/yakju-userdebug/P123/device-tests.zip", resolvablePath);
    }

    @Test
    public void testCreateResolvableFilePath_fail() throws Exception {
        try {
            LCUtil.createResolvableFilePath("aosp_main-linux-yakju-userdebug/P123");
            fail("createResolvableFilePath method should throw key not found exception.");
        } catch (RuntimeException e) {
            // Ignore exception.
        }
    }
}
