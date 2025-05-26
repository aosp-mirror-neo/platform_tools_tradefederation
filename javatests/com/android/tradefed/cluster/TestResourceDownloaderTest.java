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
package com.android.tradefed.cluster;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.net.URL;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TestResourceDownloader}. */
@RunWith(JUnit4.class)
public class TestResourceDownloaderTest {

    @Test
    public void testBuildCurlCommandArgs() throws Exception {
        URL url = new URL("https://www.example.com");
        File file = new File("/tmp/test.txt");

        List<String> args = new TestResourceDownloader().buildCurlCommandArgs(url, file);
        assertEquals("curl -o /tmp/test.txt -fL https://www.example.com", String.join(" ", args));

        List<String> argsWithLimitRate =
                new TestResourceDownloader("200M").buildCurlCommandArgs(url, file);
        assertEquals(
                "curl -o /tmp/test.txt -fL https://www.example.com --limit-rate 200M",
                String.join(" ", argsWithLimitRate));
    }
}
