/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tradefed.util.avd;

import com.android.tradefed.log.LogUtil.CLog;

import com.google.android_cuttlefish.base.cvd.cuttlefish.host.commands.cvd.cli.parser.CvdConfig.EnvironmentSpecification;
import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Java implementation of Cuttlefish Host Orchestator API.
 *
 * <p>- Endpoints:
 * https://github.com/google/android-cuttlefish/blob/main/frontend/src/host_orchestrator/orchestrator/controller.go#L56-L102
 * - Objects:
 * https://github.com/google/android-cuttlefish/blob/main/frontend/src/host_orchestrator/api/v1/messages.go
 */
public class HostOrchestratorClient {

    // https://github.com/google/android-cuttlefish/blob/main/frontend/src/host_orchestrator/api/v1/messages.go#L104
    public static final class Operation {
        public String name;
        public boolean done;
    }

    public static final class Cvd {
        public String group;
        public String name;
        public String status;
    }

    public static final class CreateCvdRequest {
        public EnvironmentSpecification env_config;

        CreateCvdRequest(EnvironmentSpecification specs) {
            env_config = specs;
        }
    }

    public static final class ListCvdsResponse {
        public List<Cvd> cvds;
    }

    public static final class CreateSnapshotRequest {
        // Snapshot request must be as a json like {"snapshot_id" : "string"}.
        public String snapshot_id;

        CreateSnapshotRequest(String id) {
            snapshot_id = id;
        }
    }

    public static final class StartCVDRequest {
        // Snapshot request must be as a json like {"snapshot_id" : "string"}.
        public String snapshot_id;

        StartCVDRequest(String id) {
            snapshot_id = id;
        }
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/api/v1/messages.go#L176
    public static final class DisplayAddRequest {

        public int width;
        public int height;
        public int dpi;
        public int refresh_rate_hz;

        DisplayAddRequest(int width, int height, int dpi, int refreshRateHz) {
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.refresh_rate_hz = refreshRateHz;
        }
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/api/v1/messages.go#L183
    public static final class DisplayAddResponse {

        public int display_number;
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/api/v1/messages.go#L187
    public static final class DisplayMode {
        public List<Integer> windowed;
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/api/v1/messages.go#L191
    public static final class Display {
        public List<Integer> dpi;
        public DisplayMode mode;
        public int refresh_rate_hz;
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/api/v1/messages.go#L197
    public static final class DisplayListResponse {
        public Map<Integer, Display> displays;
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/api/v1/messages.go#L207
    public static final class DisplayScreenshotResponse {
        public String screenshot_bytes;
        public String screenshot_mime_type;
    }

    public static HttpRequest buildCreateCvdRequest(String baseURL, CreateCvdRequest request) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/cvds", baseURL)))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(new Gson().toJson(request)))
                .build();
    }

    public static HttpRequest buildListCvdsRequest(String baseURL) {
        return HttpRequest.newBuilder().uri(URI.create(String.format("%s/cvds", baseURL))).build();
    }

