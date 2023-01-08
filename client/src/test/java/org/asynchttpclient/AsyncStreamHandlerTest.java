/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty.handler.codec.http.HttpHeaderNames.ALLOW;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.AsyncHandlerAdapter;
import static org.asynchttpclient.test.TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET;
import static org.asynchttpclient.test.TestUtils.TIMEOUT;
import static org.asynchttpclient.test.TestUtils.assertContentTypesEquals;
import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class AsyncStreamHandlerTest extends HttpTest {

    private static final String RESPONSE = "param_1=value_1";

    private HttpServer server;

    @BeforeEach
    public void start() throws Throwable {
        server = new HttpServer();
        server.start();
    }

    @AfterEach
    public void stop() throws Throwable {
        server.close();
    }

    private String getTargetUrl() {
        return server.getHttpUrl() + "/foo/bar";
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void getWithOnHeadersReceivedAbort() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {
                    server.enqueueEcho();
                    client.prepareGet(getTargetUrl()).execute(new AsyncHandlerAdapter() {

                        @Override
                        public State onHeadersReceived(HttpHeaders headers) {
                            assertContentTypesEquals(headers.get(CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                            return State.ABORT;
                        }
                    }).get(5, TimeUnit.SECONDS);
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void asyncStreamPOSTTest() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {

                    server.enqueueEcho();

                    String responseBody = client.preparePost(getTargetUrl())
                            .setHeader(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)
                            .addFormParam("param_1", "value_1")
                            .execute(new AsyncHandlerAdapter() {
                                private final StringBuilder builder = new StringBuilder();

                                @Override
                                public State onHeadersReceived(HttpHeaders headers) {
                                    assertContentTypesEquals(headers.get(CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                                    for (Map.Entry<String, String> header : headers) {
                                        if (header.getKey().startsWith("X-param")) {
                                            builder.append(header.getKey().substring(2)).append('=').append(header.getValue()).append('&');
                                        }
                                    }
                                    return State.CONTINUE;
                                }

                                @Override
                                public State onBodyPartReceived(HttpResponseBodyPart content) {
                                    return State.CONTINUE;
                                }

                                @Override
                                public String onCompleted() {
                                    if (builder.length() > 0) {
                                        builder.setLength(builder.length() - 1);
                                    }
                                    return builder.toString();
                                }
                            }).get(10, TimeUnit.SECONDS);

                    assertEquals(responseBody, RESPONSE);
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void asyncStreamInterruptTest() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {

                    server.enqueueEcho();

                    final AtomicBoolean onHeadersReceived = new AtomicBoolean();
                    final AtomicBoolean onBodyPartReceived = new AtomicBoolean();
                    final AtomicBoolean onThrowable = new AtomicBoolean();

                    client.preparePost(getTargetUrl())
                            .setHeader(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)
                            .addFormParam("param_1", "value_1")
                            .execute(new AsyncHandlerAdapter() {

                                @Override
                                public State onHeadersReceived(HttpHeaders headers) {
                                    onHeadersReceived.set(true);
                                    assertContentTypesEquals(headers.get(CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                                    return State.ABORT;
                                }

                                @Override
                                public State onBodyPartReceived(final HttpResponseBodyPart content) {
                                    onBodyPartReceived.set(true);
                                    return State.ABORT;
                                }

                                @Override
                                public void onThrowable(Throwable t) {
                                    onThrowable.set(true);
                                }
                            }).get(5, TimeUnit.SECONDS);

                    assertTrue(onHeadersReceived.get(), "Headers weren't received");
                    assertFalse(onBodyPartReceived.get(), "Abort not working");
                    assertFalse(onThrowable.get(), "Shouldn't get an exception");
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void asyncStreamFutureTest() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {

                    server.enqueueEcho();

                    final AtomicBoolean onHeadersReceived = new AtomicBoolean();
                    final AtomicBoolean onThrowable = new AtomicBoolean();

                    String responseBody = client.preparePost(getTargetUrl())
                            .addFormParam("param_1", "value_1")
                            .execute(new AsyncHandlerAdapter() {
                                private final StringBuilder builder = new StringBuilder();

                                @Override
                                public State onHeadersReceived(HttpHeaders headers) {
                                    assertContentTypesEquals(headers.get(CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                                    onHeadersReceived.set(true);
                                    for (Map.Entry<String, String> header : headers) {
                                        if (header.getKey().startsWith("X-param")) {
                                            builder.append(header.getKey().substring(2)).append('=').append(header.getValue()).append('&');
                                        }
                                    }
                                    return State.CONTINUE;
                                }

                                @Override
                                public State onBodyPartReceived(HttpResponseBodyPart content) {
                                    return State.CONTINUE;
                                }

                                @Override
                                public String onCompleted() {
                                    if (builder.length() > 0) {
                                        builder.setLength(builder.length() - 1);
                                    }
                                    return builder.toString().trim();
                                }

                                @Override
                                public void onThrowable(Throwable t) {
                                    onThrowable.set(true);
                                }
                            }).get(5, TimeUnit.SECONDS);

                    assertTrue(onHeadersReceived.get(), "Headers weren't received");
                    assertFalse(onThrowable.get(), "Shouldn't get an exception");
                    assertEquals(responseBody, RESPONSE, "Unexpected response body");
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void asyncStreamThrowableRefusedTest() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {

                    server.enqueueEcho();

                    final CountDownLatch l = new CountDownLatch(1);
                    client.prepareGet(getTargetUrl()).execute(new AsyncHandlerAdapter() {

                        @Override
                        public State onHeadersReceived(HttpHeaders headers) {
                            throw unknownStackTrace(new RuntimeException("FOO"), AsyncStreamHandlerTest.class, "asyncStreamThrowableRefusedTest");
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            try {
                                if (t.getMessage() != null) {
                                    assertEquals(t.getMessage(), "FOO");
                                }
                            } finally {
                                l.countDown();
                            }
                        }
                    });

                    if (!l.await(10, TimeUnit.SECONDS)) {
                        fail("Timed out");
                    }
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void asyncStreamReusePOSTTest() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {

                    server.enqueueEcho();

                    final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();

                    BoundRequestBuilder rb = client.preparePost(getTargetUrl())
                            .setHeader(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)
                            .addFormParam("param_1", "value_1");

                    Future<String> f = rb.execute(new AsyncHandlerAdapter() {
                        private final StringBuilder builder = new StringBuilder();

                        @Override
                        public State onHeadersReceived(HttpHeaders headers) {
                            responseHeaders.set(headers);
                            for (Map.Entry<String, String> header : headers) {
                                if (header.getKey().startsWith("X-param")) {
                                    builder.append(header.getKey().substring(2)).append('=').append(header.getValue()).append('&');
                                }
                            }
                            return State.CONTINUE;
                        }

                        @Override
                        public State onBodyPartReceived(HttpResponseBodyPart content) {
                            return State.CONTINUE;
                        }

                        @Override
                        public String onCompleted() {
                            if (builder.length() > 0) {
                                builder.setLength(builder.length() - 1);
                            }
                            return builder.toString();
                        }
                    });

                    String r = f.get(5, TimeUnit.SECONDS);
                    HttpHeaders h = responseHeaders.get();
                    assertNotNull(h, "Should receive non null headers");
                    assertContentTypesEquals(h.get(CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                    assertNotNull(r, "No response body");
                    assertEquals(r.trim(), RESPONSE, "Unexpected response body");

                    responseHeaders.set(null);

                    server.enqueueEcho();

                    // Let do the same again
                    f = rb.execute(new AsyncHandlerAdapter() {
                        private final StringBuilder builder = new StringBuilder();

                        @Override
                        public State onHeadersReceived(HttpHeaders headers) {
                            responseHeaders.set(headers);
                            return State.CONTINUE;
                        }

                        @Override
                        public State onBodyPartReceived(HttpResponseBodyPart content) {
                            builder.append(new String(content.getBodyPartBytes()));
                            return State.CONTINUE;
                        }

                        @Override
                        public String onCompleted() {
                            return builder.toString();
                        }
                    });

                    f.get(5, TimeUnit.SECONDS);
                    h = responseHeaders.get();
                    assertNotNull(h, "Should receive non null headers");
                    assertContentTypesEquals(h.get(CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                    assertNotNull(r, "No response body");
                    assertEquals(r.trim(), RESPONSE, "Unexpected response body");
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void asyncStream302RedirectWithBody() throws Throwable {
        withClient(config().setFollowRedirect(true)).run(client ->
                withServer(server).run(server -> {

                    String originalUrl = server.getHttpUrl() + "/original";
                    String redirectUrl = server.getHttpUrl() + "/redirect";

                    server.enqueueResponse(response -> {
                        response.setStatus(302);
                        response.setHeader(LOCATION.toString(), redirectUrl);
                        response.getOutputStream().println("You are being asked to redirect to " + redirectUrl);
                    });
                    server.enqueueOk();

                    Response response = client.prepareGet(originalUrl).execute().get(20, TimeUnit.SECONDS);

                    assertEquals(response.getStatusCode(), 200);
                    assertTrue(response.getResponseBody().isEmpty());
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 3000)
    public void asyncStreamJustStatusLine() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {

                    server.enqueueEcho();

                    final int STATUS = 0;
                    final int COMPLETED = 1;
                    final int OTHER = 2;
                    final boolean[] whatCalled = {false, false, false};
                    final CountDownLatch latch = new CountDownLatch(1);
                    Future<Integer> statusCode = client.prepareGet(getTargetUrl()).execute(new AsyncHandler<Integer>() {
                        private int status = -1;

                        @Override
                        public void onThrowable(Throwable t) {
                            whatCalled[OTHER] = true;
                            latch.countDown();
                        }

                        @Override
                        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                            whatCalled[OTHER] = true;
                            latch.countDown();
                            return State.ABORT;
                        }

                        @Override
                        public State onStatusReceived(HttpResponseStatus responseStatus) {
                            whatCalled[STATUS] = true;
                            status = responseStatus.getStatusCode();
                            latch.countDown();
                            return State.ABORT;
                        }

                        @Override
                        public State onHeadersReceived(HttpHeaders headers) {
                            whatCalled[OTHER] = true;
                            latch.countDown();
                            return State.ABORT;
                        }

                        @Override
                        public Integer onCompleted() {
                            whatCalled[COMPLETED] = true;
                            latch.countDown();
                            return status;
                        }
                    });

                    if (!latch.await(2, TimeUnit.SECONDS)) {
                        fail("Timeout");
                        return;
                    }
                    Integer status = statusCode.get(TIMEOUT, TimeUnit.SECONDS);
                    assertEquals((int) status, 200, "Expected status code failed.");

                    if (!whatCalled[STATUS]) {
                        fail("onStatusReceived not called.");
                    }
                    if (!whatCalled[COMPLETED]) {
                        fail("onCompleted not called.");
                    }
                    if (whatCalled[OTHER]) {
                        fail("Other method of AsyncHandler got called.");
                    }
                }));
    }

    // This test is flaky - see https://github.com/AsyncHttpClient/async-http-client/issues/1728#issuecomment-699962325
    // For now, just run again if fails
    @RepeatedIfExceptionsTest(repeats = 5)
    public void asyncOptionsTest() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {

                    final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();

                    // Some responses contain the TRACE method, some do not - account for both
                    final String[] expected = {"GET", "HEAD", "OPTIONS", "POST"};
                    final String[] expectedWithTrace = {"GET", "HEAD", "OPTIONS", "POST", "TRACE"};
                    Future<String> f = client.prepareOptions("https://www.shieldblaze.com/").execute(new AsyncHandlerAdapter() {

                        @Override
                        public State onHeadersReceived(HttpHeaders headers) {
                            responseHeaders.set(headers);
                            return State.ABORT;
                        }

                        @Override
                        public String onCompleted() {
                            return "OK";
                        }
                    });

                    f.get(20, TimeUnit.SECONDS);
                    HttpHeaders h = responseHeaders.get();
                    assertNotNull(h);
                    if (h.contains(ALLOW)) {
                        String[] values = h.get(ALLOW).split(",|, ");
                        assertNotNull(values);
                        // Some responses contain the TRACE method, some do not - account for both
                        assert values.length == expected.length || values.length == expectedWithTrace.length;
                        Arrays.sort(values);
                        // Some responses contain the TRACE method, some do not - account for both
                        if (values.length == expected.length) {
                            assertArrayEquals(values, expected);
                        } else {
                            assertArrayEquals(values, expectedWithTrace);
                        }
                    }
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void closeConnectionTest() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {
                    server.enqueueEcho();

                    Response r = client.prepareGet(getTargetUrl()).execute(new AsyncHandler<Response>() {

                        private final Response.ResponseBuilder builder = new Response.ResponseBuilder();

                        @Override
                        public State onHeadersReceived(HttpHeaders headers) {
                            builder.accumulate(headers);
                            return State.CONTINUE;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                        }

                        @Override
                        public State onBodyPartReceived(HttpResponseBodyPart content) {
                            builder.accumulate(content);
                            return content.isLast() ? State.ABORT : State.CONTINUE;
                        }

                        @Override
                        public State onStatusReceived(HttpResponseStatus responseStatus) {
                            builder.accumulate(responseStatus);

                            return State.CONTINUE;
                        }

                        @Override
                        public Response onCompleted() {
                            return builder.build();
                        }
                    }).get();

                    assertNotNull(r);
                    assertEquals(r.getStatusCode(), 200);
                }));
    }
}
