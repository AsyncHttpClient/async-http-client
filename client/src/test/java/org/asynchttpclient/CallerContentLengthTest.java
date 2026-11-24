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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.multipart.MultipartBody;
import org.asynchttpclient.request.body.multipart.MultipartUtils;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code Content-Length} header that the caller sets goes on the wire as it is. Where AHC knows the length of the
 * body, a header that does not match it fails the request before anything is sent: the server would take the bytes
 * past the header's length for the start of the next request on the connection, and wait for missing ones.
 */
public class CallerContentLengthTest {

    // The ByteBuf bodies a test handed to the client, released after it.
    private static final Queue<ByteBuf> OWNED_BUFFERS = new ConcurrentLinkedQueue<>();

    // Not ASCII: the length of a String body is in bytes.
    private static final String CONTENT = "known content, \u00e4";
    private static final byte[] BYTES = CONTENT.getBytes(UTF_8);

    @TempDir
    static Path tempDir;

    private ServerSocket server;
    private ExecutorService executor;

    @BeforeEach
    public void setUp() throws IOException {
        server = new ServerSocket(0);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    public void tearDown() throws IOException {
        executor.shutdownNow();
        server.close();
        // The caller owns a ByteBuf body and releases it once the request is done. AHC sends retained duplicates and
        // releases those, so this release is the last one.
        for (ByteBuf buffer; (buffer = OWNED_BUFFERS.poll()) != null; ) {
            assertTrue(buffer.release(), "a reference to the ByteBuf body was kept");
        }
    }

    /**
     * The bodies whose length AHC knows from the request alone.
     */
    private enum KnownBody {
        BYTES {
            @Override
            void set(BoundRequestBuilder request) {
                request.setBody(CallerContentLengthTest.BYTES);
            }
        },
        COMPOSITE_BYTES {
            @Override
            void set(BoundRequestBuilder request) {
                request.setBody(Arrays.asList(Arrays.copyOfRange(CallerContentLengthTest.BYTES, 0, 5),
                        Arrays.copyOfRange(CallerContentLengthTest.BYTES, 5, CallerContentLengthTest.BYTES.length)));
            }
        },
        STRING {
            @Override
            void set(BoundRequestBuilder request) {
                request.setBody(CONTENT);
            }
        },
        BYTE_BUFFER {
            @Override
            void set(BoundRequestBuilder request) {
                request.setBody(ByteBuffer.wrap(CallerContentLengthTest.BYTES));
            }
        },
        BYTE_BUF {
            @Override
            void set(BoundRequestBuilder request) {
                ByteBuf buffer = Unpooled.wrappedBuffer(CallerContentLengthTest.BYTES);
                OWNED_BUFFERS.add(buffer);
                request.setBody(buffer);
            }
        },
        FORM {
            @Override
            void set(BoundRequestBuilder request) {
                request.addFormParam("content", CONTENT);
            }

            @Override
            byte[] expected() {
                return "content=known+content%2C+%C3%A4".getBytes(US_ASCII);
            }
        },
        FILE {
            @Override
            void set(BoundRequestBuilder request) throws IOException {
                request.setBody(file());
            }
        },
        FILE_REGION {
            @Override
            void set(BoundRequestBuilder request) throws IOException {
                request.setBody(new FileBodyGenerator(file(), 6, 7));
            }

            @Override
            byte[] expected() {
                return "content".getBytes(US_ASCII);
            }
        },
        NONE {
            @Override
            void set(BoundRequestBuilder request) {
            }

            @Override
            byte[] expected() {
                return new byte[0];
            }
        };

        abstract void set(BoundRequestBuilder request) throws IOException;

        byte[] expected() {
            return CallerContentLengthTest.BYTES;
        }

        static File file() throws IOException {
            Path file = tempDir.resolve("body.txt");
            if (!Files.exists(file)) {
                Files.write(file, CallerContentLengthTest.BYTES);
            }
            return file.toFile();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(KnownBody.class)
    public void knownBodyIsSentWithItsLengthInAContentLengthHeader(KnownBody body) throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(2));
        try (AsyncHttpClient client = client()) {
            BoundRequestBuilder upload = client.preparePost(url("/upload")).setHeader(CONTENT_LENGTH, body.expected().length);
            body.set(upload);
            assertEquals(200, upload.execute().get(10, SECONDS).getStatusCode());
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertArrayEquals(body.expected(), received.get(0).body);
        assertEquals("GET /next HTTP/1.1", received.get(1).requestLine);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(KnownBody.class)
    public void knownBodyFailsWithAContentLengthHeaderLongerThanItself(KnownBody body) throws Exception {
        int length = body.expected().length;
        assertRejectedBeforeConnecting("Content-Length header " + (length + 5) + " does not match the body's length " + length,
                client -> {
                    BoundRequestBuilder upload = client.preparePost(url("/upload")).setHeader(CONTENT_LENGTH, length + 5);
                    body.set(upload);
                    return upload.execute();
                });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = KnownBody.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    public void knownBodyFailsWithAContentLengthHeaderShorterThanItself(KnownBody body) throws Exception {
        int length = body.expected().length;
        assertRejectedBeforeConnecting("Content-Length header " + (length - 5) + " does not match the body's length " + length,
                client -> {
                    BoundRequestBuilder upload = client.preparePost(url("/upload")).setHeader(CONTENT_LENGTH, length - 5);
                    body.set(upload);
                    return upload.execute();
                });
    }

    // RFC 9110 section 8.6: a length is ASCII digits, without a sign, and the field has one.
    @ParameterizedTest(name = "header={0}")
    @ValueSource(strings = {"17, 17", "+17", "-0", "\u0661\u0667"})
    public void knownBodyFailsWhenItsContentLengthHeaderIsNotALength(String header) throws Exception {
        assertRejectedBeforeConnecting("Content-Length header [" + header + "] is not a length",
                client -> client.preparePost(url("/upload")).setHeader(CONTENT_LENGTH, header).setBody(BYTES).execute());
    }

    // The length of a multipart body depends on the boundary, which AHC picks when it builds the body unless the
    // Content-Type header has one. The check then runs when the body is built, and the request is not sent.
    @Test
    public void multipartBodyFailsWithAContentLengthHeaderOtherThanItsLength() throws Exception {
        assertRejectedWithoutSending("Content-Length header 10 does not match the body's length ",
                client -> client.preparePost(url("/upload"))
                        .setHeader(CONTENT_LENGTH, 10)
                        .addBodyPart(new StringPart("content", CONTENT))
                        .execute());
    }

    @Test
    public void multipartBodyIsSentWithItsLengthInAContentLengthHeader() throws Exception {
        String contentType = "multipart/form-data; boundary=callerBoundary";
        List<Part> parts = List.of(new StringPart("content", CONTENT));
        HttpHeaders headers = new DefaultHttpHeaders().set(CONTENT_TYPE, contentType);
        long length;
        try (MultipartBody body = MultipartUtils.newMultipartBody(parts, headers)) {
            length = body.getContentLength();
        }

        Future<List<Exchange>> exchanges = executor.submit(() -> serve(2));
        try (AsyncHttpClient client = client()) {
            BoundRequestBuilder upload = client.preparePost(url("/upload"))
                    .setHeader(CONTENT_TYPE, contentType)
                    .setHeader(CONTENT_LENGTH, length);
            parts.forEach(upload::addBodyPart);
            assertEquals(200, upload.execute().get(10, SECONDS).getStatusCode());
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertEquals(length, received.get(0).body.length);
        assertTrue(new String(received.get(0).body, ISO_8859_1).endsWith("\r\n--callerBoundary--\r\n"));
        assertEquals("GET /next HTTP/1.1", received.get(1).requestLine);
    }

    // A BodyGenerator's body has its length once it is created, so the check runs then, and the body is closed.
    @Test
    public void bodyGeneratorFailsWithAContentLengthHeaderOtherThanItsLength() throws Exception {
        KnownLengthGenerator generator = new KnownLengthGenerator();
        assertRejectedWithoutSending("Content-Length header 10 does not match the body's length " + BYTES.length,
                client -> client.preparePost(url("/upload")).setHeader(CONTENT_LENGTH, 10).setBody(generator).execute());
        assertEquals(1, generator.bodies.size());
        assertTrue(generator.bodies.get(0).closed, "the body was not closed");
    }

    // On a pooled connection, the late check runs after the request took the connection. The request is not sent, its
    // body is closed, and the connection is closed rather than kept from the pool: with a limit of one connection, the
    // next request opens a new one.
    @Test
    public void bodyGeneratorFailingTheCheckOnAPooledConnectionLeavesTheLimitFree() throws Exception {
        KnownLengthGenerator generator = new KnownLengthGenerator();
        Future<List<String>> requestLines = executor.submit(() -> serveConnections(2));
        try (AsyncHttpClient client = client()) {
            assertEquals(200, client.prepareGet(url("/first")).execute().get(10, SECONDS).getStatusCode());
            assertRejected("Content-Length header 10 does not match the body's length " + BYTES.length,
                    c -> c.preparePost(url("/upload")).setHeader(CONTENT_LENGTH, 10).setBody(generator).execute(), client);
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        assertEquals(1, generator.bodies.size());
        assertTrue(generator.bodies.get(0).closed, "the body was not closed");
        assertEquals(List.of("GET /first HTTP/1.1", "GET /next HTTP/1.1"), requestLines.get(10, SECONDS));
    }

    // A redirect that drops the body drops the caller's Content-Length header with it.
    @Test
    public void redirectThatDropsTheBodyDropsItsContentLengthHeader() throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(2, "HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\n\r\n"));
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setMaxConnections(1)
                .setMaxRequestRetry(0)
                .setFollowRedirect(true)
                .setRequestTimeout(Duration.ofSeconds(10)))) {
            assertEquals(200, client.preparePost(url("/upload"))
                    .setHeader(CONTENT_LENGTH, BYTES.length)
                    .setBody(BYTES)
                    .execute()
                    .get(10, SECONDS)
                    .getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertArrayEquals(BYTES, received.get(0).body);
        assertEquals("GET /next HTTP/1.1", received.get(1).requestLine);
        assertEquals(0, received.get(1).body.length);
    }

    // The request fails before it takes the pooled connection, so with a limit of one connection, the next request
    // still gets it.
    private void assertRejectedBeforeConnecting(String message, ThrowingFunction<AsyncHttpClient, Future<Response>> rejected)
            throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(2));
        try (AsyncHttpClient client = client()) {
            assertEquals(200, client.prepareGet(url("/first")).execute().get(10, SECONDS).getStatusCode());
            assertRejected(message, rejected, client);
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertEquals("GET /first HTTP/1.1", received.get(0).requestLine);
        assertEquals("GET /next HTTP/1.1", received.get(1).requestLine);
    }

    // The request fails before it is sent: here, without a proxy or a pooled connection, before a connection opens for
    // it, so the server only sees the next request.
    private void assertRejectedWithoutSending(String messageStart, ThrowingFunction<AsyncHttpClient, Future<Response>> rejected)
            throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(1));
        try (AsyncHttpClient client = client()) {
            assertRejected(messageStart, rejected, client);
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertEquals("GET /next HTTP/1.1", received.get(0).requestLine);
    }

    private static void assertRejected(String messageStart, ThrowingFunction<AsyncHttpClient, Future<Response>> rejected,
                                       AsyncHttpClient client) {
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> rejected.apply(client).get(10, SECONDS));
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().startsWith(messageStart), thrown.getCause().getMessage());
    }

