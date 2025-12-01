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
package com.android.tradefed.result.resultdb;

import com.google.common.base.Strings;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A gRPC client interceptor that handles ResultDB update tokens.
 *
 * <p>It attaches the current update token to outgoing requests and extracts any new update token
 * from incoming response headers for specific RPCs.
 */
public class UpdateTokenInterceptor implements ClientInterceptor {

    // The key for the update token used to create/update resources under a ResultDB invocation.
    private static final Metadata.Key<String> UPDATE_TOKEN_METADATA_KEY =
            Metadata.Key.of("update-token", Metadata.ASCII_STRING_MARSHALLER);

    private final Supplier<String> mTokenSupplier;
    private final Consumer<String> mTokenConsumer;
    private final String mUpdateTokenRpc;

    /**
     * Constructor for UpdateTokenInterceptor.
     *
     * @param tokenSupplier A supplier to get the current update token.
     * @param tokenConsumer A consumer to set the update token if a new one is received.
     * @param updateTokenRpc A list of RPC method names that may return an update token in headers.
     */
    public UpdateTokenInterceptor(
            Supplier<String> tokenSupplier, Consumer<String> tokenConsumer, String updateTokenRpc) {
        this.mTokenSupplier = tokenSupplier;
        this.mTokenConsumer = tokenConsumer;
        this.mUpdateTokenRpc = updateTokenRpc;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(delegate) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String currentToken = mTokenSupplier.get();
                if (!Strings.isNullOrEmpty(currentToken)) {
                    // Add update token to request header.
                    headers.put(UPDATE_TOKEN_METADATA_KEY, currentToken);
                }

                super.start(
                        new SimpleForwardingClientCallListener<RespT>(responseListener) {
                            @Override
                            public void onHeaders(Metadata headers) {
                                String fullMethodName = method.getFullMethodName();
                                if (Objects.equals(mUpdateTokenRpc, fullMethodName)) {
                                    String newToken = headers.get(UPDATE_TOKEN_METADATA_KEY);
                                    if (!Strings.isNullOrEmpty(newToken)) {
                                        // Retrieve the update token from the
                                        // response header.
                                        mTokenConsumer.accept(newToken);
                                    }
                                }
                                super.onHeaders(headers);
                            }
                        },
                        headers);
            }
        };
    }
}
