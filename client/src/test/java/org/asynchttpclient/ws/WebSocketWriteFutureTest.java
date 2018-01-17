/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.ws;

import org.asynchttpclient.AsyncHttpClient;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;

public class WebSocketWriteFutureTest extends AbstractBasicWebSocketTest {

  @Override
  public WebSocketHandler configureHandler() {
    return new WebSocketHandler() {
      @Override
      public void configure(WebSocketServletFactory factory) {
        factory.register(EchoWebSocket.class);
      }
    };
  }

  @Test(timeOut = 60000)
  public void sendTextMessage() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      getWebSocket(c).sendTextFrame("TEXT").get(10, TimeUnit.SECONDS);
    }
  }

  @Test(timeOut = 60000, expectedExceptions = ExecutionException.class)
  public void sendTextMessageExpectFailure() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      WebSocket websocket = getWebSocket(c, closeLatch);
      websocket.sendCloseFrame();
      closeLatch.await(1, TimeUnit.SECONDS);
      websocket.sendTextFrame("TEXT").get(10, TimeUnit.SECONDS);
    }
  }

  @Test(timeOut = 60000)
  public void sendByteMessage() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      getWebSocket(c).sendBinaryFrame("BYTES".getBytes()).get(10, TimeUnit.SECONDS);
    }
  }

  @Test(timeOut = 60000, expectedExceptions = ExecutionException.class)
  public void sendByteMessageExpectFailure() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      WebSocket websocket = getWebSocket(c, closeLatch);
      websocket.sendCloseFrame();
      closeLatch.await(1, TimeUnit.SECONDS);
      websocket.sendBinaryFrame("BYTES".getBytes()).get(10, TimeUnit.SECONDS);
    }
  }

  @Test(timeOut = 60000)
  public void sendPingMessage() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      getWebSocket(c).sendPingFrame("PING".getBytes()).get(10, TimeUnit.SECONDS);
    }
  }

  @Test(timeOut = 60000, expectedExceptions = ExecutionException.class)
  public void sendPingMessageExpectFailure() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      WebSocket websocket = getWebSocket(c, closeLatch);
      websocket.sendCloseFrame();
      closeLatch.await(1, TimeUnit.SECONDS);
      websocket.sendPingFrame("PING".getBytes()).get(10, TimeUnit.SECONDS);
    }
  }

  @Test(timeOut = 60000)
  public void sendPongMessage() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      getWebSocket(c).sendPongFrame("PONG".getBytes()).get(10, TimeUnit.SECONDS);
    }
  }

  @Test(timeOut = 60000, expectedExceptions = ExecutionException.class)
  public void sendPongMessageExpectFailure() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      WebSocket websocket = getWebSocket(c, closeLatch);
      websocket.sendCloseFrame();
      closeLatch.await(1, TimeUnit.SECONDS);
      websocket.sendPongFrame("PONG".getBytes()).get(1, TimeUnit.SECONDS);
    }
  }

  @Test(timeOut = 60000)
  public void streamBytes() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      getWebSocket(c).sendBinaryFrame("STREAM".getBytes(), true, 0).get(1, TimeUnit.SECONDS);
    }
  }

  @Test(timeOut = 60000, expectedExceptions = ExecutionException.class)
  public void streamBytesExpectFailure() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      WebSocket websocket = getWebSocket(c, closeLatch);
      websocket.sendCloseFrame();
      closeLatch.await(1, TimeUnit.SECONDS);
      websocket.sendBinaryFrame("STREAM".getBytes(), true, 0).get(1, TimeUnit.SECONDS);
    }
  }

  @Test
  public void streamText() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      getWebSocket(c).sendTextFrame("STREAM", true, 0).get(1, TimeUnit.SECONDS);
    }
  }

  @Test(expectedExceptions = ExecutionException.class)
  public void streamTextExpectFailure() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      WebSocket websocket = getWebSocket(c, closeLatch);
      websocket.sendCloseFrame();
      closeLatch.await(1, TimeUnit.SECONDS);
      websocket.sendTextFrame("STREAM", true, 0).get(1, TimeUnit.SECONDS);
    }
  }

  private WebSocket getWebSocket(final AsyncHttpClient c) throws Exception {
    return c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().build()).get();
  }

  private WebSocket getWebSocket(final AsyncHttpClient c, CountDownLatch closeLatch) throws Exception {
    return c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

      @Override
      public void onOpen(WebSocket websocket) {
      }

      @Override
      public void onError(Throwable t) {
      }

      @Override
      public void onClose(WebSocket websocket, int code, String reason) {
        closeLatch.countDown();
      }
    }).build()).get();
  }
}
