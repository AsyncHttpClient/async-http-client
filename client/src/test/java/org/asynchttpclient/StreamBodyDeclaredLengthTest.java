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

import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.multipart.InputStreamPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An {@link InputStreamPart} or a stream body with a declared length sends that many bytes of its stream: not more, as
 * the server reads the bytes past the {@code Content-Length} as the start of the next request on the connection, and
 * not fewer, as the server then waits for the rest. A {@code Content-Length} header the caller sets on a stream body
 * declares its length too.
 */
public class StreamBodyDeclaredLengthTest {

    private static final String CONTENT = "declared content";
    private static final String EXTRA = "GET /smuggled HTTP/1.1\r\nHost: localhost\r\n\r\n";
    // Long enough for part of the body to go out before the stream ends.
    private static final int DECLARED = 100_000;
    private static final int SHORT = 60_000;

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
    }

    @ParameterizedTest(name = "zeroCopy={0}")
    @ValueSource(booleans = {true, false})
    public void streamPartLongerThanItsDeclaredLengthSendsOnlyThatLength(boolean zeroCopy) throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(2));
        ByteArrayInputStream stream = new ByteArrayInputStream((CONTENT + EXTRA).getBytes(US_ASCII));
        try (AsyncHttpClient client = client(zeroCopy)) {
            assertEquals(200, client.preparePost(url("/upload"))
                    .addBodyPart(new InputStreamPart("file", stream, "file.bin", CONTENT.length()))
                    .execute()
                    .get(10, SECONDS)
                    .getStatusCode());
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertTrue(received.get(0).body.contains("\r\n\r\n" + CONTENT + "\r\n--"), received.get(0).body);
        assertTrue(received.get(0).body.endsWith("--\r\n"), received.get(0).body);
        assertEquals("GET /next HTTP/1.1", received.get(1).requestLine);
        assertEquals(EXTRA.length(), stream.available());
    }

    @Test
    public void streamBodyLongerThanItsDeclaredLengthSendsOnlyThatLength() throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(2));
        ByteArrayInputStream stream = new ByteArrayInputStream((CONTENT + EXTRA).getBytes(US_ASCII));
        try (AsyncHttpClient client = client(true)) {
            assertEquals(200, client.preparePost(url("/upload"))
                    .setBody(new InputStreamBodyGenerator(stream, CONTENT.length()))
                    .execute()
                    .get(10, SECONDS)
                    .getStatusCode());
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertEquals(CONTENT, received.get(0).body);
        assertEquals("GET /next HTTP/1.1", received.get(1).requestLine);
        assertEquals(EXTRA.length(), stream.available());
    }

    @ParameterizedTest(name = "zeroCopy={0}")
    @ValueSource(booleans = {true, false})
    public void streamPartShorterThanItsDeclaredLengthFails(boolean zeroCopy) throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(1));
        try (AsyncHttpClient client = client(zeroCopy)) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(url("/upload"))
                    .addBodyPart(new InputStreamPart("file", new ByteArrayInputStream(new byte[SHORT]), "file.bin", DECLARED))
                    .execute()
                    .get(10, SECONDS));
            assertCause("Multipart InputStream body part 'file' ended after 60000 of its 100000 declared bytes", thrown);
            // Before the client closes, which would close the connection anyway.
            assertCutOff(exchanges.get(10, SECONDS));
        }
    }

    // On a pooled connection, WriteListener leaves a failed write to the retry when its error has "Connection reset" in
    // its message, taking it for a connection that died. The part's name is in the error, but a stream part that ended
    // early fails with an EOFException, which is never taken for one.
    @ParameterizedTest(name = "zeroCopy={0}")
    @ValueSource(booleans = {true, false})
    public void streamPartThatEndsEarlyOnAPooledConnectionIsNotRetried(boolean zeroCopy) throws Exception {
        executor.submit(() -> serve(2));
        AtomicInteger retries = new AtomicInteger();
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setMaxConnections(1)
                .setMaxRequestRetry(1)
                .setDisableZeroCopy(!zeroCopy)
                .setRequestTimeout(Duration.ofSeconds(10)))) {
            assertEquals(200, client.prepareGet(url("/first")).execute().get(10, SECONDS).getStatusCode());
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(url("/upload"))
                    .addBodyPart(new InputStreamPart("Connection reset", new ByteArrayInputStream(new byte[SHORT]),
                            "file.bin", DECLARED))
                    .execute(new AsyncCompletionHandlerBase() {
                        @Override
                        public void onRetry() {
                            retries.incrementAndGet();
                        }
                    })
                    .get(10, SECONDS));
            assertCause("Multipart InputStream body part 'Connection reset' ended after 60000 of its 100000 declared bytes",
                    thrown);
            assertEquals(0, retries.get(), "retried as a pooled connection that died");
        }
    }

    @Test
    public void streamBodyShorterThanItsDeclaredLengthFails() throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(1));
        try (AsyncHttpClient client = client(true)) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(url("/upload"))
                    .setBody(new InputStreamBodyGenerator(new ByteArrayInputStream(new byte[SHORT]), DECLARED))
                    .execute()
                    .get(10, SECONDS));
            assertCause("InputStream body ended after 60000 of its 100000 declared bytes", thrown);
            assertCutOff(exchanges.get(10, SECONDS));
        }
    }

    @Test
    public void streamBodyLongerThanItsContentLengthHeaderSendsOnlyThatLength() throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(2));
        ByteArrayInputStream stream = new ByteArrayInputStream((CONTENT + EXTRA).getBytes(US_ASCII));
        try (AsyncHttpClient client = client(true)) {
            assertEquals(200, client.preparePost(url("/upload"))
                    .setHeader(CONTENT_LENGTH, CONTENT.length())
                    .setBody(stream)
                    .execute()
                    .get(10, SECONDS)
                    .getStatusCode());
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertEquals(CONTENT, received.get(0).body);
        assertEquals("GET /next HTTP/1.1", received.get(1).requestLine);
        assertEquals(EXTRA.length(), stream.available());
    }

    @Test
    public void streamBodyShorterThanItsContentLengthHeaderFails() throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(1));
        try (AsyncHttpClient client = client(true)) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(url("/upload"))
                    .setHeader(CONTENT_LENGTH, DECLARED)
                    .setBody(new ByteArrayInputStream(new byte[SHORT]))
                    .execute()
                    .get(10, SECONDS));
            assertCause("InputStream body ended after 60000 of its 100000 declared bytes", thrown);
            assertCutOff(exchanges.get(10, SECONDS));
        }
    }

    @ParameterizedTest(name = "header={0}")
    @ValueSource(ints = {5, 20})
    public void streamBodyGeneratorFailsWhenItsLengthDisagreesWithItsContentLengthHeader(int header) throws Exception {
        assertRejectedBeforeConnecting("Content-Length header " + header + " does not match the InputStreamBodyGenerator's length 16",
                client -> client.preparePost(url("/upload"))
                        .setHeader(CONTENT_LENGTH, header)
                        .setBody(new InputStreamBodyGenerator(new ByteArrayInputStream((CONTENT + EXTRA).getBytes(US_ASCII)),
                                CONTENT.length()))
                        .execute());
    }

    // RFC 9110 section 8.6: a length is ASCII digits, without a sign.
    @ParameterizedTest(name = "header={0}")
    @ValueSource(strings = {"16, 16", "+16", "-0", "\u0661\u0666"})
    public void streamBodyFailsWhenItsContentLengthHeaderIsNotALength(String header) throws Exception {
        assertRejectedBeforeConnecting("Content-Length header [" + header + "] is not a length",
                client -> client.preparePost(url("/upload"))
                        .setHeader(CONTENT_LENGTH, header)
                        .setBody(new ByteArrayInputStream(CONTENT.getBytes(US_ASCII)))
                        .execute());
    }

    @Test
    public void streamBodyFailsWhenItsContentLengthHeaderIsLongerThanSupported() throws Exception {
        assertRejectedBeforeConnecting("Content-Length header [99999999999999999999] is longer than AHC supports",
                client -> client.preparePost(url("/upload"))
                        .setHeader(CONTENT_LENGTH, "99999999999999999999")
                        .setBody(new ByteArrayInputStream(CONTENT.getBytes(US_ASCII)))
                        .execute());
    }

    // Netty's validation of the request's headers rejects whitespace in front of a value, even where the caller turned
    // header validation off. The check fails such a request before it takes a connection, not Netty after.
    @Test
    public void streamBodyFailsWhenItsContentLengthHeaderHasWhitespaceInFront() throws Exception {
        Request request = new RequestBuilder("POST", false, false)
                .setUrl(url("/upload"))
                .setHeader(CONTENT_LENGTH, " 16")
                .setBody(new ByteArrayInputStream(CONTENT.getBytes(US_ASCII)))
                .build();
        assertRejectedBeforeConnecting("Content-Length header [ 16] is not a length",
                client -> client.executeRequest(request));
    }

    // Whitespace after a value is not part of it (RFC 9112 section 5.1), and Netty's validation accepts it.
    @ParameterizedTest(name = "header={0}, generator={1}")
    @CsvSource(value = {"'16 ',false", "'16\t',false", "'16 ',true", "'16\t',true"})
    public void streamBodyContentLengthHeaderMayEndInWhitespace(String header, boolean generator) throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(2));
        ByteArrayInputStream stream = new ByteArrayInputStream((CONTENT + EXTRA).getBytes(US_ASCII));
        try (AsyncHttpClient client = client(true)) {
            BoundRequestBuilder upload = client.preparePost(url("/upload")).setHeader(CONTENT_LENGTH, header);
            if (generator) {
                upload.setBody(new InputStreamBodyGenerator(stream, CONTENT.length()));
            } else {
                upload.setBody(stream);
            }
            assertEquals(200, upload.execute().get(10, SECONDS).getStatusCode());
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertEquals(CONTENT, received.get(0).body);
        assertEquals("GET /next HTTP/1.1", received.get(1).requestLine);
        assertEquals(EXTRA.length(), stream.available());
    }

    // The request fails before it takes the pooled connection, so with a limit of one connection, the next request
    // still gets it.
    private void assertRejectedBeforeConnecting(String message, Function<AsyncHttpClient, Future<Response>> rejected)
            throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(2));
        try (AsyncHttpClient client = client(true)) {
            assertEquals(200, client.prepareGet(url("/first")).execute().get(10, SECONDS).getStatusCode());
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> rejected.apply(client).get(10, SECONDS));
            assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
            assertEquals(message, thrown.getCause().getMessage());
            assertEquals(200, client.prepareGet(url("/next")).execute().get(10, SECONDS).getStatusCode());
        }

        List<Exchange> received = exchanges.get(10, SECONDS);
        assertEquals("GET /first HTTP/1.1", received.get(0).requestLine);
        assertEquals("GET /next HTTP/1.1", received.get(1).requestLine);
    }

    /**
     * A stream that has its declared length must not be read for its end: a socket-backed stream has nothing more to
     * give, so that read would block the event loop, and the request with it, until the stream ends.
     */
    @Test
    public void streamBodyIsNotReadPastItsDeclaredLength() throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(1));
        NoEofStream stream = new NoEofStream(CONTENT.getBytes(US_ASCII));
        try (AsyncHttpClient client = client(true)) {
            assertEquals(200, client.preparePost(url("/upload"))
                    .setBody(new InputStreamBodyGenerator(stream, CONTENT.length()))
                    .execute()
                    .get(10, SECONDS)
                    .getStatusCode());
        }

        assertEquals(CONTENT, exchanges.get(10, SECONDS).get(0).body);
        assertFalse(stream.readPastDeclaredLength, "the body read past its declared length");
    }

    private AsyncHttpClient client(boolean zeroCopy) {
        return asyncHttpClient(config()
                .setMaxConnections(1)
                .setMaxRequestRetry(0)
                .setDisableZeroCopy(!zeroCopy)
                .setRequestTimeout(Duration.ofSeconds(10)));
    }

    private String url(String path) {
        return "http://localhost:" + server.getLocalPort() + path;
    }

    // The server received part of the body, and then the connection closed: it does not wait for the rest.
    private static void assertCutOff(List<Exchange> received) {
        assertEquals(1, received.size());
        assertFalse(received.get(0).complete, "the server received a whole request");
        assertTrue(received.get(0).body.length() > 0, "no part of the body went out before the failure");
    }

    private static void assertCause(String message, Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof EOFException && message.equals(t.getMessage())) {
                return;
            }
        }
        fail("no EOFException with message '" + message + "' in the causes of " + thrown, thrown);
    }

    // Reads up to the given number of requests with a Content-Length on one connection and answers each with a 200.
    // Stops when the client closes the connection, recording a request it cut off.
    private List<Exchange> serve(int requests) throws IOException {
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
                byte[] body = in.readNBytes(contentLength);
                boolean complete = body.length == contentLength;
                exchanges.add(new Exchange(head.substring(0, head.indexOf("\r\n")), new String(body, US_ASCII), complete));
                if (!complete) {
                    break;
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII));
                out.flush();
            }
        }
        return exchanges;
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

    private static final class Exchange {

        final String requestLine;
        final String body;
        final boolean complete;

        Exchange(String requestLine, String body, boolean complete) {
            this.requestLine = requestLine;
            this.body = body;
            this.complete = complete;
        }
    }

    /**
     * Returns EOF past its content so a regression fails an assertion instead of blocking, but records that it was
     * asked.
     */
    private static final class NoEofStream extends ByteArrayInputStream {

        boolean readPastDeclaredLength;

        NoEofStream(byte[] content) {
            super(content);
        }

        @Override
        public synchronized int read() {
            if (available() == 0) {
                readPastDeclaredLength = true;
            }
            return super.read();
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            if (available() == 0) {
                readPastDeclaredLength = true;
            }
            return super.read(b, off, len);
        }
    }
}