    private AsyncHttpClient client() {
        return asyncHttpClient(config()
                .setMaxConnections(1)
                .setMaxRequestRetry(0)
                .setRequestTimeout(Duration.ofSeconds(10)));
    }

    private String url(String path) {
        return "http://localhost:" + server.getLocalPort() + path;
    }

    private List<Exchange> serve(int requests) throws IOException {
        return serve(requests, null);
    }

    // Reads up to the given number of requests with a Content-Length on one connection. Answers the first with the
    // given response, if any, and every other one with a 200. Stops when the client closes the connection.
    private List<Exchange> serve(int requests, String firstResponse) throws IOException {
        List<Exchange> exchanges = new ArrayList<>();
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while (exchanges.size() < requests) {
                String head = readHead(in);
                if (head == null) {
                    break;
                }
                int contentLength = 0;
                for (String line : head.split("\r\n")) {
                    if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                exchanges.add(new Exchange(head.substring(0, head.indexOf("\r\n")), in.readNBytes(contentLength)));
                String response = exchanges.size() == 1 && firstResponse != null
                        ? firstResponse : "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
                out.write(response.getBytes(US_ASCII));
                out.flush();
            }
        }
        return exchanges;
    }

    // Answers requests with a 200, on one connection after the other, until it has the given number, and returns their
    // request lines.
    private List<String> serveConnections(int requests) throws IOException {
        List<String> requestLines = new ArrayList<>();
        server.setSoTimeout(10_000);
        while (requestLines.size() < requests) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(10_000);
                InputStream in = socket.getInputStream();
                String head;
                while (requestLines.size() < requests && (head = readHead(in)) != null) {
                    requestLines.add(head.substring(0, head.indexOf("\r\n")));
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII));
                }
            }
        }
        return requestLines;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            String text = head.toString(US_ASCII);
            if (text.endsWith("\r\n\r\n")) {
                return text;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws Exception;
    }

    private static final class Exchange {

        final String requestLine;
        final byte[] body;

        Exchange(String requestLine, byte[] body) {
            this.requestLine = requestLine;
            this.body = body;
        }
    }

    /**
     * Creates bodies of a length known up front, as {@code ByteArrayBodyGenerator} does, and records them.
     */
    private static final class KnownLengthGenerator implements BodyGenerator {

        final List<RecordingBody> bodies = new ArrayList<>();

        @Override
        public Body createBody() {
            RecordingBody body = new RecordingBody();
            bodies.add(body);
            return body;
        }
    }

    private static final class RecordingBody implements Body {

        private int position;
        volatile boolean closed;

        @Override
        public long getContentLength() {
            return BYTES.length;
        }

        @Override
        public BodyState transferTo(ByteBuf target) {
            if (position == BYTES.length) {
                return BodyState.STOP;
            }
            int count = Math.min(target.writableBytes(), BYTES.length - position);
            target.writeBytes(BYTES, position, count);
            position += count;
            return BodyState.CONTINUE;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
