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

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.RequestBuilder;
import org.testng.annotations.Test;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.asynchttpclient.extras.retrofit.AsyncHttpClientCallTest.createConsumer;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.*;

@Slf4j
public class AsyncHttpClientCallFactoryTest {
  private static final MediaType MEDIA_TYPE = MediaType.parse("application/json");
  private static final String JSON_BODY = "{\"foo\": \"bar\"}";
  private static final RequestBody BODY = RequestBody.create(MEDIA_TYPE, JSON_BODY);
  private static final String URL = "http://localhost:11000/foo/bar?a=b&c=d";
  private static final Request REQUEST = new Request.Builder()
          .post(BODY)
          .addHeader("X-Foo", "Bar")
          .url(URL)
          .build();
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
    assertNotNull(call);
    assertTrue(customizer1Called.get() == 1);
    assertTrue(customizer2Called.get() == 1);

    assertTrue(call.request() == request);
    assertTrue(call.getHttpClient() == httpClient);

    assertEquals(call.getOnRequestStart().get(0), onRequestStart);
    assertEquals(call.getOnRequestFailure().get(0), onRequestFailure);
    assertEquals(call.getOnRequestSuccess().get(0), onRequestSuccess);
    assertEquals(call.getRequestCustomizers().get(0), requestCustomizer);
  }

  @Test
  void shouldApplyAllConsumersToCallBeingConstructed() {
    // given
    val httpClient = mock(AsyncHttpClient.class);

    val rewriteUrl = "http://foo.bar.com/";
    val headerName = "X-Header";
    val headerValue = UUID.randomUUID().toString();

    val numCustomized = new AtomicInteger();
    val numRequestStart = new AtomicInteger();
    val numRequestSuccess = new AtomicInteger();
    val numRequestFailure = new AtomicInteger();

    Consumer<RequestBuilder> requestCustomizer = requestBuilder -> {
      requestBuilder.setUrl(rewriteUrl)
              .setHeader(headerName, headerValue);
      numCustomized.incrementAndGet();
    };

    Consumer<AsyncHttpClientCall.AsyncHttpClientCallBuilder> callCustomizer = callBuilder ->
            callBuilder
                    .requestCustomizer(requestCustomizer)
                    .requestCustomizer(rb -> log.warn("I'm customizing: {}", rb))
                    .onRequestSuccess(createConsumer(numRequestSuccess))
                    .onRequestFailure(createConsumer(numRequestFailure))
                    .onRequestStart(createConsumer(numRequestStart));

    // create factory
    val factory = AsyncHttpClientCallFactory.builder()
            .callCustomizer(callCustomizer)
            .httpClient(httpClient)
            .build();

    // when
    val call = (AsyncHttpClientCall) factory.newCall(REQUEST);
    val callRequest = call.createRequest(call.request());

    // then
    assertTrue(numCustomized.get() == 1);
    assertTrue(numRequestStart.get() == 0);
    assertTrue(numRequestSuccess.get() == 0);
    assertTrue(numRequestFailure.get() == 0);

    // let's see whether request customizers did their job
    // final async-http-client request should have modified URL and one
    // additional header value.
    assertEquals(callRequest.getUrl(), rewriteUrl);
    assertEquals(callRequest.getHeaders().get(headerName), headerValue);

    // final call should have additional consumers set
    assertNotNull(call.getOnRequestStart());
    assertTrue(call.getOnRequestStart().size() == 1);

    assertNotNull(call.getOnRequestSuccess());
    assertTrue(call.getOnRequestSuccess().size() == 1);

    assertNotNull(call.getOnRequestFailure());
    assertTrue(call.getOnRequestFailure().size() == 1);

    assertNotNull(call.getRequestCustomizers());
    assertTrue(call.getRequestCustomizers().size() == 2);
  }

  @Test(expectedExceptions = NullPointerException.class,
          expectedExceptionsMessageRegExp = "httpClientSupplier is marked @NonNull but is null")
  void shouldThrowISEIfHttpClientIsNotDefined() {
    // given
    val factory = AsyncHttpClientCallFactory.builder()
            .build();

    // when
    val httpClient = factory.getHttpClient();

    // then
    assertNull(httpClient);
  }

  @Test
  void shouldUseHttpClientInstanceIfSupplierIsNotAvailable() {
    // given
    val httpClient = mock(AsyncHttpClient.class);

    val factory = AsyncHttpClientCallFactory.builder()
            .httpClient(httpClient)
            .build();

    // when
    val usedHttpClient = factory.getHttpClient();

    // then
    assertTrue(usedHttpClient == httpClient);

    // when
    val call = (AsyncHttpClientCall) factory.newCall(REQUEST);

    // then: call should contain correct http client
    assertTrue(call.getHttpClient()== httpClient);
  }

  @Test
  void shouldPreferHttpClientSupplierOverHttpClient() {
    // given
    val httpClientA = mock(AsyncHttpClient.class);
    val httpClientB = mock(AsyncHttpClient.class);

    val factory = AsyncHttpClientCallFactory.builder()
            .httpClient(httpClientA)
            .httpClientSupplier(() -> httpClientB)
            .build();

    // when
    val usedHttpClient = factory.getHttpClient();

    // then
    assertTrue(usedHttpClient == httpClientB);

    // when: try to create new call
    val call = (AsyncHttpClientCall) factory.newCall(REQUEST);

    // then: call should contain correct http client
    assertNotNull(call);
    assertTrue(call.getHttpClient() == httpClientB);
  }
}
