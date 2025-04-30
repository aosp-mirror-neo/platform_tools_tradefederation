/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.google.android.tradefed.result.teststorage;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.MultiMap;

import com.google.api.services.androidbuildinternal.v3.model.Property;
import com.google.common.collect.ImmutableList;

import java.util.List;

final class InvocationProperties {

    private static final String PROPERTY_ATTR = "invocation-property";

    private InvocationProperties() {}

    /**
     * Parses AnTS invocation properties from an invocation context. Properties are stored in
     * invocation-property attribute in the "key:value" format.
     */
    public static ImmutableList<Property> parse(IInvocationContext context) {
        ImmutableList.Builder<Property> builder = ImmutableList.builder();
        MultiMap<String, String> attributes = context.getAttributes();
        if (attributes == null || !attributes.containsKey(PROPERTY_ATTR)) {
            return builder.build();
        }
        List<String> properties = attributes.get(PROPERTY_ATTR);
        for (String property : properties) {
            Property p = parse(property);
            if (isValid(p)) {
                builder.add(p);
            } else {
                CLog.i("Invalid property: %s. Format must be \"name:value\"", property);
            }
        }
        return builder.build();
    }

    private static Property parse(String property) {
        return new Property().setName(name(property)).setValue(value(property));
    }

    private static String name(String property) {
        int index = property.indexOf(':');
        if (index == -1) {
            return "";
        }
        return property.substring(0, index);
    }

    private static String value(String property) {
        int index = property.indexOf(':');
        if (index == -1 || index == property.length() - 1) {
            return "";
        }
        return property.substring(index + 1);
    }

    private static boolean isValid(Property p) {
        return !p.getName().isEmpty() && !p.getValue().isEmpty();
    }
}
