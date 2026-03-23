/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.google.android.tradefed.util.androidbuildapi;

import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.host.HostOptions;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.GoogleApiClientUtil;

import com.google.android.ci.build.shadow.api.gax.core.FixedCredentialsProvider;
import com.google.android.ci.build.v4.BranchServiceClient;
import com.google.android.ci.build.v4.BranchServiceSettings;
import com.google.android.ci.build.v4.BuildArtifactServiceClient;
import com.google.android.ci.build.v4.BuildArtifactServiceSettings;
import com.google.android.ci.build.v4.BuildServiceClient;
import com.google.android.ci.build.v4.BuildServiceSettings;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.androidbuildinternal.v3.Androidbuildinternal;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Collections;

/** Helper method used to create an Android Build client. */
public class AndroidBuildAPIHelper {
    private static final Collection<String> ACCESS_SCOPE =
            Collections.singleton("https://www.googleapis.com/auth/androidbuild.internal");

    public static final String BUILD_ENV_INVOCATION_CONTEXT_KEY = "android-build-environment";

    /** This is the key for {@link HostOptions}'s service-account-json-key-file option. */
    private static final String HOST_OPTION_KEY_NAME = "android-build-api-json-key";

    // Time out for android build client v3.
    private static final int CLIENT_V3_TIMEOUT = 3 * 60000; // 3 minutes

    private static String getEnvServicePath(String environment) {
        if (environment.equals("treehugger")) {
            return "android/internal/build/v2beta1_qa_treehugger";
        } else if (environment.equals("atp")) {
            return "android/internal/build/v2beta1_qa_atp";
        } else {
            return "android/internal/build/v2beta1";
        }
    }

    private static String getEnvServicePathV3(String environment) {
        if (environment.equals("treehugger")) {
            return "android/internal/build/v3_qa_treehugger";
        } else if (environment.equals("atp")) {
            return "android/internal/build/v3_qa_atp";
        } else {
            return "android/internal/build/v3";
        }
    }

    private static String getEnvRootUrl(String environment) {
        if (environment.equals("build")) {
            return "https://www-googleapis-staging.sandbox.google.com/";
        } else {
            return "https://www.googleapis.com/";
        }
    }

