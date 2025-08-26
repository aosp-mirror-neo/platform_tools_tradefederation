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
package com.google.android.tradefed.build.content;

import com.android.tradefed.build.content.ContentInformation;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Cache {@link ContentInformation} and ensures the lifecycle of the files related to it. */
public class ContentInformationCache {

    private static ContentInformationCache sDefaultInstance;
    private final LoadingCache<String, ContentInformation> mContentCacheTracker;

    public static ContentInformationCache getDefaultCache() {
        if (sDefaultInstance == null) {
            sDefaultInstance = new ContentInformationCache();
        }
        return sDefaultInstance;
    }

    protected ContentInformationCache() {
        RemovalListener<String, ContentInformation> listener =
                new RemovalListener<String, ContentInformation>() {
                    @Override
                    public void onRemoval(RemovalNotification<String, ContentInformation> n) {
                        if (n.wasEvicted()) {
                            n.getValue().clean();
                        }
                    }
                };
        mContentCacheTracker =
                CacheBuilder.newBuilder()
                        .maximumSize(50)
                        .expireAfterAccess(1, TimeUnit.DAYS)
                        .removalListener(listener)
                        .build(
                                new CacheLoader<String, ContentInformation>() {
                                    @Override
                                    public ContentInformation load(String key) throws IOException {
                                        // We manually seed and manage the cache
                                        // no need to load.
                                        return null;
                                    }
                                });
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread() {
                            @Override
                            public void run() {
                                cleanUp();
                            }
                        });
    }

    /** Track a {@link ContentInformation} based on its origin. */
    public void trackContent(
            String branch, String buildId, String buildFlavor, ContentInformation contentInfo)
            throws IOException {
        mContentCacheTracker.put(
                String.format("%s:%s:%s", branch, buildId, buildFlavor),
                contentInfo.cloneInformation(null));
    }

    /** Retrieve a {@link ContentInformation} based on its origin. */
    public ContentInformation getContentInformation(
            String branch, String buildId, String buildFlavor) {
        try {
            ContentInformation info =
                    mContentCacheTracker.getIfPresent(
                            String.format("%s:%s:%s", branch, buildId, buildFlavor));
            if (info == null) {
                return null;
            }
            return info.cloneInformation();
        } catch (RuntimeException | IOException e) {
            CLog.e(e);
        }
        return null;
    }

    private void cleanUp() {
        mContentCacheTracker.invalidateAll();
    }
}
