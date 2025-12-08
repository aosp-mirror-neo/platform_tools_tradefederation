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

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.IDeviceFlasher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A utility class that loads StaticDeviceInfo from classpath and provides access to the methods.
 */
public class StaticDeviceInfoUtil {
    private static String mStaticDeviceInfoClass =
            "com.google.android.tradefed.device.StaticDeviceInfo";

    /** Method to fetch the flasher class to use for a given product type/variant */
    public static Class<? extends IDeviceFlasher> getFlasherClass(String type, String variant) {
        String methodName = "getFlasherClass";
        Class<?>[] paramTypes = {String.class, String.class};
        Object[] methodArgs = {type, variant};
        try {
            Class<?> loadedClass = Class.forName(mStaticDeviceInfoClass);
            Method method = loadedClass.getDeclaredMethod(methodName, paramTypes);
            return (Class<? extends IDeviceFlasher>) method.invoke(null, methodArgs);
        } catch (ClassNotFoundException e) {
            CLog.d("Failed to load StaticDeviceInfo class.");
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            CLog.d(
                    "Failed to load method %s from loaded class %s.",
                    methodName, mStaticDeviceInfoClass);
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            CLog.d(
                    "Failed to invoke method %s from loaded class %s.",
                    methodName, mStaticDeviceInfoClass);
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Method to fetch the default LaunchControl build flavor for a given product type/variant
     *
     * @return the build flavor or <code>null</code> if not found
     */
    public static String getDefaultLcFlavor(String type, String variant) {
        String methodName = "getDefaultLcFlavor";
        Class<?>[] paramTypes = {String.class, String.class};
        Object[] methodArgs = {type, variant};
        try {
            Class<?> loadedClass = Class.forName(mStaticDeviceInfoClass);
            Method method = loadedClass.getDeclaredMethod(methodName, paramTypes);
            return (String) method.invoke(null, methodArgs);
        } catch (ClassNotFoundException e) {
            CLog.d("Failed to load StaticDeviceInfo class.");
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            CLog.d(
                    "Failed to load method %s from loaded class %s.",
                    methodName, mStaticDeviceInfoClass);
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            CLog.d(
                    "Failed to invoke method %s from loaded class %s.",
                    methodName, mStaticDeviceInfoClass);
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setStaticDeviceInfoClass(String staticDeviceInfoClass) {
        mStaticDeviceInfoClass = staticDeviceInfoClass;
    }
}
