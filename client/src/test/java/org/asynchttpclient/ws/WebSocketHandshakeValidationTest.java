/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.ws;

import org.asynchttpclient.AsyncHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WebSocketHandshakeValidationTest {

    private ServerSocket serverSocket;
    private Thread serverThread;

    @AfterEach
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

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 30000)
    public void doesNotUpgradeOnInvalidAcceptKey() throws Exception {
        int port = startServerWithBadAccept();
        CountDownLatch openLatch = new CountDownLatch(1);

        try (AsyncHttpClient c = asyncHttpClient()) {
            assertThrows(ExecutionException.class, () -> c.prepareGet("ws://localhost:" + port + "/")
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
                    }).build()).get());

            assertFalse(openLatch.await(2, TimeUnit.SECONDS),
                    "onOpen must not fire when the server Sec-WebSocket-Accept is invalid");
        }
    }
}