    // https://github.com/google/android-cuttlefish/blob/fff7e3487c924435e6f6120345edf1dddb49d50b/frontend/src/host_orchestrator/orchestrator/controller.go#L78
    public static HttpRequest buildGetOperationRequest(String baseURL, String name) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/operations/%s", baseURL, name)))
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/fff7e3487c924435e6f6120345edf1dddb49d50b/frontend/src/host_orchestrator/orchestrator/controller.go#L82
    public static HttpRequest buildGetOperationResultRequest(String baseURL, String name) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/operations/%s/result", baseURL, name)))
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/fff7e3487c924435e6f6120345edf1dddb49d50b/frontend/src/host_orchestrator/orchestrator/controller.go#L69
    public static HttpRequest buildCreateBugreportRequest(String baseURL, String group) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/cvds/%s/:bugreport", baseURL, group)))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/fff7e3487c924435e6f6120345edf1dddb49d50b/frontend/src/host_orchestrator/orchestrator/controller.go#L75
    public static HttpRequest buildPowerwashRequest(String baseURL, String group, String name) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/cvds/%s/%s/:powerwash", baseURL, group, name)))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/fff7e3487c924435e6f6120345edf1dddb49d50b/frontend/src/host_orchestrator/orchestrator/controller.go#L71
    public static HttpRequest buildRemoveInstanceRequest(
            String baseURL, String group, String name) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/cvds/%s/%s", baseURL, group, name)))
                .DELETE()
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/fff7e3487c924435e6f6120345edf1dddb49d50b/frontend/src/host_orchestrator/orchestrator/controller.go#L73
    public static HttpRequest buildStopInstanceRequest(String baseURL, String group, String name) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/cvds/%s/%s/:stop", baseURL, group, name)))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/main/frontend/src/host_orchestrator/orchestrator/controller.go#L89
    public static HttpRequest buildCreateSnapshotRequest(
            String baseURL, String group, String name, CreateSnapshotRequest request) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/cvds/%s/%s/snapshots", baseURL, group, name)))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(new Gson().toJson(request)))
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/main/frontend/src/host_orchestrator/orchestrator/controller.go#L107
    public static HttpRequest buildDeleteSnapshotRequest(String baseURL, String snapshotId) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/snapshots/%s", baseURL, snapshotId)))
                .DELETE()
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/fff7e3487c924435e6f6120345edf1dddb49d50b/frontend/src/host_orchestrator/orchestrator/controller.go#L65
    public static HttpRequest buildStartCvdRequest(
            String baseURL, String group, String name, StartCVDRequest request) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/cvds/%s/%s/:start", baseURL, group, name)))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(new Gson().toJson(request)))
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/orchestrator/controller.go#L91
    public static HttpRequest buildDisplayAddRequest(
            String baseURL, String group, String name, DisplayAddRequest request) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/cvds/%s/%s/displays", baseURL, group, name)))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(new Gson().toJson(request)))
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/orchestrator/controller.go#L93
    public static HttpRequest buildDisplayListRequest(String baseURL, String group, String name) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/cvds/%s/%s/displays", baseURL, group, name)))
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/orchestrator/controller.go#L95
    public static HttpRequest buildDisplayRemoveRequest(
            String baseURL, String group, String name, int displayNumber) {
        return HttpRequest.newBuilder()
                .uri(
                        URI.create(
                                String.format(
                                        "%s/cvds/%s/%s/displays/%d",
                                        baseURL, group, name, displayNumber)))
                .DELETE()
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/613f81afb382cf7fb8b0d8793b9a4f0ab0986981/frontend/src/host_orchestrator/orchestrator/controller.go#L97
    public static HttpRequest buildDisplayScreenshotRequest(
            String baseURL, String group, String name, int displayNumber) {
        return HttpRequest.newBuilder()
                .uri(
                        URI.create(
                                String.format(
                                        "%s/cvds/%s/%s/displays/%d/:screenshot",
                                        baseURL, group, name, displayNumber)))
                .build();
    }

    public static interface IHoHttpClient {
        HttpResponse<String> send(HttpRequest request)
                throws IOException, InterruptedException, ErrorResponseException;

        HttpResponse<Path> send(HttpRequest request, Path dst)
                throws IOException, InterruptedException, ErrorResponseException;
    }

    public static final class HoHttpClient implements IHoHttpClient {
        private final HttpClient mClient;

        public HoHttpClient() {
            mClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        }

        @Override
        public HttpResponse<String> send(HttpRequest request)
                throws IOException, InterruptedException, ErrorResponseException {
            return mClient.send(request, BodyHandlers.ofString());
        }

        @Override
        public HttpResponse<Path> send(HttpRequest request, Path dst)
                throws IOException, InterruptedException, ErrorResponseException {
            return mClient.send(request, BodyHandlers.ofFile(dst));
        }
    }

    public static final class ErrorResponseException extends Exception {
        private final int mStatusCode;
        private final String mBody;

        public ErrorResponseException(int statusCode, String body) {
            super(
                    String.format(
                            "error response with status code: %d, response body: %s",
                            statusCode, body));
            mStatusCode = statusCode;
            mBody = body;
        }

        public int getStatusCode() {
            return mStatusCode;
        }

        public String getBody() {
            return mBody;
        }
    }

    public static <T> T sendRequest(
        IHoHttpClient client, HttpRequest request, Class<T> responseClass)
            throws IOException, InterruptedException, ErrorResponseException {
        HttpResponse<String> res = client.send(request);
        if (res.statusCode() != 200) {
            throw new ErrorResponseException(res.statusCode(), res.body());
        }
        return new Gson().fromJson(res.body(), responseClass);
    }

    public static void saveToFile(IHoHttpClient client, HttpRequest request, Path dst)
            throws IOException, InterruptedException, ErrorResponseException {
        HttpResponse<Path> res = client.send(request, dst);
        if (res.statusCode() != 200) {
            throw new ErrorResponseException(res.statusCode(), "");
        }
        CLog.i("Response body for \"%s\" successfully saved to \"%s\"", request.uri(), dst);
    }
}
