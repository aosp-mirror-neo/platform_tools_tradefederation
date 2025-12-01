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

import com.android.tradefed.log.LogUtil.CLog;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Helper class to batch upload test result and artifacts. */
public class BatchChannel<T> {

    private final int mMaxBatchSize;
    // Item name, only used for logging.
    private final String mItemName;
    private final LinkedBlockingQueue<T> mItemQueue;
    private final BatchUploadAction<T> mBatchUploadAction;
    // Executor
    private final ExecutorService executorService;
    // Buffer for items polled from queue but not yet uploaded.
    private final List<T> mBatch;

    // Shutdown flag
    private volatile boolean mShutdownRequested = false;

    public BatchChannel(int maxBatchSize, String itemName, BatchUploadAction<T> batchUploadAction) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive.");
        }
        this.mItemName = itemName;
        this.mItemQueue = new LinkedBlockingQueue<>();
        this.executorService = Executors.newSingleThreadExecutor();
        this.mMaxBatchSize = maxBatchSize;
        this.mBatch = new ArrayList<>(maxBatchSize);
        this.mBatchUploadAction = batchUploadAction;
        executorService.execute(() -> processQueue());
    }

    private void processQueue() {
        CLog.i("Uploader Service started for %s. Batch Size: %s", mItemName, mMaxBatchSize);
        while (!mShutdownRequested || !mItemQueue.isEmpty()) {
            // Synchronized block to avoid race conditions with forceUpload.
            // We only allow one thread to upload the items from mItemQueue and mBatch at a time.
            synchronized (this) {
                T item = null;
                try {
                    // Wait for 1 second for more items.
                    item = mItemQueue.poll(1000L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // Exit if the thread is interrupted.
                    CLog.e(
                            "Uploader Service interrupted while polling for %s:%s ",
                            mItemName, e.getMessage());
                    Thread.currentThread().interrupt();
                    return;
                }
                ImmutableList<T> batchToUpload = null;
                if (item != null) {
                    mBatch.add(item);
                }
                if (mBatch.size() >= mMaxBatchSize) {
                    batchToUpload = ImmutableList.copyOf(mBatch);
                    mBatch.clear();
                }

                if (batchToUpload != null) {
                    try {
                        mBatchUploadAction.uploadBatch(batchToUpload);
                    } catch (Exception e) {
                        // We log the error, we log the error and clear the batch to avoid infinite
                        // loops on errors. This means that we will lose the batch.
                        // TODO (b/464393481): ResultDB may reject the test result upload request if
                        // some fields
                        // are invalid (eg. test identifier is too long).
                        // We need some way to surface this error to avoid silently dropping
                        // test results.
                        CLog.e("Failed to upload batch %s: %s", mItemName, e.getMessage());
                        // continue the loop, upload other batches.
                    }
                }
            }
        }

        // Upload the last batch.
        synchronized (this) {
            ImmutableList<T> finalBatch = null;
            if (!mBatch.isEmpty()) {
                finalBatch = ImmutableList.copyOf(mBatch);
                mBatch.clear();
            }
            if (finalBatch != null) {
                try {
                    mBatchUploadAction.uploadBatch(finalBatch);
                } catch (Exception e) {
                    // We log the error, we just log the error. This means that we will lose the
                    // batch.
                    CLog.e("Failed to upload final batch %s: %s", mItemName, e.getMessage());
                }
            }
        }
    }

    public void enqueue(T item) throws InterruptedException {
        if (mShutdownRequested) {
            throw new IllegalStateException(
                    "Uploader service finalizing. Cannot enqueue new" + mItemName);
        }
        mItemQueue.put(item);
    }

    public void finalizeUpload() throws InterruptedException {
        CLog.i("Uploader Service finalizing %s.", mItemName);
        mShutdownRequested = true;
        executorService.shutdown();
        if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
            CLog.i(
                    "Uploader tasks for %s did not finish within the timeout. Forcing shutdown...",
                    mItemName);
            executorService.shutdownNow();
        } else {
            CLog.i("Main thread: All pending batch uploads completed %s.", mItemName);
        }
    }

    /**
     * Upload all items currently in the queue. This is a blocking call that waits for all items
     * enqueued previously to be uploaded. Calling this method in mBatchUploadAction of the same
     * instance will cause deadlock.
     */
    public synchronized void forceUpload() {
        List<T> currentItems = new ArrayList<>();
        // Add all items in the queue to the current items list.
        mItemQueue.drainTo(currentItems);
        // Add all items in the mBatch to the current items list.
        currentItems.addAll(mBatch);
        mBatch.clear();

        CLog.i("Force uploading %d items for %s.", currentItems.size(), mItemName);
        // Upload in chunks
        for (int i = 0; i < currentItems.size(); i += mMaxBatchSize) {
            List<T> chunk =
                    currentItems.subList(i, Math.min(i + mMaxBatchSize, currentItems.size()));
            try {
                mBatchUploadAction.uploadBatch(ImmutableList.copyOf(chunk));
            } catch (Exception e) {
                CLog.e(
                        "Failed to upload chunk during force upload %s: %s",
                        mItemName, e.getMessage());
            }
        }
    }

    /** Action to be performed when a batch of items is ready to be uploaded. */
    @FunctionalInterface
    public interface BatchUploadAction<T> {
        void uploadBatch(ImmutableList<T> batch) throws Exception;
    }
}
