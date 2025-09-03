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

import static com.android.tradefed.device.HsumHelper.HsumBootStrategy.DEFAULT_BEHAVIOR;

import com.android.annotations.VisibleForTesting;

import java.util.Objects;

/** Provides utilities for HSUM (Headless System User Mode) tasks */
public final class HsumHelper {

    // Copied from UserManagerService
    @VisibleForTesting
    static final String BOOT_STRATEGY_PROPERTY = "persist.user.hsum_boot_strategy";

    private final ITestDevice mDevice;

    public HsumHelper(ITestDevice device) {
        mDevice = Objects.requireNonNull(device, "device cannot be null");
    }

    /**
     * Gets the device's {@link HsumBootStrategy}.
     *
     * @throws IllegalStateException if the current strategy is set to an invalid value.
     */
    public HsumBootStrategy getHsumBootStrategy() throws DeviceNotAvailableException {
        var value = mDevice.getIntProperty(BOOT_STRATEGY_PROPERTY, DEFAULT_BEHAVIOR.mValue);
        for (var strategy : HsumBootStrategy.values()) {
            if (strategy.mValue == value) {
                return strategy;
            }
        }
        throw new IllegalStateException(
                "Invalid value of property " + BOOT_STRATEGY_PROPERTY + ": " + value);
    }

    /** Sets the device's {@link HsumBootStrategy} */
    public void setHsumBootStrategy(HsumBootStrategy strategy) throws DeviceNotAvailableException {
        Objects.requireNonNull(strategy, "strategy cannot be null");
        mDevice.setProperty(BOOT_STRATEGY_PROPERTY, String.valueOf(strategy.mValue));
    }

    /* Defines which user the Android system switches to on boot. */
    public enum HsumBootStrategy {

        // NOTE: values were copied from UMS (UserManagerService)

        /**
         * Uses the default strategy provided by the device configuration.
         *
         * <p>This is the value tests should mostly use on cleanup tasks.
         */
        DEFAULT_BEHAVIOR(-1), // UMS.BOOT_STRATEGY_DO_NOT_OVERRIDE

        /* Switches to the previous (before boot) current user . */
        SWITCH_TO_PREVIOUS_USER(0), // UMS.BOOT_STRATEGY_TO_PREVIOUS_OR_FIRST_SWITCHABLE_USER

        /** Doesn't switch. */
        STAY_ON_SYSTEM_USER(1); // UMS.BOOT_STRATEGY_TO_HSU_FOR_PROVISIONED_DEVICE

        private final int mValue;

        HsumBootStrategy(int value) {
            mValue = value;
        }

        @VisibleForTesting
        int getValue() {
            return mValue;
        }
    }
}
