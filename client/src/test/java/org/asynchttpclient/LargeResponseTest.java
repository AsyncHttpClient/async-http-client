/*
 *    Copyright (c) 2015-2025 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.handler.codec.http.HttpHeaders;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;


@ExtendWith(NettyLeakDetectorExtension.class)
public class LargeResponseTest {
    private static Logger LOG = LoggerFactory.getLogger(LargeResponseTest.class);
    private static final int textSize = 200_000;
    private static final byte[] textBytes = "z".repeat(textSize).getBytes(StandardCharsets.UTF_8);

    private static final long responseSize = ((long)textSize) * (2_000_000L);

    private static HttpServer HTTP_SERVER;

    private static AsyncHttpClient createClient() {
        AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setEnableAutomaticDecompression(true)
                .setCompressionEnforced(true)
                .setReadTimeout(Duration.ofMinutes(15))
                .setRequestTimeout(Duration.ofMinutes(15))
                .setConnectTimeout(Duration.ofSeconds(1))
                .build();
        return new DefaultAsyncHttpClient(config);
    }

    @BeforeAll
    static void setupServer() throws Exception {
        HTTP_SERVER = HttpServer.create(new InetSocketAddress(0), 0);

        HTTP_SERVER.createContext("/large").setHandler(new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange)
                    throws IOException {
                exchange.sendResponseHeaders(200, 0);
                long bytesWritten = 0;
                OutputStream out = exchange.getResponseBody();
                while (bytesWritten < responseSize) {
                    out.write(textBytes);
                    out.flush();
                    bytesWritten += textBytes.length;
                }
                out.close();
            }
        });

        HTTP_SERVER.start();
    }

    @AfterAll
    static void stopServer() {
        if (HTTP_SERVER != null) {
            HTTP_SERVER.stop(0);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void handleLargeResponse() throws Throwable {
        AtomicInteger status = new AtomicInteger(-1);
        AtomicLong bytesReceived = new AtomicLong();
        AtomicInteger throwableCount = new AtomicInteger();
        AtomicInteger bodyPartCount = new AtomicInteger();

        try (AsyncHttpClient client = createClient()) {
            Request request = new RequestBuilder("GET")
                    .setUrl("http://localhost:" + HTTP_SERVER.getAddress().getPort() + "/large")
                    .build();
            var future = client.executeRequest(request, new AsyncHandler<Object>() {
                    @Override
                    public State onStatusReceived(HttpResponseStatus responseStatus)
                        throws Exception {
                        status.set(responseStatus.getStatusCode());
                        return State.CONTINUE;
                    }

                    @Override
                    public State onHeadersReceived(HttpHeaders headers)
                        throws Exception {
                        return State.CONTINUE;
                    }

                    @Override
                    public State onBodyPartReceived(HttpResponseBodyPart bodyPart)
                        throws Exception {
                        bodyPartCount.incrementAndGet();
                        bytesReceived.addAndGet(bodyPart.length());
                        return State.CONTINUE;
                    }

                    @Override
                    public void onThrowable(Throwable t) {
                        throwableCount.incrementAndGet();
                    }

                    @Override
                    public @Nullable Object onCompleted()
                        throws Exception {
                        return null;
                    }
                });

                future.get(15, TimeUnit.MINUTES);

                assertEquals(200, status.get());
                assertEquals(0, throwableCount.get());
                assertEquals(responseSize, bytesReceived.get());

                LOG.info("Body part count: " + bodyPartCount);
                LOG.info("Body part average size: " + FileUtils.byteCountToDisplaySize(responseSize / bodyPartCount.get()));
                LOG.info("Response size: " + FileUtils.byteCountToDisplaySize(responseSize));
        }
    }
}
