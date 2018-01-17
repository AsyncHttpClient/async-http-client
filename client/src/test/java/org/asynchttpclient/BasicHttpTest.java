/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.asynchttpclient.test.EventCollectingHandler;
import org.asynchttpclient.test.TestUtils.*;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpServer.EchoHandler;
import org.asynchttpclient.testserver.HttpTest;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.net.ssl.SSLException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;
import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;
import static org.testng.Assert.*;

public class BasicHttpTest extends HttpTest {

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
  public void getRootUrl() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        String url = server.getHttpUrl();
        server.enqueueOk();

        Response response = client.executeRequest(get(url), new AsyncCompletionHandlerAdapter()).get(TIMEOUT, SECONDS);
        assertEquals(response.getUri().toUrl(), url);
      }));
  }

  @Test
  public void getUrlWithPathWithoutQuery() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueOk();

        Response response = client.executeRequest(get(getTargetUrl()), new AsyncCompletionHandlerAdapter()).get(TIMEOUT, SECONDS);
        assertEquals(response.getUri().toUrl(), getTargetUrl());
      }));
  }

  @Test
  public void getUrlWithPathWithQuery() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        String targetUrl = getTargetUrl() + "?q=+%20x";
        Request request = get(targetUrl).build();
        assertEquals(request.getUrl(), targetUrl);
        server.enqueueOk();

        Response response = client.executeRequest(request, new AsyncCompletionHandlerAdapter()).get(TIMEOUT, SECONDS);
        assertEquals(response.getUri().toUrl(), targetUrl);
      }));
  }

  @Test
  public void getUrlWithPathWithQueryParams() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueOk();

        Response response = client.executeRequest(get(getTargetUrl()).addQueryParam("q", "a b"), new AsyncCompletionHandlerAdapter()).get(TIMEOUT, SECONDS);
        assertEquals(response.getUri().toUrl(), getTargetUrl() + "?q=a%20b");
      }));
  }

  @Test
  public void getResponseBody() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        final String body = "Hello World";

        server.enqueueResponse(response -> {
          response.setStatus(200);
          response.setContentType(TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
          writeResponseBody(response, body);
        });

        client.executeRequest(get(getTargetUrl()), new AsyncCompletionHandlerAdapter() {

          @Override
          public Response onCompleted(Response response) {
            assertEquals(response.getStatusCode(), 200);
            String contentLengthHeader = response.getHeader(CONTENT_LENGTH);
            assertNotNull(contentLengthHeader);
            assertEquals(Integer.parseInt(contentLengthHeader), body.length());
            assertContentTypesEquals(response.getContentType(), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
            assertEquals(response.getResponseBody(), body);
            return response;
          }
        }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void getWithHeaders() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders h = new DefaultHttpHeaders();
        for (int i = 1; i < 5; i++) {
          h.add("Test" + i, "Test" + i);
        }

        server.enqueueEcho();

        client.executeRequest(get(getTargetUrl()).setHeaders(h), new AsyncCompletionHandlerAdapter() {

          @Override
          public Response onCompleted(Response response) {
            assertEquals(response.getStatusCode(), 200);
            for (int i = 1; i < 5; i++) {
              assertEquals(response.getHeader("X-Test" + i), "Test" + i);
            }
            return response;
          }
        }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void postWithHeadersAndFormParams() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders h = new DefaultHttpHeaders();
        h.add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);

        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < 5; i++) {
          m.put("param_" + i, Collections.singletonList("value_" + i));
        }

        Request request = post(getTargetUrl()).setHeaders(h).setFormParams(m).build();

        server.enqueueEcho();

        client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

          @Override
          public Response onCompleted(Response response) {
            assertEquals(response.getStatusCode(), 200);
            for (int i = 1; i < 5; i++) {
              assertEquals(response.getHeader("X-param_" + i), "value_" + i);
            }
            return response;
          }
        }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void headHasEmptyBody() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueOk();

        Response response = client.executeRequest(head(getTargetUrl()), new AsyncCompletionHandlerAdapter() {
          @Override
          public Response onCompleted(Response response) {
            assertEquals(response.getStatusCode(), 200);
            return response;
          }
        }).get(TIMEOUT, SECONDS);

        assertTrue(response.getResponseBody().isEmpty());
      }));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void nullSchemeThrowsNPE() throws Throwable {
    withClient().run(client -> client.prepareGet("gatling.io").execute());
  }

  @Test
  public void jettyRespondsWithChunkedTransferEncoding() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueEcho();
        client.prepareGet(getTargetUrl())//
                .execute(new AsyncCompletionHandlerAdapter() {
                  @Override
                  public Response onCompleted(Response response) {
                    assertEquals(response.getStatusCode(), 200);
                    assertEquals(response.getHeader(TRANSFER_ENCODING), HttpHeaderValues.CHUNKED.toString());
                    return response;
                  }
                }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void getWithCookies() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        final Cookie coo = new DefaultCookie("foo", "value");
        coo.setDomain("/");
        coo.setPath("/");
        server.enqueueEcho();

        client.prepareGet(getTargetUrl())//
                .addCookie(coo)//
                .execute(new AsyncCompletionHandlerAdapter() {
                  @Override
                  public Response onCompleted(Response response) {
                    assertEquals(response.getStatusCode(), 200);
                    List<Cookie> cookies = response.getCookies();
                    assertEquals(cookies.size(), 1);
                    assertEquals(cookies.get(0).toString(), "foo=value");
                    return response;
                  }
                }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void defaultRequestBodyEncodingIsUtf8() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueEcho();
        Response response = client.preparePost(getTargetUrl())//
                .setBody("\u017D\u017D\u017D\u017D\u017D\u017D")//
                .execute().get();
        assertEquals(response.getResponseBodyAsBytes(), "\u017D\u017D\u017D\u017D\u017D\u017D".getBytes(UTF_8));
      }));
  }

  @Test
  public void postFormParametersAsBodyString() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders h = new DefaultHttpHeaders();
        h.add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
          sb.append("param_").append(i).append("=value_").append(i).append("&");
        }
        sb.setLength(sb.length() - 1);

        server.enqueueEcho();
        client.preparePost(getTargetUrl())//
                .setHeaders(h)//
                .setBody(sb.toString())//
                .execute(new AsyncCompletionHandlerAdapter() {

                  @Override
                  public Response onCompleted(Response response) {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                      assertEquals(response.getHeader("X-param_" + i), "value_" + i);

                    }
                    return response;
                  }
                }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void postFormParametersAsBodyStream() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders h = new DefaultHttpHeaders();
        h.add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
          sb.append("param_").append(i).append("=value_").append(i).append("&");
        }
        sb.setLength(sb.length() - 1);

        server.enqueueEcho();
        client.preparePost(getTargetUrl())//
                .setHeaders(h)//
                .setBody(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)))//
                .execute(new AsyncCompletionHandlerAdapter() {

                  @Override
                  public Response onCompleted(Response response) {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                      assertEquals(response.getHeader("X-param_" + i), "value_" + i);

                    }
                    return response;
                  }
                }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void putFormParametersAsBodyStream() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders h = new DefaultHttpHeaders();
        h.add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
          sb.append("param_").append(i).append("=value_").append(i).append("&");
        }
        sb.setLength(sb.length() - 1);
        ByteArrayInputStream is = new ByteArrayInputStream(sb.toString().getBytes());

        server.enqueueEcho();
        client.preparePut(getTargetUrl())//
                .setHeaders(h)//
                .setBody(is)//
                .execute(new AsyncCompletionHandlerAdapter() {

                  @Override
                  public Response onCompleted(Response response) {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                      assertEquals(response.getHeader("X-param_" + i), "value_" + i);
                    }
                    return response;
                  }
                }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void postSingleStringPart() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueEcho();
        client.preparePost(getTargetUrl())//
                .addBodyPart(new StringPart("foo", "bar"))//
                .execute(new AsyncCompletionHandlerAdapter() {
                  @Override
                  public Response onCompleted(Response response) {
                    String requestContentType = response.getHeader("X-" + CONTENT_TYPE);
                    String boundary = requestContentType.substring((requestContentType.indexOf("boundary") + "boundary".length() + 1));
                    assertTrue(response.getResponseBody().regionMatches(false, "--".length(), boundary, 0, boundary.length()));
                    return response;
                  }
                }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void getVirtualHost() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        String virtualHost = "localhost:" + server.getHttpPort();

        server.enqueueEcho();
        Response response = client.prepareGet(getTargetUrl())//
                .setVirtualHost(virtualHost)//
                .execute(new AsyncCompletionHandlerAdapter()).get(TIMEOUT, SECONDS);

        assertEquals(response.getStatusCode(), 200);
        if (response.getHeader("X-" + HOST) == null) {
          System.err.println(response);
        }
        assertEquals(response.getHeader("X-" + HOST), virtualHost);
      }));
  }

  @Test(expectedExceptions = CancellationException.class)
  public void cancelledFutureThrowsCancellationException() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.add("X-Delay", 5_000);
        server.enqueueEcho();

        Future<Response> future = client.prepareGet(getTargetUrl()).setHeaders(headers).execute(new AsyncCompletionHandlerAdapter() {
          @Override
          public void onThrowable(Throwable t) {
          }
        });
        future.cancel(true);
        future.get(TIMEOUT, SECONDS);
      }));
  }

  @Test(expectedExceptions = TimeoutException.class)
  public void futureTimeOutThrowsTimeoutException() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.add("X-Delay", 5_000);

        server.enqueueEcho();
        Future<Response> future = client.prepareGet(getTargetUrl()).setHeaders(headers).execute(new AsyncCompletionHandlerAdapter() {
          @Override
          public void onThrowable(Throwable t) {
          }
        });

        future.get(2, SECONDS);
      }));
  }

  @Test(expectedExceptions = ConnectException.class)
  public void connectFailureThrowsConnectException() throws Throwable {
    withClient().run(client -> {
      int dummyPort = findFreePort();
      try {
        client.preparePost(String.format("http://localhost:%d/", dummyPort)).execute(new AsyncCompletionHandlerAdapter() {
          @Override
          public void onThrowable(Throwable t) {
          }
        }).get(TIMEOUT, SECONDS);
      } catch (ExecutionException ex) {
        throw ex.getCause();
      }
    });
  }

  @Test
  public void connectFailureNotifiesHandlerWithConnectException() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        final CountDownLatch l = new CountDownLatch(1);
        int port = findFreePort();

        client.prepareGet(String.format("http://localhost:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
          @Override
          public void onThrowable(Throwable t) {
            try {
              assertTrue(t instanceof ConnectException);
            } finally {
              l.countDown();
            }
          }
        });

        if (!l.await(TIMEOUT, SECONDS)) {
          fail("Timed out");
        }
      }));
  }

  @Test(expectedExceptions = UnknownHostException.class)
  public void unknownHostThrowsUnknownHostException() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        try {
          client.prepareGet("http://null.gatling.io").execute(new AsyncCompletionHandlerAdapter() {
            @Override
            public void onThrowable(Throwable t) {
            }
          }).get(TIMEOUT, SECONDS);
        } catch (ExecutionException e) {
          throw e.getCause();
        }
      }));
  }

  @Test
  public void getEmptyBody() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueOk();
        Response response = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter())//
                .get(TIMEOUT, SECONDS);
        assertTrue(response.getResponseBody().isEmpty());
      }));
  }

  @Test
  public void getEmptyBodyNotifiesHandler() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        final AtomicBoolean handlerWasNotified = new AtomicBoolean();

        server.enqueueOk();
        client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

          @Override
          public Response onCompleted(Response response) {
            assertEquals(response.getStatusCode(), 200);
            handlerWasNotified.set(true);
            return response;
          }
        }).get(TIMEOUT, SECONDS);
        assertTrue(handlerWasNotified.get());
      }));
  }

  @Test
  public void exceptionInOnCompletedGetNotifiedToOnThrowable() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> message = new AtomicReference<>();

        server.enqueueOk();
        client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {
          @Override
          public Response onCompleted(Response response) {
            throw unknownStackTrace(new IllegalStateException("FOO"), BasicHttpTest.class, "exceptionInOnCompletedGetNotifiedToOnThrowable");

          }

          @Override
          public void onThrowable(Throwable t) {
            message.set(t.getMessage());
            latch.countDown();
          }
        });

        if (!latch.await(TIMEOUT, SECONDS)) {
          fail("Timed out");
        }

        assertEquals(message.get(), "FOO");
      }));
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void exceptionInOnCompletedGetNotifiedToFuture() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueOk();
        Future<Response> whenResponse = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {
          @Override
          public Response onCompleted(Response response) {
            throw unknownStackTrace(new IllegalStateException("FOO"), BasicHttpTest.class, "exceptionInOnCompletedGetNotifiedToFuture");
          }

          @Override
          public void onThrowable(Throwable t) {
          }
        });

        try {
          whenResponse.get(TIMEOUT, SECONDS);
        } catch (ExecutionException e) {
          throw e.getCause();
        }
      }));
  }

  @Test(expectedExceptions = TimeoutException.class)
  public void configTimeoutNotifiesOnThrowableAndFuture() throws Throwable {
    withClient(config().setRequestTimeout(1_000)).run(client ->
      withServer(server).run(server -> {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.add("X-Delay", 5_000); // delay greater than timeout

        final AtomicBoolean onCompletedWasNotified = new AtomicBoolean();
        final AtomicBoolean onThrowableWasNotifiedWithTimeoutException = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);

        server.enqueueEcho();
        Future<Response> whenResponse = client.prepareGet(getTargetUrl()).setHeaders(headers).execute(new AsyncCompletionHandlerAdapter() {

          @Override
          public Response onCompleted(Response response) {
            onCompletedWasNotified.set(true);
            latch.countDown();
            return response;
          }

          @Override
          public void onThrowable(Throwable t) {
            onThrowableWasNotifiedWithTimeoutException.set(t instanceof TimeoutException);
            latch.countDown();
          }
        });

        if (!latch.await(TIMEOUT, SECONDS)) {
          fail("Timed out");
        }

        assertFalse(onCompletedWasNotified.get());
        assertTrue(onThrowableWasNotifiedWithTimeoutException.get());

        try {
          whenResponse.get(TIMEOUT, SECONDS);
        } catch (ExecutionException e) {
          throw e.getCause();
        }
      }));
  }

  @Test(expectedExceptions = TimeoutException.class)
  public void configRequestTimeoutHappensInDueTime() throws Throwable {
    withClient(config().setRequestTimeout(1_000)).run(client ->
      withServer(server).run(server -> {
        HttpHeaders h = new DefaultHttpHeaders();
        h.add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        h.add("X-Delay", 2_000);

        server.enqueueEcho();
        long start = unpreciseMillisTime();
        try {
          client.prepareGet(getTargetUrl()).setHeaders(h).setUrl(getTargetUrl()).execute().get();
        } catch (Throwable ex) {
          final long elapsedTime = unpreciseMillisTime() - start;
          assertTrue(elapsedTime >= 1_000 && elapsedTime <= 1_500);
          throw ex.getCause();
        }
      }));
  }

  @Test
  public void getProperPathAndQueryString() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueEcho();
        client.prepareGet(getTargetUrl() + "?foo=bar").execute(new AsyncCompletionHandlerAdapter() {
          @Override
          public Response onCompleted(Response response) {
            assertTrue(response.getHeader("X-PathInfo") != null);
            assertTrue(response.getHeader("X-QueryString") != null);
            return response;
          }
        }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void connectionIsReusedForSequentialRequests() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        final CountDownLatch l = new CountDownLatch(2);

        AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

          volatile String clientPort;

          @Override
          public Response onCompleted(Response response) {
            try {
              assertEquals(response.getStatusCode(), 200);
              if (clientPort == null) {
                clientPort = response.getHeader("X-ClientPort");
              } else {
                // verify that the server saw the same client remote address/port
                // so the same connection was used
                assertEquals(response.getHeader("X-ClientPort"), clientPort);
              }
            } finally {
              l.countDown();
            }
            return response;
          }
        };

        server.enqueueEcho();
        client.prepareGet(getTargetUrl()).execute(handler).get(TIMEOUT, SECONDS);
        server.enqueueEcho();
        client.prepareGet(getTargetUrl()).execute(handler);

        if (!l.await(TIMEOUT, SECONDS)) {
          fail("Timed out");
        }
      }));
  }

  @Test(expectedExceptions = MaxRedirectException.class)
  public void reachingMaxRedirectThrowsMaxRedirectException() throws Throwable {
    withClient(config().setMaxRedirects(1).setFollowRedirect(true)).run(client ->
      withServer(server).run(server -> {
        try {
          // max redirect is 1, so second redirect will fail
          server.enqueueRedirect(301, getTargetUrl());
          server.enqueueRedirect(301, getTargetUrl());
          client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {
            @Override
            public Response onCompleted(Response response) {
              fail("Should not be here");
              return response;
            }

            @Override
            public void onThrowable(Throwable t) {
            }
          }).get(TIMEOUT, SECONDS);
        } catch (ExecutionException e) {
          throw e.getCause();
        }
      }));
  }

  @Test
  public void nonBlockingNestedRequetsFromIoThreadAreFine() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {

        final int maxNested = 5;

        final CountDownLatch latch = new CountDownLatch(2);

        final AsyncCompletionHandlerAdapter handler = new AsyncCompletionHandlerAdapter() {

          private AtomicInteger nestedCount = new AtomicInteger(0);

          @Override
          public Response onCompleted(Response response) {
            try {
              if (nestedCount.getAndIncrement() < maxNested) {
                client.prepareGet(getTargetUrl()).execute(this);
              }
            } finally {
              latch.countDown();
            }
            return response;
          }
        };

        for (int i = 0; i < maxNested + 1; i++) {
          server.enqueueOk();
        }

        client.prepareGet(getTargetUrl()).execute(handler);

        if (!latch.await(TIMEOUT, SECONDS)) {
          fail("Timed out");
        }
      }));
  }

  @Test
  public void optionsIsSupported() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueEcho();
        Response response = client.prepareOptions(getTargetUrl()).execute().get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getHeader("Allow"), "GET,HEAD,POST,OPTIONS,TRACE");
      }));
  }

  @Test
  public void cancellingFutureNotifiesOnThrowableWithCancellationException() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders h = new DefaultHttpHeaders();
        h.add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        h.add("X-Delay", 2_000);

        CountDownLatch latch = new CountDownLatch(1);

        Future<Response> future = client.preparePost(getTargetUrl()).setHeaders(h).setBody("Body").execute(new AsyncCompletionHandlerAdapter() {

          @Override
          public void onThrowable(Throwable t) {
            if (t instanceof CancellationException) {
              latch.countDown();
            }
          }
        });

        future.cancel(true);
        if (!latch.await(TIMEOUT, SECONDS)) {
          fail("Timed out");
        }
      }));
  }

  @Test
  public void getShouldAllowBody() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server ->
        client.prepareGet(getTargetUrl()).setBody("Boo!").execute()));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void malformedUriThrowsException() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> client.prepareGet(String.format("http:localhost:%d/foo/test", server.getHttpPort())).build()));
  }

  @Test
  public void emptyResponseBodyBytesAreEmpty() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueEcho();
        Response response = client.prepareGet(getTargetUrl()).execute().get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getResponseBodyAsBytes(), new byte[]{});
      }));
  }

  @Test
  public void newConnectionEventsAreFired() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {

        Request request = get(getTargetUrl()).build();

        EventCollectingHandler handler = new EventCollectingHandler();
        client.executeRequest(request, handler).get(3, SECONDS);
        handler.waitForCompletion(3, SECONDS);

        Object[] expectedEvents = new Object[]{//
                CONNECTION_POOL_EVENT,//
                HOSTNAME_RESOLUTION_EVENT,//
                HOSTNAME_RESOLUTION_SUCCESS_EVENT,//
                CONNECTION_OPEN_EVENT,//
                CONNECTION_SUCCESS_EVENT,//
                REQUEST_SEND_EVENT,//
                HEADERS_WRITTEN_EVENT,//
                STATUS_RECEIVED_EVENT,//
                HEADERS_RECEIVED_EVENT,//
                CONNECTION_OFFER_EVENT,//
                COMPLETED_EVENT};

        assertEquals(handler.firedEvents.toArray(), expectedEvents, "Got " + Arrays.toString(handler.firedEvents.toArray()));
      }));
  }

  @Test
  public void requestingPlainHttpEndpointOverHttpsThrowsSslException() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        server.enqueueEcho();
        try {
          client.prepareGet(getTargetUrl().replace("http", "https")).execute().get();
          fail("Request shouldn't succeed");
        } catch (ExecutionException e) {
          assertTrue(e.getCause() instanceof ConnectException, "Cause should be a ConnectException");
          assertTrue(e.getCause().getCause() instanceof SSLException, "Root cause should be a SslException");
        }
      }));
  }

  @Test
  public void postUnboundedInputStreamAsBodyStream() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders h = new DefaultHttpHeaders();
        h.add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        server.enqueue(new AbstractHandler() {
          EchoHandler chain = new EchoHandler();

          @Override
          public void handle(String target, org.eclipse.jetty.server.Request request, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
                  throws IOException, ServletException {
            assertEquals(request.getHeader(TRANSFER_ENCODING.toString()), HttpHeaderValues.CHUNKED.toString());
            assertNull(request.getHeader(CONTENT_LENGTH.toString()));
            chain.handle(target, request, httpServletRequest, httpServletResponse);
          }
        });
        server.enqueueEcho();

        client.preparePost(getTargetUrl())//
                .setHeaders(h)//
                .setBody(new ByteArrayInputStream("{}".getBytes(StandardCharsets.ISO_8859_1)))//
                .execute(new AsyncCompletionHandlerAdapter() {
                  @Override
                  public Response onCompleted(Response response) {
                    assertEquals(response.getStatusCode(), 200);
                    assertEquals(response.getResponseBody(), "{}");
                    return response;
                  }
                }).get(TIMEOUT, SECONDS);
      }));
  }

  @Test
  public void postInputStreamWithContentLengthAsBodyGenerator() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        HttpHeaders h = new DefaultHttpHeaders();
        h.add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        server.enqueue(new AbstractHandler() {
          EchoHandler chain = new EchoHandler();

          @Override
          public void handle(String target, org.eclipse.jetty.server.Request request, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
                  throws IOException, ServletException {
            assertNull(request.getHeader(TRANSFER_ENCODING.toString()));
            assertEquals(request.getHeader(CONTENT_LENGTH.toString()),//
                    Integer.toString("{}".getBytes(StandardCharsets.ISO_8859_1).length));
            chain.handle(target, request, httpServletRequest, httpServletResponse);
          }
        });

        byte[] bodyBytes = "{}".getBytes(StandardCharsets.ISO_8859_1);
        InputStream bodyStream = new ByteArrayInputStream(bodyBytes);

        client.preparePost(getTargetUrl())//
                .setHeaders(h)//
                .setBody(new InputStreamBodyGenerator(bodyStream, bodyBytes.length))//
                .execute(new AsyncCompletionHandlerAdapter() {

                  @Override
                  public Response onCompleted(Response response) {
                    assertEquals(response.getStatusCode(), 200);
                    assertEquals(response.getResponseBody(), "{}");
                    return response;
                  }
                }).get(TIMEOUT, SECONDS);
      }));
  }
}
