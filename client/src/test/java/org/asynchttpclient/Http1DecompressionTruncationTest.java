/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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

import com.aayushatharva.brotli4j.encoder.Encoder;
import com.github.luben.zstd.Zstd;
import com.sun.net.httpserver.HttpServer;
import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.compression.SnappyFrameEncoder;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.netty.handler.Http1ContentDecompressor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A compressed HTTP/1.1 response body that ends in the middle of its compressed stream fails the request, as the
 * caller sees it.
 *
 * @see Http1ContentDecompressor
 */
@ExtendWith(NettyLeakDetectorExtension.class)
public class Http1DecompressionTruncationTest {

    private static final byte[] PAYLOAD = payload();

    private static byte[] payload() {
        byte[] payload = new byte[300 * 1024];
        new Random(23).nextBytes(payload);
        return payload;
    }

    private static byte[] compress(String contentEncoding, byte[] payload) throws Throwable {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        switch (contentEncoding) {
            case "gzip":
                try (OutputStream out = new GZIPOutputStream(compressed)) {
                    out.write(payload);
                }
                return compressed.toByteArray();
            case "deflate":
                try (OutputStream out = new DeflaterOutputStream(compressed)) {
                    out.write(payload);
                }
                return compressed.toByteArray();
            case "br":
                Brotli.ensureAvailability();
                return Encoder.compress(payload);
            case "zstd":
                return Zstd.compress(payload);
            case "snappy":
                EmbeddedChannel encoder = new EmbeddedChannel(new SnappyFrameEncoder());
                encoder.writeOutbound(Unpooled.wrappedBuffer(payload));
                encoder.finish();
                for (ByteBuf buf; (buf = encoder.readOutbound()) != null; ) {
                    buf.readBytes(compressed, buf.readableBytes());
                    buf.release();
                }
                return compressed.toByteArray();
            default:
                throw new IllegalArgumentException(contentEncoding);
        }
    }

    /**
     * Returns {@code false} if the connection ended instead.
     */
    private static boolean readRequestHead(BufferedReader in) throws IOException {
        String line = in.readLine();
        for (; line != null && !line.isEmpty(); line = in.readLine()) {
            // The requests have no body.
        }
        return line != null;
    }

    /**
     * Serves {@code body} delimited by the connection, closing it after the last byte.
     */
    private static Response get(String contentEncoding, byte[] body) throws Exception {
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
             AsyncHttpClient client = asyncHttpClient(config().setMaxRequestRetry(0))) {
            Future<?> serving = server.submit(() -> {
                try (Socket socket = listener.accept()) {
                    readRequestHead(new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)));
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Encoding: " + contentEncoding
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                }
                return null;
            });
            Response response = client.prepareGet("http://127.0.0.1:" + listener.getLocalPort() + '/')
                    .execute().get(10, SECONDS);
            serving.get(10, SECONDS);
            return response;
        } finally {
            server.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "deflate", "br", "zstd", "snappy"})
    public void aBodyThatTheConnectionEndsInTheMiddleOfItsStreamFails(String contentEncoding) throws Throwable {
        byte[] compressed = compress(contentEncoding, PAYLOAD);
        assertArrayEquals(PAYLOAD, get(contentEncoding, compressed).getResponseBodyAsBytes());

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> get(contentEncoding, Arrays.copyOf(compressed, compressed.length - 3)));
        assertInstanceOf(DecompressionException.class, failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "deflate", "br", "zstd", "snappy"})
    public void aCutOffBodyIsNotPassedToTheIOExceptionFilters(String contentEncoding) throws Throwable {
        // zstd-jni reports a truncated stream with an IOException, which must not reach the filters as an I/O failure
        // of the connection. With a Content-Length, the client has to close the connection itself.
        byte[] compressed = compress(contentEncoding, PAYLOAD);
        byte[] cutOff = Arrays.copyOf(compressed, compressed.length - 3);
        AtomicInteger filtered = new AtomicInteger();
        IOExceptionFilter replay = new IOExceptionFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                filtered.incrementAndGet();
                return new FilterContext.FilterContextBuilder<>(ctx).replayRequest(true).build();
            }
        };
        AtomicInteger requests = new AtomicInteger();
        List<Future<Boolean>> closedByClient = new CopyOnWriteArrayList<>();
        ExecutorService server = Executors.newCachedThreadPool();
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
             AsyncHttpClient client = asyncHttpClient(config().setMaxRequestRetry(0).addIOExceptionFilter(replay))) {
            server.submit(() -> {
                while (!listener.isClosed()) {
                    Socket socket = listener.accept();
                    // Added before it serves the response, so it is there once the client has seen the response.
                    FutureTask<Boolean> connection =
                            new FutureTask<>(() -> serveUntilClosed(socket, contentEncoding, cutOff, requests));
                    closedByClient.add(connection);
                    server.execute(connection);
                }
                return null;
            });
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.prepareGet("http://127.0.0.1:" + listener.getLocalPort() + '/').execute()
                            .get(10, SECONDS));
            assertInstanceOf(DecompressionException.class, failure.getCause());
            assertEquals(0, filtered.get(), "a cut-off body is not an I/O failure");
            assertEquals(1, requests.get());
            assertTrue(closedByClient.get(0).get(15, SECONDS), "the client must close the connection");
        } finally {
            server.shutdownNow();
        }
    }

    /**
     * Answers the first request with the cut-off body and every later one with a plain {@code ok}, and returns whether
     * the client closed the connection within 10 seconds.
     */
    private static boolean serveUntilClosed(Socket socket, String contentEncoding, byte[] cutOff,
                                            AtomicInteger requests) throws IOException {
        try (socket) {
            socket.setSoTimeout(10_000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = socket.getOutputStream();
            while (readRequestHead(in)) {
                if (requests.getAndIncrement() == 0) {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Encoding: " + contentEncoding + "\r\nContent-Length: "
                            + cutOff.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(cutOff);
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
                }
                out.flush();
            }
            return true;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (SocketException reset) {
            return true;
        }
    }

    @Test
    public void aReplayIsNotFailedByTheCutOffBodyItDrains() throws Throwable {
        // The replay drains the rest of the first response to pool its connection. That body is too short for any
        // output, so it is only at its end that the decompressor sees that nobody wants it.
        byte[] cutOff = Arrays.copyOf(compress("gzip", PAYLOAD), 6);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            if (requests.getAndIncrement() == 0) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(503, cutOff.length);
                exchange.getResponseBody().write(cutOff);
            } else {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("ok".getBytes(StandardCharsets.US_ASCII));
            }
            exchange.close();
        });
        server.start();
        ResponseFilter replayOn503 = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getResponseStatus().getStatusCode() == 503) {
                    return new FilterContext.FilterContextBuilder<>(ctx.getAsyncHandler(), ctx.getRequest())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
        try (AsyncHttpClient client = asyncHttpClient(config().addResponseFilter(replayOn503))) {
            Response response = client.prepareGet("http://127.0.0.1:" + server.getAddress().getPort() + '/')
                    .execute().get(10, SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals("ok", response.getResponseBody());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
