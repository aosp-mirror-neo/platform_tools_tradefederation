/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.tradefed.account;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.text.TextUtils;

/**
 * An instrumentation utility to check for the existence of a Google account on the device using adb
 * shell am instrument -w com.google.android.tradefed.account/.HasAccount
 */
public class HasAccount extends Instrumentation {

    static final String GOOGLE_ACCOUNT = "com.google";

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        super.onStart();

        Bundle results = new Bundle();

        Account[] accounts = AccountManager.get(getContext()).getAccounts();
        for (int i = 0; i < accounts.length; ++i) {
            if (accounts[i].type.equals(GOOGLE_ACCOUNT)) {
                results.putString("result", "SUCCESS");
                finish(Activity.RESULT_OK, results);
                return;
            }
        }
        String errorMsg =
                String.format(
                        "Did not find a registered Google account in the list: %s",
                        TextUtils.join(", ", accounts));
        results.putString("error", errorMsg);
        finish(Activity.RESULT_CANCELED, results);
    }
}
