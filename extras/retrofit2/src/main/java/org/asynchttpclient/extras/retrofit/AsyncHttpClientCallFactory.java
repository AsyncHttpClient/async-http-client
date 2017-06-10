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
import lombok.Singular;
import lombok.Value;
import lombok.val;
import okhttp3.Call;
import okhttp3.Request;
import org.asynchttpclient.AsyncHttpClient;

import java.util.List;
import java.util.function.Consumer;

import static org.asynchttpclient.extras.retrofit.AsyncHttpClientCall.runConsumers;

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
     * List of {@link Call} builder customizers that are invoked just before creating it.
     */
    @Singular("callCustomizer")
    List<Consumer<AsyncHttpClientCall.AsyncHttpClientCallBuilder>> callCustomizers;

    @Override
    public Call newCall(Request request) {
        val callBuilder = AsyncHttpClientCall.builder()
                .httpClient(httpClient)
                .request(request);

        // customize builder before creating a call
        runConsumers(this.callCustomizers, callBuilder);

        // create a call
        return callBuilder.build();
    }
}
