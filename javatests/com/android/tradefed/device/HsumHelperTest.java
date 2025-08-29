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

package com.android.tradefed.device;

import static com.android.tradefed.device.HsumHelper.BOOT_STRATEGY_PROPERTY;
import static com.android.tradefed.device.HsumHelper.HsumBootStrategy.DEFAULT_BEHAVIOR;
import static com.android.tradefed.device.HsumHelper.HsumBootStrategy.STAY_ON_SYSTEM_USER;
import static com.android.tradefed.device.HsumHelper.HsumBootStrategy.SWITCH_TO_PREVIOUS_USER;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.HsumHelper.HsumBootStrategy;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public final class HsumHelperTest {

    @Rule public final Expect expect = Expect.create();
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private ITestDevice mMockDevice;

    private HsumHelper mHelper;

    @Before
    public void setHelper() {
        mHelper = new HsumHelper(mMockDevice);
    }

    @Test
    public void testHsumBootStrategyValues() {
        expect.withMessage("%s", DEFAULT_BEHAVIOR).that(DEFAULT_BEHAVIOR.getValue()).isEqualTo(-1);
        expect.withMessage("%s", SWITCH_TO_PREVIOUS_USER)
                .that(SWITCH_TO_PREVIOUS_USER.getValue())
                .isEqualTo(0);
        expect.withMessage("%s", STAY_ON_SYSTEM_USER)
                .that(STAY_ON_SYSTEM_USER.getValue())
                .isEqualTo(1);
    }

    @Test
    public void testConstructor_null() {
        assertThrows(NullPointerException.class, () -> new HsumHelper(null));
    }

    @Test
    public void testGetHsumBootStrategy_notSet() throws Exception {
        mockIntSystemPropertyNotSet(BOOT_STRATEGY_PROPERTY);

        expect.withMessage("getHsumBootStrategy() when property not set")
                .that(mHelper.getHsumBootStrategy())
                .isEqualTo(DEFAULT_BEHAVIOR);
    }

    @Test
    public void testGetHsumBootStrategy_unknownValue() throws Exception {
        mockSystemProperty(BOOT_STRATEGY_PROPERTY, 666);

        var thrown = assertThrows(IllegalStateException.class, () -> mHelper.getHsumBootStrategy());

        expect.withMessage("exception message")
                .that(thrown)
                .hasMessageThat()
                .isEqualTo("Invalid value of property " + BOOT_STRATEGY_PROPERTY + ": 666");
    }

    @Test
    public void testGetHsumBootStrategy_validValues() throws Exception {
        for (var expected : HsumBootStrategy.values()) {
            int propValue = expected.getValue();
            mockSystemProperty(BOOT_STRATEGY_PROPERTY, propValue);
            var actual = mHelper.getHsumBootStrategy();
            expect.withMessage("getHsumBootStrategy() for property %s", propValue)
                    .that(actual)
                    .isEqualTo(expected);
        }
    }

    @Test
    public void testSetHsumBootStrategy_null() {
        assertThrows(NullPointerException.class, () -> mHelper.setHsumBootStrategy(null));
    }

    @Test
    public void testSetHsumBootStrategy_validValues() throws Exception {
        for (var strategy : HsumBootStrategy.values()) {
            mHelper.setHsumBootStrategy(strategy);

            expectPropertySet(BOOT_STRATEGY_PROPERTY, String.valueOf(strategy.getValue()));
        }
    }

    private void mockSystemProperty(String name, long value) throws DeviceNotAvailableException {
        when(mMockDevice.getIntProperty(eq(name), anyLong())).thenReturn(value);
    }

    private void mockIntSystemPropertyNotSet(String name) throws DeviceNotAvailableException {
        when(mMockDevice.getIntProperty(eq(name), anyLong()))
                .thenAnswer(
                        invocation -> {
                            return (Long) invocation.getArguments()[1];
                        });
    }

    private void expectPropertySet(String name, String value) {
        try {
            verify(mMockDevice).setProperty(name, value);
        } catch (Throwable t) {
            expect.withMessage("expectPropertySet(%s, %s) failed: %s", name, value, t).fail();
        }
    }
}
