/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.extras.retrofit;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.RequestBuilder;

import java.util.function.Consumer;

/**
 * {@link AsyncHttpClient} implementation of Retrofit2 {@link Call.Factory}
 */
@Value
@Builder(toBuilder = true)
public class AsyncHttpClientCallFactory implements Call.Factory {
    /**
     * {@link AsyncHttpClient} in use.
     */
    @NonNull
    AsyncHttpClient httpClient;

    /**
     * Consumer that gets called just before actual HTTP request is being fired.
     */
    Consumer<Request> onRequestStart;

    /**
     * Consumer that gets called when HTTP request finishes with an exception.
     */
    Consumer<Throwable> onRequestFailure;

    /**
     * Consumer that gets called when HTTP request finishes successfully.
     */
    Consumer<Response> onRequestSuccess;

    /**
     * <p>Request customizer that is being invoked just before async-http-client request is being built.</p>
     * <p><b>NOTE:</b> You should NOT keep reference to request builder or related collections.</p>
     */
    Consumer<RequestBuilder> requestCustomizer;

    @Override
    public Call newCall(Request request) {
        return AsyncHttpClientCall.builder()
                .httpClient(httpClient)
                .onRequestStart(getOnRequestStart())
                .onRequestSuccess(getOnRequestSuccess())
                .onRequestFailure(getOnRequestFailure())
                .requestCustomizer(getRequestCustomizer())
                .request(request)
                .build();
    }
}
