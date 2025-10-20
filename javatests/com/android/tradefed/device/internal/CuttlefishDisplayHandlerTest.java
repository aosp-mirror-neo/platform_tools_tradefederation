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
package com.android.tradefed.device.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.service.TradefedFeatureClient;

import com.proto.tradefed.feature.FeatureResponse;
import com.proto.tradefed.feature.internal.CuttlefishDisplay;
import com.proto.tradefed.feature.internal.CuttlefishDisplayFeatureResponse;
import com.proto.tradefed.feature.internal.CuttlefishDisplayListResponse;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Base64;

/** Unit tests for {@link CuttlefishDisplayHandler}. */
@RunWith(JUnit4.class)
public class CuttlefishDisplayHandlerTest {

    private CuttlefishDisplayHandler mHandler;
    private IInvocationContext mContext;
    @Mock TradefedFeatureClient mMockClient;
    @Mock ITestDevice mMockDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = new InvocationContext();
        mHandler = new CuttlefishDisplayHandler(mMockClient, mContext);
    }

    @Test
    public void testListDisplays() throws Exception {
        CuttlefishDisplayFeatureResponse.Builder displayResponseBuilder =
                CuttlefishDisplayFeatureResponse.newBuilder();
        CuttlefishDisplayListResponse.Builder displayListResponseBuilder =
                displayResponseBuilder
                        .getListDisplaysBuilder()
                        .putDisplays(
                                1,
                                CuttlefishDisplay.newBuilder()
                                        .setWidth(1)
                                        .setHeight(1)
                                        .setDpi(1)
                                        .setRefreshRateHz(1)
                                        .build());
        CuttlefishDisplayFeatureResponse displayResponse = displayResponseBuilder.build();

        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();

        responseBuilder.setResponse(
                Base64.getEncoder().encodeToString(displayResponse.toByteArray()));

        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        mHandler.listDisplays(mMockDevice);
    }
}
