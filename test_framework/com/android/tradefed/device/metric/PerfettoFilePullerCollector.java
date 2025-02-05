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
package com.android.tradefed.device.metric;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.LargeOutputReceiver;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * A collector which pulls perfetto trace files from the device, optionally compresses them, and
 * adds them to the test logs.
 */
@OptionClass(alias = "perfetto-file-puller-collector")
public class PerfettoFilePullerCollector extends FilePullerDeviceMetricCollector {

    @Option(
            name = "compress-perfetto",
            description =
                    "If enabled retrieves the perfetto compressed content,"
                            + "decompress for processing and upload the compressed file. If"
                            + "this flag is not enabled uncompressed version of perfetto file is"
                            + "pulled, processed and uploaded.")
    private boolean mCompressPerfetto = false;

    @Option(
            name = "max-compressed-file-size",
            description =
                    "Max size of the compressed"
                            + " perfetto file. If the compressed file size exceeds the max then"
                            + " post processing and uploading the compressed file will not happen.")
    private long mMaxCompressedFileSize = 10000L * 1024 * 1024;

    @Option(
            name = "compressed-trace-shell-timeout",
            description = "Timeout for retrieving compressed trace content through shell",
            isTimeVal = true)
    private long mCompressedTimeoutMs = TimeUnit.MINUTES.toMillis(20);

    @Option(
            name = "compress-response-timeout",
            description = "Timeout to receive the shell response when running the gzip command.",
            isTimeVal = true)
    private long mCompressResponseTimeoutMs = TimeUnit.SECONDS.toMillis(30);

    /**
     * Pull the file from the specified path in the device. Pull the compressed content of the
     * perfetto file if the compress perfetto option is enabled.
     *
     * @param device which has the file.
     * @param remoteFilePath location in the device.
     * @return compressed or decompressed version of perfetto file based on mCompressPerfetto option
     *     is set or not.
     * @throws DeviceNotAvailableException
     */
    @Override
    protected File retrieveFile(ITestDevice device, String remoteFilePath, int userId)
            throws DeviceNotAvailableException {
        if (!mCompressPerfetto) {
            return super.retrieveFile(device, remoteFilePath, userId);
        }
        File perfettoCompressedFile = null;
        String filePathInDevice = remoteFilePath;
        CLog.i("Retrieving the compressed perfetto trace content from device.");
        LargeOutputReceiver compressedOutputReceiver =
                new LargeOutputReceiver(
                        "perfetto_compressed_temp",
                        device.getSerialNumber(),
                        mMaxCompressedFileSize);
        device.executeShellCommand(
                String.format("gzip -c %s", filePathInDevice),
                compressedOutputReceiver,
                mCompressedTimeoutMs,
                mCompressResponseTimeoutMs,
                TimeUnit.MILLISECONDS,
                1);
        compressedOutputReceiver.flush();
        compressedOutputReceiver.cancel();

        // Copy to temp file which will be used for decompression, perfetto
        // metrics extraction and uploading the file later.
        try (InputStreamSource largeStreamSrc = compressedOutputReceiver.getData();
                InputStream inputStream = largeStreamSrc.createInputStream()) {
            // Preserve the original file name including the .perfetto-trace extension, so that
            // PerfettoTracePostProcessor can identify it as a trace file for processing.
            // This also has the advantage of preserving the test name in the file name.
            String perfettoFileName = new File(filePathInDevice).getName();
            perfettoCompressedFile = FileUtil.createTempFile(perfettoFileName, ".gz");

            FileOutputStream outStream = new FileOutputStream(perfettoCompressedFile);
            byte[] buffer = new byte[4096];
            int bytesRead = -1;
            while ((bytesRead = inputStream.read(buffer)) > -1) {
                outStream.write(buffer, 0, bytesRead);
            }
            StreamUtil.close(outStream);
            CLog.i("Successfully copied the compressed content from device to" + " host.");
        } catch (IOException e) {
            if (perfettoCompressedFile != null) {
                perfettoCompressedFile.delete();
            }
            CLog.e("Failed to copy compressed perfetto to temporary file.");
            CLog.e(e);
        } finally {
            compressedOutputReceiver.delete();
        }
        return perfettoCompressedFile;
    }

    /**
     * Take the perfetto trace file pulled from the device, which can be either raw or compressed,
     * and add it to the test logs.
     *
     * @param key the option key associated to the file that was pulled.
     * @param metricFile the {@link File} pulled from the device matching the option key.
     * @param data the {@link DeviceMetricData} where metrics can be stored.
     */
    @Override
    public void processMetricFile(String key, File metricFile, DeviceMetricData data) {
        try (InputStreamSource source = new FileInputStreamSource(metricFile, true)) {
            LogDataType type = mCompressPerfetto ? LogDataType.GZIP : LogDataType.PERFETTO;
            testLog(metricFile.getName(), type, source);
        }
    }

    @Override
    public void processMetricDirectory(String key, File metricDirectory, DeviceMetricData runData) {
        // Implement if all the files under specific directory have to be post processed.
    }
}
