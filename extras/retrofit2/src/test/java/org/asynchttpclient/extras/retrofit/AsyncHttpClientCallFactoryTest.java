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

import lombok.val;
import okhttp3.Request;
import okhttp3.Response;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.RequestBuilder;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.asynchttpclient.extras.retrofit.AsyncHttpClientCallTest.createConsumer;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertTrue;

public class AsyncHttpClientCallFactoryTest {
    @Test
    void newCallShouldProduceExpectedResult() {
        // given
        val request = new Request.Builder().url("http://www.google.com/").build();
        val httpClient = mock(AsyncHttpClient.class);

        Consumer<Request> onRequestStart = createConsumer(new AtomicInteger());
        Consumer<Throwable> onRequestFailure = createConsumer(new AtomicInteger());
        Consumer<Response> onRequestSuccess = createConsumer(new AtomicInteger());
        Consumer<RequestBuilder> requestCustomizer = createConsumer(new AtomicInteger());

        // first call customizer
        val customizer1Called = new AtomicInteger();
        Consumer<AsyncHttpClientCall.AsyncHttpClientCallBuilder> callBuilderConsumer1 = builder -> {
            builder.onRequestStart(onRequestStart)
                    .onRequestFailure(onRequestFailure)
                    .onRequestSuccess(onRequestSuccess);
            customizer1Called.incrementAndGet();
        };

        // first call customizer
        val customizer2Called = new AtomicInteger();
        Consumer<AsyncHttpClientCall.AsyncHttpClientCallBuilder> callBuilderConsumer2 = builder -> {
            builder.requestCustomizer(requestCustomizer);
            customizer2Called.incrementAndGet();
        };

        // when: create call factory
        val factory = AsyncHttpClientCallFactory.builder()
                .httpClient(httpClient)
                .callCustomizer(callBuilderConsumer1)
                .callCustomizer(callBuilderConsumer2)
                .build();

        // then
        assertTrue(factory.getHttpClient() == httpClient);
        assertTrue(factory.getCallCustomizers().size() == 2);
        assertTrue(customizer1Called.get() == 0);
        assertTrue(customizer2Called.get() == 0);

        // when
        val call = (AsyncHttpClientCall) factory.newCall(request);

        // then
        Assert.assertNotNull(call);
        assertTrue(customizer1Called.get() == 1);
        assertTrue(customizer2Called.get() == 1);

        assertTrue(call.request() == request);
        assertTrue(call.getHttpClient() == httpClient);
        assertTrue(call.getOnRequestStart() == onRequestStart);
        assertTrue(call.getOnRequestFailure() == onRequestFailure);
        assertTrue(call.getOnRequestSuccess() == onRequestSuccess);
        assertTrue(call.getRequestCustomizer() == requestCustomizer);
    }
}
