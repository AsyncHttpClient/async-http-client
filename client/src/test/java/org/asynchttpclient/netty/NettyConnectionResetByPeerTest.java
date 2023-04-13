/*
 *    Copyright (c) 2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty;

import io.github.artsok.RepeatedIfExceptionsTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.RequestBuilder;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.Exchanger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class NettyConnectionResetByPeerTest {

    private String resettingServerAddress;

    @BeforeEach
    public void setUp() {
        resettingServerAddress = createResettingServer();
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testAsyncHttpClientConnectionResetByPeer() throws InterruptedException {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setRequestTimeout(1500)
                .build();
        try (AsyncHttpClient asyncHttpClient = new DefaultAsyncHttpClient(config)) {
            asyncHttpClient.executeRequest(new RequestBuilder("GET").setUrl(resettingServerAddress)).get();
        } catch (Exception ex) {
            assertInstanceOf(Exception.class, ex);
        }
    }

    private static String createResettingServer() {
        return createServer(sock -> {
            try (Socket socket = sock) {
                socket.setSoLinger(true, 0);
                InputStream inputStream = socket.getInputStream();
                //to not eliminate read
                OutputStream os = new OutputStream() {
                    @Override
                    public void write(int b) {
                        // Do nothing
                    }
                };
                os.write(startRead(inputStream));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static String createServer(Consumer<Socket> handler) {
        Exchanger<Integer> portHolder = new Exchanger<>();
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(0)) {
                portHolder.exchange(ss.getLocalPort());
                while (true) {
                    handler.accept(ss.accept());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread()
                            .interrupt();
                }
                throw new RuntimeException(e);
            }
        });
        t.setDaemon(true);
        t.start();
        return tryGetAddress(portHolder);
    }

    private static String tryGetAddress(Exchanger<Integer> portHolder) {
        try {
            return "http://localhost:" + portHolder.exchange(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static byte[] startRead(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4];
        int length = inputStream.read(buffer);
        return Arrays.copyOf(buffer, length);
    }
}
