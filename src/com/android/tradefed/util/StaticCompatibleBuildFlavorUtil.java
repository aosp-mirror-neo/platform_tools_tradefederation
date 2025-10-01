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
import java.util.List;

/**
 * A utility class that loads StaticCompatibleBuildFlavor from classpath and provides access to the
 * methods.
 */
public class StaticCompatibleBuildFlavorUtil {
    private static final String STATIC_COMPATIBLE_BUILD_FLAVOR_CLASS =
            "com.google.android.tradefed.device.StaticCompatibleBuildFlavor";

    /**
     * Method to return the compatible known build flavors associated with the product type if there
     * are several alternatives.
     */
    public static List<String> getPossibleFlavors(String productType) {
        String methodName = "getPossibleFlavors";
        Class<?>[] paramTypes = {String.class};
        Object[] methodArgs = {productType};
        try {
            Class<?> loadedClass = Class.forName(STATIC_COMPATIBLE_BUILD_FLAVOR_CLASS);
            Method method = loadedClass.getDeclaredMethod(methodName, paramTypes);
            return (List<String>) method.invoke(null, methodArgs);
        } catch (ClassNotFoundException e) {
            LogUtil.CLog.d("Failed to load StaticCompatibleBuildFlavor class.");
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            LogUtil.CLog.d(
                    "Failed to load method %s from loaded class %s.",
                    methodName, STATIC_COMPATIBLE_BUILD_FLAVOR_CLASS);
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            LogUtil.CLog.d(
                    "Failed to invoke method %s from loaded class %s.",
                    methodName, STATIC_COMPATIBLE_BUILD_FLAVOR_CLASS);
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
