/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * RFC 6455 section 4.1: if the {@code Sec-WebSocket-Accept} value in the server's opening handshake does
 * not match the expected base64 of SHA-1 of the nonce, the client MUST fail the WebSocket connection. It
 * must not go on to upgrade the pipeline or notify the listener of an open socket.
 */
public class WebSocketHandshakeValidationTest {

  private ServerSocket serverSocket;
  private Thread serverThread;

  @AfterMethod
  public void tearDown() throws Exception {
    if (serverSocket != null) {
      serverSocket.close();
    }
    if (serverThread != null) {
      serverThread.interrupt();
    }
  }

  // Completes the HTTP upgrade with a 101 but a Sec-WebSocket-Accept that does not match the key.
  private int startServerWithBadAccept() throws IOException {
    serverSocket = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
    int port = serverSocket.getLocalPort();
    serverThread = new Thread(() -> {
      try (Socket socket = serverSocket.accept()) {
        InputStream in = socket.getInputStream();
        int b3 = -1, b2 = -1, b1 = -1, b;
        while ((b = in.read()) != -1) {
          if (b3 == '\r' && b2 == '\n' && b1 == '\r' && b == '\n') {
            break;
          }
          b3 = b2;
          b2 = b1;
          b1 = b;
        }
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: this-is-not-the-expected-accept\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(5000);
      } catch (Exception ignored) {
      }
    });
    serverThread.setDaemon(true);
    serverThread.start();
    return port;
  }

  @Test(timeOut = 30000)
  public void doesNotUpgradeOnInvalidAcceptKey() throws Exception {
    int port = startServerWithBadAccept();
    CountDownLatch openLatch = new CountDownLatch(1);

    try (AsyncHttpClient c = asyncHttpClient()) {
      try {
        c.prepareGet("ws://localhost:" + port + "/")
                .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {
                  @Override
                  public void onOpen(WebSocket websocket) {
                    openLatch.countDown();
                  }

                  @Override
                  public void onClose(WebSocket websocket, int code, String reason) {
                  }

                  @Override
                  public void onError(Throwable t) {
                  }
                }).build()).get();
        fail("the handshake must fail when Sec-WebSocket-Accept is invalid");
      } catch (ExecutionException expected) {
        // the request is aborted with "Invalid challenge"
      }

      assertFalse(openLatch.await(2, TimeUnit.SECONDS),
              "onOpen must not fire when the server Sec-WebSocket-Accept is invalid");
    }
  }

  // Reads the request, then stalls well past the client's request timeout before sending a perfectly
  // valid 101, so the upgrade response lands on a future the timeout has already aborted.
  private int startServerWithLateHandshake(long delayMillis) throws IOException {
    serverSocket = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
    int port = serverSocket.getLocalPort();
    serverThread = new Thread(() -> {
      try (Socket socket = serverSocket.accept()) {
        InputStream in = socket.getInputStream();
        StringBuilder request = new StringBuilder();
        int b3 = -1, b2 = -1, b1 = -1, b;
        while ((b = in.read()) != -1) {
          request.append((char) b);
          if (b3 == '\r' && b2 == '\n' && b1 == '\r' && b == '\n') {
            break;
          }
          b3 = b2;
          b2 = b1;
          b1 = b;
        }

        // Echo back the accept value the client expects, so the only reason not to upgrade is that the
        // exchange is already over.
        String key = null;
        for (String line : request.toString().split("\r\n")) {
          if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, "Sec-WebSocket-Key:".length())) {
            key = line.substring("Sec-WebSocket-Key:".length()).trim();
          }
        }

        Thread.sleep(delayMillis);

        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + WebSocketUtils.getAcceptKey(key) + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(5000);
      } catch (Exception ignored) {
      }
    });
    serverThread.setDaemon(true);
    serverThread.start();
    return port;
  }

  /**
   * End-to-end contract: a 101 that arrives after the request timeout must never surface as onOpen. Two
   * independent things uphold it - NettyRequestSender.abort marks the channel discarded before it completes
   * the future, and WebSocketHandler drops an upgrade response landing on a finished future. The second is
   * what covers the paths where the first does not run in that order (see
   * {@code WebSocketHandlerDoneFutureTest}, which pins that guard directly).
   */
  @Test(timeOut = 30000)
  public void doesNotUpgradeAfterTheFutureWasAborted() throws Exception {
    int port = startServerWithLateHandshake(2000);

    CountDownLatch openLatch = new CountDownLatch(1);
    CountDownLatch errorLatch = new CountDownLatch(1);

    AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setRequestTimeout(500)
            .build();
    try (AsyncHttpClient c = asyncHttpClient(config)) {
      try {
        c.prepareGet("ws://localhost:" + port + "/")
                .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {
                  @Override
                  public void onOpen(WebSocket websocket) {
                    openLatch.countDown();
                  }

                  @Override
                  public void onClose(WebSocket websocket, int code, String reason) {
                  }

                  @Override
                  public void onError(Throwable t) {
                    errorLatch.countDown();
                  }
                }).build()).get();
        fail("the request must fail on the request timeout");
      } catch (ExecutionException expected) {
        // TimeoutException
      }

      assertTrue(errorLatch.await(2, TimeUnit.SECONDS), "onError must fire when the request times out");
      assertFalse(openLatch.await(4, TimeUnit.SECONDS),
              "onOpen must not fire on a 101 that arrives after the future was aborted");
    }
  }
}