    /** Create V2 version of client */
    public static com.google.api.services.androidbuildinternal.Androidbuildinternal getLegacyClient(
            String environment) throws GeneralSecurityException, IOException {
        // Avoid querying the build API options several times
        AndroidBuildAPIOptions abOptions = getAndroidBuildAPIOptions();
        com.google.api.services.androidbuildinternal.Androidbuildinternal client = null;
        if (environment == null) {
            environment = abOptions.getEnvironment();
            CLog.w(
                    "No environment was specified. Defaulting to '%s' environment configuration.",
                    environment);
        }
        String rootUrl = getEnvRootUrl(environment);
        String servicePath = getEnvServicePath(environment);
        CLog.i("Creating client with base URL: %s%s", rootUrl, servicePath);
        Credentials credential = createCredential();
        HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credential);
        client =
                new com.google.api.services.androidbuildinternal.Androidbuildinternal.Builder(
                                GoogleNetHttpTransport.newTrustedTransport(),
                                GsonFactory.getDefaultInstance(),
                                GoogleApiClientUtil.configureRetryStrategyAndTimeout(
                                        requestInitializer,
                                        abOptions.getConnectTimeout(),
                                        abOptions.getReadTimeout()))
                        .setApplicationName(GoogleApiClientUtil.APP_NAME)
                        .setRootUrl(rootUrl)
                        .setServicePath(servicePath)
                        .build();
        return client;
    }

    // FIXME: Pass the invocation context instead of the environment string, so that logic for
    // parsing the environment is not spread in multiple classes.
    public static Androidbuildinternal getClient(String environment)
            throws GeneralSecurityException, IOException {
        return getClient(environment, createCredential());
    }

    private static Androidbuildinternal getClient(String environment, Credentials credential)
            throws GeneralSecurityException, IOException {
        // Avoid querying the build API options several times
        AndroidBuildAPIOptions abOptions = getAndroidBuildAPIOptions();
        Androidbuildinternal client = null;
        if (environment == null) {
            environment = abOptions.getEnvironment();
            CLog.w(
                    "No environment was specified. Defaulting to '%s' environment configuration.",
                    environment);
        }
        String rootUrl = getEnvRootUrl(environment);
        String servicePath = getEnvServicePathV3(environment);
        CLog.i("Creating client with base URL: %s%s", rootUrl, servicePath);
        HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credential);
        client =
                new Androidbuildinternal.Builder(
                                GoogleNetHttpTransport.newTrustedTransport(),
                                GsonFactory.getDefaultInstance(),
                                GoogleApiClientUtil.configureRetryStrategyAndTimeout(
                                        requestInitializer,
                                        abOptions.getConnectTimeout(),
                                        abOptions.getReadTimeout()))
                        .setApplicationName(GoogleApiClientUtil.APP_NAME)
                        .setRootUrl(rootUrl)
                        .setServicePath(servicePath)
                        .build();
        return client;
    }

    private static Credentials createCredential()
            throws FileNotFoundException, IOException, GeneralSecurityException {

        AndroidBuildAPIOptions abOptions = getAndroidBuildAPIOptions();
        if (abOptions != null
                && abOptions.getAuthTokenFile() != null
                && abOptions.getAuthTokenFile().exists()) {
            String accessToken = FileUtil.readStringFromFile(abOptions.getAuthTokenFile());
            return GoogleCredentials.create(new AccessToken(accessToken, null));
        }

        File abOptionJsonKey = null;
        if (abOptions != null) {
            abOptionJsonKey = abOptions.getJsonKeyFile();
        }
        return GoogleApiClientUtil.createCredential(
                ACCESS_SCOPE, true, abOptionJsonKey, HOST_OPTION_KEY_NAME);
    }

    public static com.google.api.services.androidbuildinternal.v3.Androidbuildinternal
            getTestStorageClient(String rootUrl, String servicePath)
                    throws FileNotFoundException, IOException, GeneralSecurityException {
        Credentials credential = createCredential();
        HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credential);
        com.google.api.services.androidbuildinternal.v3.Androidbuildinternal client =
                new com.google.api.services.androidbuildinternal.v3.Androidbuildinternal.Builder(
                                GoogleNetHttpTransport.newTrustedTransport(),
                                GsonFactory.getDefaultInstance(),
                                GoogleApiClientUtil.configureRetryStrategy(
                                        GoogleApiClientUtil.setHttpTimeout(
                                                requestInitializer,
                                                CLIENT_V3_TIMEOUT,
                                                CLIENT_V3_TIMEOUT)))
                        .setApplicationName(GoogleApiClientUtil.APP_NAME)
                        .setRootUrl(rootUrl)
                        .setServicePath(servicePath)
                        .build();
        return client;
    }

    public static com.google.api.services.androidbuildinternal.v3.Androidbuildinternal
            getTestStorageClient(String environment)
                    throws FileNotFoundException, IOException, GeneralSecurityException {
        return getTestStorageClient(getEnvRootUrl(environment), getEnvServicePathV3(environment));
    }

    public static AndroidBuildAPIOptions getAndroidBuildAPIOptions() {
        AndroidBuildAPIOptions options =
                (AndroidBuildAPIOptions)
                        GlobalConfiguration.getInstance()
                                .getConfigurationObject(AndroidBuildAPIOptions.TYPE_NAME);
        if (options == null) {
            CLog.w("No 'android-build' global object was configured. Using default one.");
            // Return default values if object is not configured
            options = new AndroidBuildAPIOptions();
        }
        return options;
    }

    public static BuildServiceClient getV4BuildClient(String environment)
            throws IOException, GeneralSecurityException {
        com.google.android.ci.build.shadow.auth.oauth2.GoogleCredentials credentials =
                createV4Credential();
        BuildServiceSettings.Builder builder = BuildServiceSettings.newHttpJsonBuilder();
        if (credentials != null) {
            builder.setCredentialsProvider(FixedCredentialsProvider.create(credentials));
        }
        return BuildServiceClient.create(builder.build());
    }

    public static BranchServiceClient getV4BranchClient(String environment)
            throws IOException, GeneralSecurityException {
        com.google.android.ci.build.shadow.auth.oauth2.GoogleCredentials credentials =
                createV4Credential();
        BranchServiceSettings.Builder builder = BranchServiceSettings.newHttpJsonBuilder();
        if (credentials != null) {
            builder.setCredentialsProvider(FixedCredentialsProvider.create(credentials));
        }
        return BranchServiceClient.create(builder.build());
    }

    public static BuildArtifactServiceClient getV4BuildArtifactClient(String environment)
            throws IOException, GeneralSecurityException {
        com.google.android.ci.build.shadow.auth.oauth2.GoogleCredentials credentials =
                createV4Credential();
        BuildArtifactServiceSettings.Builder builder =
                BuildArtifactServiceSettings.newHttpJsonBuilder();
        if (credentials != null) {
            builder.setCredentialsProvider(FixedCredentialsProvider.create(credentials));
        }
        return BuildArtifactServiceClient.create(builder.build());
    }

    private static com.google.android.ci.build.shadow.auth.oauth2.GoogleCredentials
            createV4Credential() throws IOException {
        AndroidBuildAPIOptions abOptions = getAndroidBuildAPIOptions();
        File abOptionJsonKey = null;
        if (abOptions != null) {
            abOptionJsonKey = abOptions.getJsonKeyFile();
        }
        if (abOptionJsonKey != null && abOptionJsonKey.exists()) {
            return com.google.android.ci.build.shadow.auth.oauth2.GoogleCredentials.fromStream(
                            new java.io.FileInputStream(abOptionJsonKey))
                    .createScoped(ACCESS_SCOPE);
        }
        return com.google.android.ci.build.shadow.auth.oauth2.GoogleCredentials
                .getApplicationDefault()
                .createScoped(ACCESS_SCOPE);
    }
}
