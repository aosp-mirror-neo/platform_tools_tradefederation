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

package com.android.tradefed.util.gcs;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.storage.Storage;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GCSFileDownloaderBase}. */
@RunWith(JUnit4.class)
public class GCSFileDownloaderBaseTest {

    private GCSFileDownloaderBase mDownloader;
    private Storage mMockStorage;
    private Storage.Objects mMockObjects;
    private Storage.Objects.List mMockList;

    @Before
    public void setUp() throws Exception {
        mMockStorage = mock(Storage.class);
        mMockObjects = mock(Storage.Objects.class);
        mMockList = mock(Storage.Objects.List.class);

        when(mMockStorage.objects()).thenReturn(mMockObjects);
        when(mMockObjects.list(any())).thenReturn(mMockList);
        when(mMockList.setPrefix(any())).thenReturn(mMockList);
        when(mMockList.setDelimiter(any())).thenReturn(mMockList);
        when(mMockList.setMaxResults(anyLong())).thenReturn(mMockList);

        mDownloader =
                new GCSFileDownloaderBase() {
                    @Override
                    protected Storage getStorage() throws IOException {
                        return mMockStorage;
                    }
                };
    }

    @Test
    public void testIsRemoteFolder_permissionDenied_assumeFile() throws Exception {
        GoogleJsonError error = new GoogleJsonError();
        error.setCode(403);
        HttpResponseException.Builder builder =
                new HttpResponseException.Builder(403, "Forbidden", new HttpHeaders());
        builder.setMessage("user does not have storage.objects.list access to the bucket");
        GoogleJsonResponseException exception = new GoogleJsonResponseException(builder, error);

        when(mMockList.execute()).thenThrow(exception);

        boolean isFolder = mDownloader.isRemoteFolder("bucket", "filename");

        assertFalse(isFolder);
    }

    @Test(expected = GoogleJsonResponseException.class)
    public void testIsRemoteFolder_other403Error_throwsException() throws Exception {
        GoogleJsonError error = new GoogleJsonError();
        error.setCode(403);
        HttpResponseException.Builder builder =
                new HttpResponseException.Builder(403, "Forbidden", new HttpHeaders());
        builder.setMessage("Some other 403 forbidden error");
        GoogleJsonResponseException exception = new GoogleJsonResponseException(builder, error);

        when(mMockList.execute()).thenThrow(exception);

        mDownloader.isRemoteFolder("bucket", "filename");
    }
}
