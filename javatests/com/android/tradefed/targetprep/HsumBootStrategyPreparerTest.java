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

package com.android.tradefed.targetprep;

import static com.android.tradefed.device.HsumHelper.HsumBootStrategy;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.HsumHelper;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/** Unit tests for {@link HsumBootStrategyPreparer}. */
@RunWith(MockitoJUnitRunner.class)
public final class HsumBootStrategyPreparerTest {

    @Mock private ITestDevice mMockDevice;
    private TestInformation mTestInformation;
    private HsumBootStrategyPreparer mHsumBootStrategyPreparer;
    private OptionSetter mOptionSetter;

    @Before
    public void setUp() throws Exception {
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, mMockDevice);
        mTestInformation = TestInformation.newBuilder().setInvocationContext(context).build();
        mHsumBootStrategyPreparer = new HsumBootStrategyPreparer();
        mOptionSetter = new OptionSetter(mHsumBootStrategyPreparer);
    }

    @Test
    public void setUpAndTearDown_setsAndRestoresStrategy() throws Exception {
        mOptionSetter.setOptionValue(
                HsumBootStrategyPreparer.OPTION_HSUM_BOOT_STRATEGY,
                HsumBootStrategy.SWITCH_TO_PREVIOUS_USER.name());

        mockHsumBootStrategy(HsumBootStrategy.DEFAULT_BEHAVIOR);

        // setUp
        mHsumBootStrategyPreparer.setUp(mTestInformation);

        // Verify the behavior during setUp
        verify(mMockDevice)
                .getIntProperty(
                        HsumHelper.BOOT_STRATEGY_PROPERTY,
                        HsumBootStrategy.DEFAULT_BEHAVIOR.getValue());
        verify(mMockDevice)
                .setProperty(
                        HsumHelper.BOOT_STRATEGY_PROPERTY,
                        String.valueOf(HsumBootStrategy.SWITCH_TO_PREVIOUS_USER.getValue()));

        // tearDown
        mHsumBootStrategyPreparer.tearDown(mTestInformation, null);
        verify(mMockDevice)
                .setProperty(
                        HsumHelper.BOOT_STRATEGY_PROPERTY,
                        String.valueOf(HsumBootStrategy.DEFAULT_BEHAVIOR.getValue()));
    }

    private void mockHsumBootStrategy(HsumBootStrategy hsumBootStrategy)
            throws DeviceNotAvailableException {
        when(mMockDevice.getIntProperty(
                        HsumHelper.BOOT_STRATEGY_PROPERTY,
                        HsumBootStrategy.DEFAULT_BEHAVIOR.getValue()))
                .thenReturn((long) hsumBootStrategy.getValue());
    }
}
