/*
 *    Copyright (c) 2015-2024 AsyncHttpClient Project. All rights reserved.
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

import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import com.aayushatharva.brotli4j.encoder.Encoder;
import com.github.luben.zstd.Zstd;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.handler.codec.compression.Brotli;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(NettyLeakDetectorExtension.class)
public class AutomaticDecompressionTest {
    private static final String UNCOMPRESSED_PAYLOAD = "a".repeat(50_000);

    private static HttpServer HTTP_SERVER;

    private static AsyncHttpClient createClient() {
        AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setEnableAutomaticDecompression(true)
                .setCompressionEnforced(true)
                .build();
        return new DefaultAsyncHttpClient(config);
    }

    @BeforeAll
    static void setupServer() throws Exception {
        HTTP_SERVER = HttpServer.create(new InetSocketAddress(0), 0);

        HTTP_SERVER.createContext("/br").setHandler(new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange)
                    throws IOException {
                validateAcceptEncodingHeader(exchange);
                exchange.getResponseHeaders().set("Content-Encoding", "br");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                Encoder.Parameters params = new Encoder.Parameters();
                BrotliOutputStream brotliOutputStream = new BrotliOutputStream(out, params);
                brotliOutputStream.write(UNCOMPRESSED_PAYLOAD.getBytes(StandardCharsets.UTF_8));
                brotliOutputStream.flush();
                brotliOutputStream.close();
            }
        });

        HTTP_SERVER.createContext("/zstd").setHandler(new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange)
                    throws IOException {
                validateAcceptEncodingHeader(exchange);
                exchange.getResponseHeaders().set("Content-Encoding", "zstd");
                byte[] compressedData = new byte[UNCOMPRESSED_PAYLOAD.length()];
                long n = Zstd.compress(compressedData, UNCOMPRESSED_PAYLOAD.getBytes(StandardCharsets.UTF_8), 2, true);
                exchange.sendResponseHeaders(200, n);
                OutputStream out = exchange.getResponseBody();
                out.write(compressedData, 0, (int) n);
                out.flush();
                out.close();
            }
        });

        HTTP_SERVER.createContext("/gzip").setHandler(new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange)
                    throws IOException {
                validateAcceptEncodingHeader(exchange);
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                GZIPOutputStream gzip = new GZIPOutputStream(out);
                gzip.write(UNCOMPRESSED_PAYLOAD.getBytes(StandardCharsets.UTF_8));
                gzip.flush();
                gzip.close();
            }
        });

        HTTP_SERVER.start();
    }

    private static void validateAcceptEncodingHeader(HttpExchange exchange) {
        Headers requestHeaders = exchange.getRequestHeaders();
        List<String> acceptEncodingList = requestHeaders.get("Accept-Encoding")
                .stream()
                .flatMap(x -> Arrays.asList(x.split(",")).stream())
                .collect(Collectors.toList());
        assertEquals(List.of("gzip", "deflate", "br", "zstd"), acceptEncodingList);
    }

    @AfterAll
    static void stopServer() {
        if (HTTP_SERVER != null) {
            HTTP_SERVER.stop(0);
        }
    }

    @Test
    void zstd() throws Throwable {
        io.netty.handler.codec.compression.Zstd.ensureAvailability();
        try (AsyncHttpClient client = createClient()) {
            Request request = new RequestBuilder("GET")
                    .setUrl("http://localhost:" + HTTP_SERVER.getAddress().getPort() + "/zstd")
                    .build();
            Response response = client.executeRequest(request).get();
            assertEquals(200, response.getStatusCode());
            assertEquals(UNCOMPRESSED_PAYLOAD, response.getResponseBody());
        }
    }

    @Test
    void brotli() throws Throwable {
        Brotli.ensureAvailability();
        try (AsyncHttpClient client = createClient()) {
            Request request = new RequestBuilder("GET")
                    .setUrl("http://localhost:" + HTTP_SERVER.getAddress().getPort() + "/br")
                    .build();
            Response response = client.executeRequest(request).get();
            assertEquals(200, response.getStatusCode());
            assertEquals(UNCOMPRESSED_PAYLOAD, response.getResponseBody());
        }
    }

    @Test
    void gzip() throws Throwable {
        try (AsyncHttpClient client = createClient()) {
            Request request = new RequestBuilder("GET")
                    .setUrl("http://localhost:" + HTTP_SERVER.getAddress().getPort() + "/gzip")
                    .build();
            Response response = client.executeRequest(request).get();
            assertEquals(200, response.getStatusCode());
            assertEquals(UNCOMPRESSED_PAYLOAD, response.getResponseBody());
        }
    }


}
