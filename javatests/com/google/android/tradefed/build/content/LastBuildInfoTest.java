// Copyright 2023 Google Inc. All Rights Reserved.
package com.google.android.tradefed.build.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.android.tradefed.util.FileUtil;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

/** Unit tests for {@link LastBuildInfo}. */
public class LastBuildInfoTest {

    @Test
    public void testParseLastBuildInfo() throws IOException {
        File info = FileUtil.createTempFile("last_build_info-tests", ".info");
        FileUtil.writeToFile("aosp-main device-userdebug @ 8888/0", info);
        try {
            LastBuildInfo res = LastBuildInfo.parseFile(info, "device-override");
            assertNotNull(res);
            assertEquals("aosp-main", res.branch);
            assertEquals("device-override", res.buildFlavor);
            assertEquals("8888", res.buildId);
        } finally {
            FileUtil.deleteFile(info);
        }
    }
}
