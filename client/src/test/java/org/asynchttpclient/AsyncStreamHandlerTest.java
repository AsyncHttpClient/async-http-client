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

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.test.TestUtils.*;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.*;
import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;
import static org.testng.Assert.*;

public class AsyncStreamHandlerTest extends HttpTest {

  private static final String RESPONSE = "param_1=value_1";

  private static HttpServer server;

  @BeforeClass
  public static void start() throws Throwable {
    server = new HttpServer();
    server.start();
  }

  @AfterClass
  public static void stop() throws Throwable {
    server.close();
  }

  private static String getTargetUrl() {
    return server.getHttpUrl() + "/foo/bar";
  }

  @Test
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

  @Test
  public void asyncStreamPOSTTest() throws Throwable {

    withClient().run(client ->
      withServer(server).run(server -> {

        server.enqueueEcho();

        String responseBody = client.preparePost(getTargetUrl())
                .setHeader(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)
                .addFormParam("param_1", "value_1")
                .execute(new AsyncHandlerAdapter() {
                  private StringBuilder builder = new StringBuilder();

                  @Override
                  public State onHeadersReceived(HttpHeaders headers) {
                    assertContentTypesEquals(headers.get(CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                    for (Map.Entry<String, String> header : headers) {
                      if (header.getKey().startsWith("X-param")) {
                        builder.append(header.getKey().substring(2)).append("=").append(header.getValue()).append("&");
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

  @Test
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

  @Test
  public void asyncStreamFutureTest() throws Throwable {

    withClient().run(client ->
      withServer(server).run(server -> {

        server.enqueueEcho();

        final AtomicBoolean onHeadersReceived = new AtomicBoolean();
        final AtomicBoolean onThrowable = new AtomicBoolean();

        String responseBody = client.preparePost(getTargetUrl())
                .addFormParam("param_1", "value_1")
                .execute(new AsyncHandlerAdapter() {
                  private StringBuilder builder = new StringBuilder();

                  @Override
                  public State onHeadersReceived(HttpHeaders headers) {
                    assertContentTypesEquals(headers.get(CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                    onHeadersReceived.set(true);
                    for (Map.Entry<String, String> header : headers) {
                      if (header.getKey().startsWith("X-param")) {
                        builder.append(header.getKey().substring(2)).append("=").append(header.getValue()).append("&");
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

  @Test
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

  @Test
  public void asyncStreamReusePOSTTest() throws Throwable {

    withClient().run(client ->
      withServer(server).run(server -> {

        server.enqueueEcho();

        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();

        BoundRequestBuilder rb = client.preparePost(getTargetUrl())
                .setHeader(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)
                .addFormParam("param_1", "value_1");

        Future<String> f = rb.execute(new AsyncHandlerAdapter() {
          private StringBuilder builder = new StringBuilder();

          @Override
          public State onHeadersReceived(HttpHeaders headers) {
            responseHeaders.set(headers);
            for (Map.Entry<String, String> header : headers) {
              if (header.getKey().startsWith("X-param")) {
                builder.append(header.getKey().substring(2)).append("=").append(header.getValue()).append("&");
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
          private StringBuilder builder = new StringBuilder();

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

  @Test
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

  @Test(timeOut = 3000)
  public void asyncStreamJustStatusLine() throws Throwable {

    withClient().run(client ->
      withServer(server).run(server -> {

        server.enqueueEcho();

        final int STATUS = 0;
        final int COMPLETED = 1;
        final int OTHER = 2;
        final boolean[] whatCalled = new boolean[]{false, false, false};
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

  @Test(groups = "online")
  public void asyncOptionsTest() throws Throwable {

    withClient().run(client ->
      withServer(server).run(server -> {

        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();

        final String[] expected = {"GET", "HEAD", "OPTIONS", "POST"};
        Future<String> f = client.prepareOptions("http://www.apache.org/").execute(new AsyncHandlerAdapter() {

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
        String[] values = h.get(ALLOW).split(",|, ");
        assertNotNull(values);
        assertEquals(values.length, expected.length);
        Arrays.sort(values);
        assertEquals(values, expected);
      }));
  }

  @Test
  public void closeConnectionTest() throws Throwable {

    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueEcho();

        Response r = client.prepareGet(getTargetUrl()).execute(new AsyncHandler<Response>() {

          private Response.ResponseBuilder builder = new Response.ResponseBuilder();

          public State onHeadersReceived(HttpHeaders headers) {
            builder.accumulate(headers);
            return State.CONTINUE;
          }

          public void onThrowable(Throwable t) {
          }

          public State onBodyPartReceived(HttpResponseBodyPart content) {
            builder.accumulate(content);
            return content.isLast() ? State.ABORT : State.CONTINUE;
          }

          public State onStatusReceived(HttpResponseStatus responseStatus) {
            builder.accumulate(responseStatus);

            return State.CONTINUE;
          }

          public Response onCompleted() {
            return builder.build();
          }
        }).get();

        assertNotNull(r);
        assertEquals(r.getStatusCode(), 200);
      }));
  }
}
