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
package com.android.tradefed.result.resultdb;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public final class BatchChannelTest {
    private BatchUploadClient mClient;
    private BatchChannel<String> mBatchChannel;

    @Before
    public void doBeforeEachTest() {
        mClient = new BatchUploadClient();
        mBatchChannel =
                new BatchChannel<>(
                        10, // Batch size is 10.
                        "test",
                        (batch) -> {
                            mClient.uploadBatch(batch);
                        });
    }

    @Test
    public void uploadBatch_partialBatch() throws InterruptedException {
        for (int i = 0; i < 11; i++) {
            mBatchChannel.enqueue(String.valueOf(i));
        }
        mBatchChannel.finalizeUpload();

        assertThat(mClient.mBatches).hasSize(2);
        assertThat(mClient.mBatches.get(0))
                .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(mClient.mBatches.get(1)).containsExactly("10");

        // enqueue after finalizeUpload should throw exception.
        Assert.assertThrows(IllegalStateException.class, () -> mBatchChannel.enqueue("11"));
    }

    @Test
    public void uploadBatch_fullBatch() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            mBatchChannel.enqueue(String.valueOf(i));
        }
        mBatchChannel.finalizeUpload();

        assertThat(mClient.mBatches).hasSize(2);
        assertThat(mClient.mBatches.get(0))
                .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(mClient.mBatches.get(1))
                .containsExactly("10", "11", "12", "13", "14", "15", "16", "17", "18", "19");

        // enqueue after finalizeUpload should throw exception.
        Assert.assertThrows(IllegalStateException.class, () -> mBatchChannel.enqueue("20"));
    }

    @Test
    public void uploadBatch_uploadFailure() throws InterruptedException {
        mClient.mShouldUploadFail = true;
        mBatchChannel.enqueue("1");
        //  no exception is thrown here.
        mBatchChannel.finalizeUpload();

        assertThat(mClient.mBatches).hasSize(0);
    }

    @Test
    public void uploadBatch_forceUpload() throws InterruptedException {
        for (int i = 0; i < 9; i++) {
            mBatchChannel.enqueue(String.valueOf(i));
        }

        // No upload happens since the batch is not full.
        assertThat(mClient.mBatches).hasSize(0);

        // Force upload should upload even though the batch is not full.
        mBatchChannel.forceUpload();
        assertThat(mClient.mBatches).hasSize(1);
        assertThat(mClient.mBatches.get(0))
                .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8");
        mBatchChannel.finalizeUpload();

        // No more upload should happen during finalizeUpload.
        assertThat(mClient.mBatches).hasSize(1);
    }

    private class BatchUploadClient {
        private final ArrayList<List<String>> mBatches = new ArrayList<>();
        private Boolean mShouldUploadFail = false;

        public void uploadBatch(List<String> batch) {
            if (mShouldUploadFail) {
                throw new RuntimeException("Failed to upload batch");
            }
            mBatches.add(batch);
        }
    }
}
