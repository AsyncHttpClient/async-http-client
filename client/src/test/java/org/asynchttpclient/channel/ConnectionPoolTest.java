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
package org.asynchttpclient.channel;

import org.asynchttpclient.*;
import org.asynchttpclient.exception.TooManyConnectionsException;
import org.asynchttpclient.test.EventCollectingHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.EventCollectingHandler.*;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.testng.Assert.*;

public class ConnectionPoolTest extends AbstractBasicTest {

  @Test
  public void testMaxTotalConnections() throws Exception {
    try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setMaxConnections(1))) {
      String url = getTargetUrl();
      int i;
      Exception exception = null;
      for (i = 0; i < 3; i++) {
        try {
          logger.info("{} requesting url [{}]...", i, url);
          Response response = client.prepareGet(url).execute().get();
          logger.info("{} response [{}].", i, response);
        } catch (Exception ex) {
          exception = ex;
        }
      }
      assertNull(exception);
    }
  }

  @Test(expectedExceptions = TooManyConnectionsException.class)
  public void testMaxTotalConnectionsException() throws Throwable {
    try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setMaxConnections(1))) {
      String url = getTargetUrl();

      List<ListenableFuture<Response>> futures = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        logger.info("{} requesting url [{}]...", i, url);
        futures.add(client.prepareGet(url).execute());
      }

      Exception exception = null;
      for (ListenableFuture<Response> future : futures) {
        try {
          future.get();
        } catch (Exception ex) {
          exception = ex;
          break;
        }
      }

      assertNotNull(exception);
      throw exception.getCause();
    }
  }

  @Test(invocationCount = 100)
  public void asyncDoGetKeepAliveHandlerTest_channelClosedDoesNotFail() throws Exception {

    try (AsyncHttpClient client = asyncHttpClient()) {
      // Use a l in case the assert fail
      final CountDownLatch l = new CountDownLatch(2);

      final Map<String, Boolean> remoteAddresses = new ConcurrentHashMap<>();

      AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

        @Override
        public Response onCompleted(Response response) {
          logger.debug("ON COMPLETED INVOKED " + response.getHeader("X-KEEP-ALIVE"));
          try {
            assertEquals(response.getStatusCode(), 200);
            remoteAddresses.put(response.getHeader("X-KEEP-ALIVE"), true);
          } finally {
            l.countDown();
          }
          return response;
        }

        @Override
        public void onThrowable(Throwable t) {
          try {
            super.onThrowable(t);
          } finally {
            l.countDown();
          }
        }
      };

      client.prepareGet(getTargetUrl()).execute(handler).get();
      server.stop();

      // Jetty 9.4.8 doesn't properly stop and restart (recreates ReservedThreadExecutors on start but still point to old offers threads to old ones)
      // instead of restarting, we create a fresh new one and have it bind on the same port
      server = new Server();
      ServerConnector newConnector = addHttpConnector(server);
      // make sure connector will restart with the port as it's originally dynamically allocated
      newConnector.setPort(port1);
      server.setHandler(configureHandler());
      server.start();

      client.prepareGet(getTargetUrl()).execute(handler);

      if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
        fail("Timed out");
      }

      assertEquals(remoteAddresses.size(), 2);
    }
  }

  @Test(expectedExceptions = TooManyConnectionsException.class)
  public void multipleMaxConnectionOpenTest() throws Throwable {
    try (AsyncHttpClient c = asyncHttpClient(config().setKeepAlive(true).setConnectTimeout(5000).setMaxConnections(1))) {
      String body = "hello there";

      // once
      Response response = c.preparePost(getTargetUrl()).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);

      assertEquals(response.getResponseBody(), body);

      // twice
      Exception exception = null;
      try {
        c.preparePost(String.format("http://localhost:%d/foo/test", port2)).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
        fail("Should throw exception. Too many connections issued.");
      } catch (Exception ex) {
        ex.printStackTrace();
        exception = ex;
      }
      assertNotNull(exception);
      throw exception.getCause();
    }
  }

  @Test
  public void multipleMaxConnectionOpenTestWithQuery() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient(config().setKeepAlive(true).setConnectTimeout(5000).setMaxConnections(1))) {
      String body = "hello there";

      // once
      Response response = c.preparePost(getTargetUrl() + "?foo=bar").setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);

      assertEquals(response.getResponseBody(), "foo_" + body);

      // twice
      Exception exception = null;
      try {
        response = c.preparePost(getTargetUrl()).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
      } catch (Exception ex) {
        ex.printStackTrace();
        exception = ex;
      }
      assertNull(exception);
      assertNotNull(response);
      assertEquals(response.getStatusCode(), 200);
    }
  }

  /**
   * This test just make sure the hack used to catch disconnected channel under win7 doesn't throw any exception. The onComplete method must be only called once.
   *
   * @throws Exception if something wrong happens.
   */
  @Test
  public void win7DisconnectTest() throws Exception {
    final AtomicInteger count = new AtomicInteger(0);

    try (AsyncHttpClient client = asyncHttpClient()) {
      AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

        @Override
        public Response onCompleted(Response response) throws Exception {

          count.incrementAndGet();
          StackTraceElement e = new StackTraceElement("sun.nio.ch.SocketDispatcher", "read0", null, -1);
          IOException t = new IOException();
          t.setStackTrace(new StackTraceElement[]{e});
          throw t;
        }
      };

      try {
        client.prepareGet(getTargetUrl()).execute(handler).get();
        fail("Must have received an exception");
      } catch (ExecutionException ex) {
        assertNotNull(ex);
        assertNotNull(ex.getCause());
        assertEquals(ex.getCause().getClass(), IOException.class);
        assertEquals(count.get(), 1);
      }
    }
  }

  @Test
  public void asyncHandlerOnThrowableTest() throws Exception {
    try (AsyncHttpClient client = asyncHttpClient()) {
      final AtomicInteger count = new AtomicInteger();
      final String THIS_IS_NOT_FOR_YOU = "This is not for you";
      final CountDownLatch latch = new CountDownLatch(16);
      for (int i = 0; i < 16; i++) {
        client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerBase() {
          @Override
          public Response onCompleted(Response response) throws Exception {
            throw new Exception(THIS_IS_NOT_FOR_YOU);
          }
        });

        client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerBase() {
          @Override
          public void onThrowable(Throwable t) {
            if (t.getMessage() != null && t.getMessage().equalsIgnoreCase(THIS_IS_NOT_FOR_YOU)) {
              count.incrementAndGet();
            }
          }

          @Override
          public Response onCompleted(Response response) {
            latch.countDown();
            return response;
          }
        });
      }
      latch.await(TIMEOUT, TimeUnit.SECONDS);
      assertEquals(count.get(), 0);
    }
  }

  @Test
  public void nonPoolableConnectionReleaseSemaphoresTest() throws Throwable {

    RequestBuilder request = get(getTargetUrl()).setHeader("Connection", "close");

    try (AsyncHttpClient client = asyncHttpClient(config().setMaxConnections(6).setMaxConnectionsPerHost(3))) {
      client.executeRequest(request).get();
      Thread.sleep(1000);
      client.executeRequest(request).get();
      Thread.sleep(1000);
      client.executeRequest(request).get();
      Thread.sleep(1000);
      client.executeRequest(request).get();
    }
  }

  @Test
  public void testPooledEventsFired() throws Exception {
    RequestBuilder request = get("http://localhost:" + port1 + "/Test");

    try (AsyncHttpClient client = asyncHttpClient()) {
      EventCollectingHandler firstHandler = new EventCollectingHandler();
      client.executeRequest(request, firstHandler).get(3, TimeUnit.SECONDS);
      firstHandler.waitForCompletion(3, TimeUnit.SECONDS);

      EventCollectingHandler secondHandler = new EventCollectingHandler();
      client.executeRequest(request, secondHandler).get(3, TimeUnit.SECONDS);
      secondHandler.waitForCompletion(3, TimeUnit.SECONDS);

      Object[] expectedEvents = new Object[]{CONNECTION_POOL_EVENT, CONNECTION_POOLED_EVENT, REQUEST_SEND_EVENT, HEADERS_WRITTEN_EVENT, STATUS_RECEIVED_EVENT,
              HEADERS_RECEIVED_EVENT, CONNECTION_OFFER_EVENT, COMPLETED_EVENT};

      assertEquals(secondHandler.firedEvents.toArray(), expectedEvents, "Got " + Arrays.toString(secondHandler.firedEvents.toArray()));
    }
  }
}
