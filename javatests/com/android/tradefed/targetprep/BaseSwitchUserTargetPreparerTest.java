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

import static com.android.tradefed.targetprep.BaseSwitchUserTargetPreparer.PROPERTY_PREPARED_USER;

import com.android.tradefed.invoker.TestInformation;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;

public final class BaseSwitchUserTargetPreparerTest {

    @Rule public final Expect expect = Expect.create();

    private final TestInformation mTestInformation = TestInformation.newBuilder().build();

    @Test
    public void testGetPreparedUser() {
        var preparer = new BaseSwitchUserTargetPreparer() {};

        expect.withMessage("getPreparedUserId() initially")
                .that(preparer.getPreparedUserId())
                .isNull();
    }

    @Test
    public void testSetPreparedUser() {
        var preparer = new BaseSwitchUserTargetPreparer() {};

        // Set first
        preparer.setPreparedUser(mTestInformation, 42);

        expect.withMessage("getPreparedUserId() after setPreparedUser(42)")
                .that(preparer.getPreparedUserId())
                .isEqualTo(42);
        var properties = mTestInformation.properties();
        boolean propertySet = properties.containsKey(PROPERTY_PREPARED_USER);
        expect.withMessage("property %s set", PROPERTY_PREPARED_USER).that(propertySet).isTrue();
        if (propertySet) {
            expect.withMessage("value of property %s", PROPERTY_PREPARED_USER)
                    .that(properties.get(PROPERTY_PREPARED_USER))
                    .isEqualTo("42");
        }

        // Then reset
        preparer.setPreparedUser(mTestInformation, /* userId= */ null);
        expect.withMessage("getPreparedUserId() after setPreparedUser(null)")
                .that(preparer.getPreparedUserId())
                .isNull();
        expect.withMessage("property %s reset", PROPERTY_PREPARED_USER)
                .that(properties.containsKey(PROPERTY_PREPARED_USER))
                .isFalse();
    }
}
