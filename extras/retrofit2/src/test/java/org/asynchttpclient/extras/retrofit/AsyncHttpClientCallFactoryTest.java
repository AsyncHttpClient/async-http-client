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

import java.util.function.Consumer;

import static org.mockito.Mockito.mock;

public class AsyncHttpClientCallFactoryTest {
    @Test
    void newCallShouldProduceExpectedResult() {
        // given
        val request = new Request.Builder().url("http://www.google.com/").build();
        val httpClient = mock(AsyncHttpClient.class);

        Consumer<Request> onRequestStart = createConsumer();
        Consumer<Throwable> onRequestFailure = createConsumer();
        Consumer<Response> onRequestSuccess = createConsumer();
        Consumer<RequestBuilder> requestCustomizer = createConsumer();

        // when
        val factory = AsyncHttpClientCallFactory.builder()
                .httpClient(httpClient)
                .onRequestStart(onRequestStart)
                .onRequestFailure(onRequestFailure)
                .onRequestSuccess(onRequestSuccess)
                .requestCustomizer(requestCustomizer)
                .build();

        // then
        Assert.assertTrue(factory.getHttpClient() == httpClient);
        Assert.assertTrue(factory.getOnRequestStart() == onRequestStart);
        Assert.assertTrue(factory.getOnRequestFailure() == onRequestFailure);
        Assert.assertTrue(factory.getOnRequestSuccess() == onRequestSuccess);
        Assert.assertTrue(factory.getRequestCustomizer() == requestCustomizer);

        // when
        val call = (AsyncHttpClientCall) factory.newCall(request);

        // then
        Assert.assertNotNull(call);
        Assert.assertTrue(call.request() == request);
        Assert.assertTrue(call.getHttpClient() == httpClient);
        Assert.assertTrue(call.getOnRequestStart() == onRequestStart);
        Assert.assertTrue(call.getOnRequestFailure() == onRequestFailure);
        Assert.assertTrue(call.getOnRequestSuccess() == onRequestSuccess);
        Assert.assertTrue(call.getRequestCustomizer() == requestCustomizer);
    }

    private <T> Consumer<T> createConsumer() {
        return e -> {
        };
    }
}
