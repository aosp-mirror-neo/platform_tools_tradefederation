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

package com.android.tradefed.testtype;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** Unit tests for {@link ArtTest}. */
@RunWith(JUnit4.class)
public class ArtTestTest {
    public static final String INPUT =
            String.join(
                    "\n",
                    "{",
                    "    \"001-HelloWorld#1f5cdb07\": {",
                    "      \"adb push\": [",
                    "        [ \"../target/001-HelloWorld\",\"/data/local/tmp/art/test/1f5cdb07\""
                            + " ],",
                    "        [ \"001-HelloWorld/1f5cdb07.sh\","
                            + " \"/data/local/tmp/art/test/1f5cdb07/run.sh\" ]",
                    "      ],",
                    "      \"adb shell\": [",
                    "        [ \"sh\", \"/data/local/tmp/art/test/1f5cdb07/run.sh\" ]",
                    "      ],",
                    "      \"dependencies\": [",
                    "        \"setup#compile-boot-image\"",
                    "      ]",
                    "    },",
                    "    \"setup#compile-boot-image\": {",
                    "      \"adb push\": [",
                    "        [ \"setup.sh\", \"/data/local/tmp/art/setup.sh\" ]",
                    "      ],",
                    "      \"adb shell\": [",
                    "        [ \"rm\", \"-rf\", \"/data/local/tmp/art/test\" ],",
                    "        [ \"sh\", \"/data/local/tmp/art/setup.sh\" ]",
                    "      ]",
                    "    }",
                    "}");

    private ArtTest mTest;
    @Mock ITestInvocationListener mListener;
    @Mock ITestDevice mDevice;

    @Before
    public void setUp() throws IOException {
        mTest =
                new ArtTest() {
                    @Override
                    File extractTarBall(File dst_dir) throws IOException {
                        try (FileWriter fw = new FileWriter(new File(dst_dir, "tests.json"))) {
                            fw.write(INPUT);
                        }
                        return dst_dir;
                    }
                };
        mListener = mock(ITestInvocationListener.class);
        mDevice = mock(ITestDevice.class);
    }

    @Test
    public void testRunTests() throws DeviceNotAvailableException {
        // Create mock test info.
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mDevice);
        TestInformation info = TestInformation.newBuilder().setInvocationContext(context).build();

        // Implement mocked methods by just always returning SUCCESS (no device is needed).
        CommandResult success = new CommandResult(CommandStatus.SUCCESS);
        when(mDevice.executeShellV2Command(any())).thenReturn(success);

        // Run the test.
        mTest.run(info, mListener);

        // Check the recorded behaviour of test.  We expected the following commands to be executed:
        verify(mDevice).executeShellV2Command("rm -rf /data/local/tmp/art/test");
        verify(mDevice).executeShellV2Command("sh /data/local/tmp/art/setup.sh");
        verify(mDevice).executeShellV2Command("sh /data/local/tmp/art/test/1f5cdb07/run.sh");
    }
}
