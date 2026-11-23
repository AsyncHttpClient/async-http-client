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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An {@link InputStreamPart} or {@link InputStreamBodyGenerator} with a declared length sends that many bytes of its
 * stream: not more, as the server reads the bytes past the {@code Content-Length} as the start of the next request
 * on the connection, and not fewer, as the server then waits for the rest.
 */
public class StreamBodyDeclaredLengthTest {

    private static final String CONTENT = "declared content";
    private static final String EXTRA = "GET /smuggled HTTP/1.1\r\nHost: localhost\r\n\r\n";
    private static final String SHORT = CONTENT.substring(0, 7);

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
                    .addBodyPart(new InputStreamPart("file", new ByteArrayInputStream(SHORT.getBytes(US_ASCII)),
                            "file.bin", CONTENT.length()))
                    .execute()
                    .get(10, SECONDS));
            assertCause("Multipart InputStream body part 'file' ended after 7 of its 16 declared bytes", thrown);
        }

        assertTrue(exchanges.get(10, SECONDS).isEmpty(), "the server received a whole request");
    }

    @Test
    public void streamBodyShorterThanItsDeclaredLengthFails() throws Exception {
        Future<List<Exchange>> exchanges = executor.submit(() -> serve(1));
        try (AsyncHttpClient client = client(true)) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(url("/upload"))
                    .setBody(new InputStreamBodyGenerator(new ByteArrayInputStream(SHORT.getBytes(US_ASCII)), CONTENT.length()))
                    .execute()
                    .get(10, SECONDS));
            assertCause("InputStream body ended after 7 of its 16 declared bytes", thrown);
        }

        assertTrue(exchanges.get(10, SECONDS).isEmpty(), "the server received a whole request");
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

    private static void assertCause(String message, Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof IOException && message.equals(t.getMessage())) {
                return;
            }
        }
        fail("no IOException with message '" + message + "' in the causes of " + thrown, thrown);
    }

    // Reads up to the given number of requests with a Content-Length on one connection and answers each with a 200.
    // Stops early when the client closes the connection.
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
                if (body.length < contentLength) {
                    break;
                }
                exchanges.add(new Exchange(head.substring(0, head.indexOf("\r\n")), new String(body, US_ASCII)));
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

        Exchange(String requestLine, String body) {
            this.requestLine = requestLine;
            this.body = body;
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
