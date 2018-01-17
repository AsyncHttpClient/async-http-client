/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.ws;

import org.asynchttpclient.AsyncHttpClient;
import org.testng.annotations.Test;

import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TextMessageTest extends AbstractBasicWebSocketTest {

  @Test(timeOut = 60000)
  public void onOpen() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<String> text = new AtomicReference<>("");

      c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

        @Override
        public void onOpen(WebSocket websocket) {
          text.set("OnOpen");
          latch.countDown();
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
        }

        @Override
        public void onError(Throwable t) {
          t.printStackTrace();
          latch.countDown();
        }
      }).build()).get();

      latch.await();
      assertEquals(text.get(), "OnOpen");
    }
  }

  @Test(timeOut = 60000)
  public void onEmptyListenerTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      WebSocket websocket = null;
      try {
        websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().build()).get();
      } catch (Throwable t) {
        fail();
      }
      assertTrue(websocket != null);
    }
  }

  @Test(timeOut = 60000, expectedExceptions = UnknownHostException.class)
  public void onFailureTest() throws Throwable {
    try (AsyncHttpClient c = asyncHttpClient()) {
      c.prepareGet("ws://abcdefg").execute(new WebSocketUpgradeHandler.Builder().build()).get();
    } catch (ExecutionException e) {
      throw e.getCause();
    }
  }

  @Test(timeOut = 60000)
  public void onTimeoutCloseTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<String> text = new AtomicReference<>("");

      c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

        @Override
        public void onOpen(WebSocket websocket) {
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
          text.set("OnClose");
          latch.countDown();
        }

        @Override
        public void onError(Throwable t) {
          t.printStackTrace();
          latch.countDown();
        }
      }).build()).get();

      latch.await();
      assertEquals(text.get(), "OnClose");
    }
  }

  @Test(timeOut = 60000)
  public void onClose() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<String> text = new AtomicReference<>("");

      WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

        @Override
        public void onOpen(WebSocket websocket) {
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
          text.set("OnClose");
          latch.countDown();
        }

        @Override
        public void onError(Throwable t) {
          t.printStackTrace();
          latch.countDown();
        }
      }).build()).get();

      websocket.sendCloseFrame();

      latch.await();
      assertEquals(text.get(), "OnClose");
    }
  }

  @Test(timeOut = 60000)
  public void echoText() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<String> text = new AtomicReference<>("");

      WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

        @Override
        public void onTextFrame(String payload, boolean finalFragment, int rsv) {
          text.set(payload);
          latch.countDown();
        }

        @Override
        public void onOpen(WebSocket websocket) {
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
          latch.countDown();
        }

        @Override
        public void onError(Throwable t) {
          t.printStackTrace();
          latch.countDown();
        }
      }).build()).get();

      websocket.sendTextFrame("ECHO");

      latch.await();
      assertEquals(text.get(), "ECHO");
    }
  }

  @Test(timeOut = 60000)
  public void echoDoubleListenerText() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(2);
      final AtomicReference<String> text = new AtomicReference<>("");

      WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

        @Override
        public void onTextFrame(String payload, boolean finalFragment, int rsv) {
          text.set(payload);
          latch.countDown();
        }

        @Override
        public void onOpen(WebSocket websocket) {
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
          latch.countDown();
        }

        @Override
        public void onError(Throwable t) {
          t.printStackTrace();
          latch.countDown();
        }
      }).addWebSocketListener(new WebSocketListener() {

        @Override
        public void onTextFrame(String payload, boolean finalFragment, int rsv) {
          text.set(text.get() + payload);
          latch.countDown();
        }

        @Override
        public void onOpen(WebSocket websocket) {
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
          latch.countDown();
        }

        @Override
        public void onError(Throwable t) {
          t.printStackTrace();
          latch.countDown();
        }
      }).build()).get();

      websocket.sendTextFrame("ECHO");

      latch.await();
      assertEquals(text.get(), "ECHOECHO");
    }
  }

  @Test
  public void echoTwoMessagesTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(2);
      final AtomicReference<String> text = new AtomicReference<>("");

      c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

        @Override
        public void onTextFrame(String payload, boolean finalFragment, int rsv) {
          text.set(text.get() + payload);
          latch.countDown();
        }

        @Override
        public void onOpen(WebSocket websocket) {
          websocket.sendTextFrame("ECHO");
          websocket.sendTextFrame("ECHO");
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
          latch.countDown();
        }

        @Override
        public void onError(Throwable t) {
          t.printStackTrace();
          latch.countDown();
        }
      }).build()).get();

      latch.await();
      assertEquals(text.get(), "ECHOECHO");
    }
  }

  @Test
  public void echoFragments() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<String> text = new AtomicReference<>("");

      WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

        @Override
        public void onTextFrame(String payload, boolean finalFragment, int rsv) {
          text.set(payload);
          latch.countDown();
        }

        @Override
        public void onOpen(WebSocket websocket) {
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
          latch.countDown();
        }

        @Override
        public void onError(Throwable t) {
          t.printStackTrace();
          latch.countDown();
        }
      }).build()).get();

      websocket.sendTextFrame("ECHO", false, 0);
      websocket.sendContinuationFrame("ECHO", true, 0);

      latch.await();
      assertEquals(text.get(), "ECHOECHO");
    }
  }

  @Test(timeOut = 60000)
  public void echoTextAndThenClose() throws Throwable {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch textLatch = new CountDownLatch(1);
      final CountDownLatch closeLatch = new CountDownLatch(1);
      final AtomicReference<String> text = new AtomicReference<>("");

      final WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

        @Override
        public void onTextFrame(String payload, boolean finalFragment, int rsv) {
          text.set(text.get() + payload);
          textLatch.countDown();
        }

        @Override
        public void onOpen(WebSocket websocket) {
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
          closeLatch.countDown();
        }

        @Override
        public void onError(Throwable t) {
          t.printStackTrace();
          closeLatch.countDown();
        }
      }).build()).get();

      websocket.sendTextFrame("ECHO");
      textLatch.await();

      websocket.sendTextFrame("CLOSE");
      closeLatch.await();

      assertEquals(text.get(), "ECHO");
    }
  }
}
