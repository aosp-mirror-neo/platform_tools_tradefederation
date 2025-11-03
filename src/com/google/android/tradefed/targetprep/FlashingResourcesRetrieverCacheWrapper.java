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

package com.google.android.tradefed.targetprep;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.FileDownloadCache;
import com.android.tradefed.build.FileDownloadCacheFactory;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.IFlashingResourcesRetriever;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A {@link IFlashingResourcesRetriever} wrapper that use cache. */
public class FlashingResourcesRetrieverCacheWrapper implements IFlashingResourcesRetriever {
    private final IFlashingResourcesRetriever mDelegateRetriever;
    private final FileDownloadCache mCache;
    private final IFileDownloader mDownloadWrapper;

    /** A {@link IFileDownloader} that wraps the retriever to use the downloader cache. */
    private class FlashingResourcesRetrieverDownloadWrapper implements IFileDownloader {

        /** {@inheritDoc} */
        @Override
        public File downloadFile(String remoteFilePath) throws BuildRetrievalError {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override
        public void downloadFile(String remoteFilePath, File destFile) throws BuildRetrievalError {
            // RemoteFilePath is imageName/imageName.version.img Version may include ".".
            Pattern pattern = Pattern.compile("([^\\/]*)\\/([^\\/\\.]*)\\.(.*)\\.img");
            Matcher matcher = pattern.matcher(remoteFilePath);
            if (!matcher.find()) {
                throw new BuildRetrievalError(
                        String.format(
                                "%s should be like \"imageName/imageName.version.img\"",
                                remoteFilePath),
                        InfraErrorIdentifier.ARTIFACT_UNSUPPORTED_PATH);
            }
            File file = null;
            try {
                file = mDelegateRetriever.retrieveFile(matcher.group(2), matcher.group(3));
                // copy the downloaded file to the destFile.
                FileUtil.copyFile(file, destFile);
                // delete the downloaded file, so it will not leak.
                FileUtil.deleteFile(file);
            } catch (TargetSetupError e) {
                throw new BuildRetrievalError(
                        String.format("Failed to fetch %s", remoteFilePath),
                        e,
                        InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
            } catch (IOException e) {
                throw new BuildRetrievalError(
                        String.format(
                                "Failed to copy %s to %s",
                                file.getAbsoluteFile(), destFile.getAbsoluteFile()),
                        e,
                        InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
            }
        }
    }

    public FlashingResourcesRetrieverCacheWrapper(
            File cacheDir, IFlashingResourcesRetriever delegateRetriever) {
        mCache = FileDownloadCacheFactory.getInstance().getCache(cacheDir);
        mDelegateRetriever = delegateRetriever;
        mDownloadWrapper = new FlashingResourcesRetrieverDownloadWrapper();
    }

    /** {@inheritDoc} */
    @Override
    public File retrieveFile(String imageName, String version) throws TargetSetupError {
        String remotePath = String.format("%s/%s.%s.img", imageName, imageName, version);
        try {
            return mCache.fetchRemoteFile(mDownloadWrapper, remotePath);
        } catch (BuildRetrievalError e) {
            throw new TargetSetupError(
                    String.format("Failed to fetch %s", remotePath), e, null, false);
        }
    }
}
