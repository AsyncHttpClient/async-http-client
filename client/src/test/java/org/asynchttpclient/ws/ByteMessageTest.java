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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.testng.Assert.assertEquals;

public class ByteMessageTest extends AbstractBasicWebSocketTest {

  private static final byte[] ECHO_BYTES = "ECHO".getBytes(StandardCharsets.UTF_8);

  private void echoByte0(boolean enableCompression) throws Exception {
    try (AsyncHttpClient c = asyncHttpClient(config().setEnablewebSocketCompression(enableCompression))) {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<byte[]> receivedBytes = new AtomicReference<>(new byte[0]);

      WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

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

        @Override
        public void onBinaryFrame(byte[] frame, boolean finalFragment, int rsv) {
          receivedBytes.set(frame);
          latch.countDown();
        }
      }).build()).get();

      websocket.sendBinaryFrame(ECHO_BYTES);

      latch.await();
      assertEquals(receivedBytes.get(), ECHO_BYTES);
    }
  }

  @Test
  public void echoByte() throws Exception {
    echoByte0(false);
  }

  @Test
  public void echoByteCompressed() throws Exception {
    echoByte0(true);
  }

  @Test
  public void echoTwoMessagesTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(2);
      final AtomicReference<byte[]> text = new AtomicReference<>(null);

      WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

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

        @Override
        public void onBinaryFrame(byte[] frame, boolean finalFragment, int rsv) {
          if (text.get() == null) {
            text.set(frame);
          } else {
            byte[] n = new byte[text.get().length + frame.length];
            System.arraycopy(text.get(), 0, n, 0, text.get().length);
            System.arraycopy(frame, 0, n, text.get().length, frame.length);
            text.set(n);
          }
          latch.countDown();
        }

      }).build()).get();

      websocket.sendBinaryFrame(ECHO_BYTES);
      websocket.sendBinaryFrame(ECHO_BYTES);

      latch.await();
      assertEquals(text.get(), "ECHOECHO".getBytes());
    }
  }

  @Test
  public void echoOnOpenMessagesTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(2);
      final AtomicReference<byte[]> text = new AtomicReference<>(null);

      c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

        @Override
        public void onOpen(WebSocket websocket) {
          websocket.sendBinaryFrame(ECHO_BYTES);
          websocket.sendBinaryFrame(ECHO_BYTES);
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

        @Override
        public void onBinaryFrame(byte[] frame, boolean finalFragment, int rsv) {
          if (text.get() == null) {
            text.set(frame);
          } else {
            byte[] n = new byte[text.get().length + frame.length];
            System.arraycopy(text.get(), 0, n, 0, text.get().length);
            System.arraycopy(frame, 0, n, text.get().length, frame.length);
            text.set(n);
          }
          latch.countDown();
        }

      }).build()).get();

      latch.await();
      assertEquals(text.get(), "ECHOECHO".getBytes());
    }
  }

  @Test
  public void echoFragments() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<byte[]> text = new AtomicReference<>(null);

      WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

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

        @Override
        public void onBinaryFrame(byte[] frame, boolean finalFragment, int rsv) {
          if (text.get() == null) {
            text.set(frame);
          } else {
            byte[] n = new byte[text.get().length + frame.length];
            System.arraycopy(text.get(), 0, n, 0, text.get().length);
            System.arraycopy(frame, 0, n, text.get().length, frame.length);
            text.set(n);
          }
          latch.countDown();
        }

      }).build()).get();
      websocket.sendBinaryFrame(ECHO_BYTES, false, 0);
      websocket.sendContinuationFrame(ECHO_BYTES, true, 0);
      latch.await();
      assertEquals(text.get(), "ECHOECHO".getBytes());
    }
  }
}
