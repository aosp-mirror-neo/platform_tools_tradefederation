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
package com.android.tradefed.result.resultdb.utils;

import com.android.resultdb.proto.StringPair;
import com.android.resultdb.proto.TestResultProperties;

import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;

import javax.annotation.Nullable;

/**
 * Provides methods to facilitate uploading Android-specific fields to ResultDB.
 *
 * <p>This class is copied from ResultDB codebase for compatibility. Some functions are removed due
 * to lack of dependencies.
 */
public final class AndroidCompatibility {

    /**
     * The tag key used to record an error type for the primary error (failure_reason.errors[0]).
     */
    public static final String PRIMARY_ERROR_TYPE_TAG_KEY = "primary_error_type";

    /**
     * The tag key used to record an error identifier (e.g. OUT_OF_QUOTA, MOBLY_TEST_CASE_ERROR,
     * EXPECTED_TESTS_MISMATCH) associated with the primary error (failure_reason.errors[0]).
     */
    public static final String PRIMARY_ERROR_NAME_TAG_KEY = "primary_error_name";

    /**
     * The tag key used to record an error code associated with the error name associated with the
     * primary error (failure_reason.errors[0]).
     */
    public static final String PRIMARY_ERROR_CODE_TAG_KEY = "primary_error_code";

    /**
     * The tag key used to record the fully qualified Java class name that created and threw the
     * exception associated with the primary error (failure_reason.errors[0]).
     */
    public static final String PRIMARY_ERROR_ORIGIN_TAG_KEY = "primary_error_origin";

    /** The tag key used to store the condition that caused the test to be skipped. */
    public static final String SKIP_TRIGGER_TAG_KEY = "skip_trigger";

    /**
     * The tag key used to store the buganizer ID for the issue that caused the tests to be skipped.
     */
    public static final String SKIP_BUG_TAG_KEY = "skip_bug_id";

    /** The maximum size of a tag key in bytes. */
    public static final int MAX_TAG_KEY_BYTES = 64;

    private AndroidCompatibility() {}

    /**
     * Marshals a TestResultProperties proto (a copy of
     * wireless.android.busytown.proto.TestResultProperties) to a google.protobuf.Struct for storage
     * in ResultDB.
     */
    @Nullable
    public static Struct marshalTestResultProperties(@Nullable TestResultProperties properties)
            throws InvalidProtocolBufferException {
        Struct.Builder structBuilder = Struct.newBuilder();
        if (properties != null) {
            JsonFormat.parser()
                    .merge(
                            JsonFormat.printer()
                                    .omittingInsignificantWhitespace()
                                    .print(properties),
                            structBuilder);
        }
        putStructType(structBuilder, "wireless.android.busytown.proto.TestResultProperties");
        return structBuilder.build();
    }

    private static void putStructType(Struct.Builder structBuilder, String typeName) {
        structBuilder.putFields(
                "@type",
                Value.newBuilder().setStringValue("type.googleapis.com/" + typeName).build());
    }

    /**
     * MakeValidTagKey converts the key of an AnTS property to a valid ResultDB tag key.
     *
     * <p>This is necessary because ResultDB tag keys are limited to matching
     * ^[a-z][a-z0-9_]*(/[a-z][a-z0-9_]*)*$.
     *
     * <p>Note: ResultDB applies a length limit of 64 bytes to keys. If your keys are longer than
     * this you might have to upload the data via other means to avoid data loss. See
     * go/resultdb-uploader-guidance#ants-field-properties for options.
     */
    public static String makeValidTagKey(String key) {
        if (Strings.isNullOrEmpty(key)) {
            return "empty_key";
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c >= 'a' && c <= 'z') {
                result.append(c);
            } else if (c >= '0' && c <= '9') {
                result.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                result.append((char) (c - 'A' + 'a'));
            } else {
                result.append('_');
            }
        }
        String validKey = result.toString();
        if (!validKey.isEmpty() && validKey.charAt(0) >= '0' && validKey.charAt(0) <= '9') {
            validKey = "num_" + validKey;
        }
        if (validKey.length() > MAX_TAG_KEY_BYTES) {
            throw new IllegalArgumentException(
                    String.format(
                            "Tag key exceeds ResultDB limit of %d bytes: %s, see"
                                    + " go/resultdb-uploader-guidance#ants-field-properties for"
                                    + " options.",
                            MAX_TAG_KEY_BYTES, validKey));
        }
        return validKey;
    }

    /**
     * Returns a StringPair that can be used to record an error identifier (e.g. OUT_OF_QUOTA,
     * MOBLY_TEST_CASE_ERROR, EXPECTED_TESTS_MISMATCH) associated with the primary error
     * (failure_reason.errors[0]).
     */
    public static StringPair primaryErrorNameTag(String name) {
        return StringPair.newBuilder().setKey(PRIMARY_ERROR_NAME_TAG_KEY).setValue(name).build();
    }

    /**
     * Returns a StringPair that can be used to record an error code associated with the primary
     * error (failure_reason.errors[0]).
     */
    public static StringPair primaryErrorCodeTag(long code) {
        return StringPair.newBuilder()
                .setKey(PRIMARY_ERROR_CODE_TAG_KEY)
                .setValue(String.valueOf(code))
                .build();
    }

    /**
     * Returns a StringPair that can be used to record] the fully qualified java class name that
     * created and threw the exception associated with the primary error (failure_reason.errors[0]).
     */
    public static StringPair primaryErrorOriginTag(String origin) {
        return StringPair.newBuilder()
                .setKey(PRIMARY_ERROR_ORIGIN_TAG_KEY)
                .setValue(origin)
                .build();
    }

    /**
     * Returns a StringPair that can be used to record the condition that caused the test to be
     * skipped.
     */
    public static StringPair skipTriggerTag(String trigger) {
        return StringPair.newBuilder().setKey(SKIP_TRIGGER_TAG_KEY).setValue(trigger).build();
    }

    /**
     * Returns a StringPair that can be used to record the buganizer ID for the issue that caused
     * the tests to be skipped.
     */
    public static StringPair skipBugTag(String bugId) {
        return StringPair.newBuilder().setKey(SKIP_BUG_TAG_KEY).setValue(bugId).build();
    }
}
