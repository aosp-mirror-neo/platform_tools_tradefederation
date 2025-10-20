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

package com.android.tradefed.util;

import com.android.tradefed.log.LogUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A utility class that loads BrightnessSetupConfig from classpath and provides access to the
 * methods.
 */
public class BrightnessSetupConfigUtil {
    private static final String BRIGHTNESS_SETUP_CONFIG_CLASS =
            "com.google.android.tradefed.targetprep.brightness.BrightnessSetupConfig";

    /** Method to return the 200 NITS brightness for the given product. */
    public static Integer getBrightnessFor200Nits(String product) {
        String methodName = "getBrightnessFor200Nits";
        Class<?>[] paramTypes = {String.class};
        Object[] methodArgs = {product};
        try {
            Class<?> loadedClass = Class.forName(BRIGHTNESS_SETUP_CONFIG_CLASS);
            Method method = loadedClass.getDeclaredMethod(methodName, paramTypes);
            return (Integer) method.invoke(null, methodArgs);
        } catch (ClassNotFoundException e) {
            LogUtil.CLog.d("Failed to load BrightnessSetupConfig class.");
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            LogUtil.CLog.d(
                    "Failed to load method %s from loaded class %s.",
                    methodName, BRIGHTNESS_SETUP_CONFIG_CLASS);
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            LogUtil.CLog.d(
                    "Failed to invoke method %s from loaded class %s.",
                    methodName, BRIGHTNESS_SETUP_CONFIG_CLASS);
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** Method to return the 100 LUX brightness for the given product. */
    public static Integer getBrightnessFor100Lux(String product) {
        String methodName = "getBrightnessFor100Lux";
        Class<?>[] paramTypes = {String.class};
        Object[] methodArgs = {product};
        try {
            Class<?> loadedClass = Class.forName(BRIGHTNESS_SETUP_CONFIG_CLASS);
            Method method = loadedClass.getDeclaredMethod(methodName, paramTypes);
            return (Integer) method.invoke(null, methodArgs);
        } catch (ClassNotFoundException e) {
            LogUtil.CLog.d("Failed to load BrightnessSetupConfig class.");
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            LogUtil.CLog.d(
                    "Failed to load method %s from loaded class %s.",
                    methodName, BRIGHTNESS_SETUP_CONFIG_CLASS);
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            LogUtil.CLog.d(
                    "Failed to invoke method %s from loaded class %s.",
                    methodName, BRIGHTNESS_SETUP_CONFIG_CLASS);
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
