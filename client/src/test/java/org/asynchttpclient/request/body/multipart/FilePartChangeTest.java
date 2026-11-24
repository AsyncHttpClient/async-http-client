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
package org.asynchttpclient.request.body.multipart;

import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.netty.request.NettyRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link FilePart} whose file changes after the request was built, over HTTP/1.1. The Content-Length was computed
 * from the file's length at that time, so the part must send exactly that many bytes of the file, or fail.
 */
public class FilePartChangeTest {

    private static final int FILE_SIZE = 20_000;
    private static final String BOUNDARY = "filePartChangeBoundary";

    @ParameterizedTest(name = "zeroCopy={0}")
    @ValueSource(booleans = {true, false})
    public void filePartThatGrowsSendsItsDeclaredBodyOnly(boolean zeroCopy) throws Exception {
        File file = file();
        List<String> requestLines = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<byte[]> body =
                    CompletableFuture.supplyAsync(() -> serveTwoRequests(server, requestLines));
            try (AsyncHttpClient client = client(zeroCopy)) {
                String url = "http://localhost:" + server.getLocalPort();
                Response response = client.preparePost(url + "/upload")
                        .setHeader("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .addBodyPart(new FilePart("file", file))
                        .execute(changeOnSend(file, f -> Files.write(f.toPath(), new byte[FILE_SIZE],
                                StandardOpenOption.APPEND)))
                        .get(10, SECONDS);
                assertEquals(200, response.getStatusCode());

                // The same connection: what was sent after the declared body would be read as this request's start.
                assertEquals(200, client.prepareGet(url + "/next").execute().get(10, SECONDS).getStatusCode());
            }
            byte[] received = body.get(10, SECONDS);
            String text = new String(received, US_ASCII);
            assertTrue(text.endsWith("\r\n--" + BOUNDARY + "--\r\n"),
                    "the declared body does not end the multipart body");
            assertEquals(List.of("POST /upload HTTP/1.1", "GET /next HTTP/1.1"), requestLines);
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @ParameterizedTest(name = "zeroCopy={0}")
    @ValueSource(booleans = {true, false})
    public void filePartThatShrinksFailsTheRequest(boolean zeroCopy) throws Exception {
        File file = file();
        List<String> requestLines = new CopyOnWriteArrayList<>();
        ServerSocket server = new ServerSocket(0);
        Thread acceptor = new Thread(() -> serveUntilClosed(server, requestLines));
        acceptor.start();
        try (AsyncHttpClient client = client(zeroCopy)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client
                    .preparePost("http://localhost:" + server.getLocalPort() + "/upload")
                    .addBodyPart(new FilePart("file", file))
                    .execute(changeOnSend(file, f -> {
                        try (FileChannel channel = FileChannel.open(f.toPath(), StandardOpenOption.WRITE)) {
                            channel.truncate(5_000);
                        }
                    }))
                    // Well before the request timeout: the failure must not wait for it.
                    .get(10, SECONDS));
            // Netty fails a write whose FileRegion throws with "Channel output shutdown", caused by that exception.
            assertTrue(hasCause(e, "Multipart file part 'file' ended after 5000 of its " + FILE_SIZE + " bytes"),
                    "unexpected failure: " + e.getCause());
        } finally {
            server.close();
            acceptor.join(10_000);
            Files.deleteIfExists(file.toPath());
        }
        // Not sent again, with the shorter file and its new length. Whether the first attempt's head got out before
        // the failure depends on the path.
        assertTrue(requestLines.size() <= 1, "sent again: " + requestLines);
    }

    private static boolean hasCause(Throwable t, String message) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof IOException && message.equals(c.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private interface FileChange {
        void apply(File file) throws IOException;
    }

    private static File file() throws IOException {
        File file = File.createTempFile("ahc-file-part-change-", ".bin");
        byte[] content = new byte[FILE_SIZE];
        for (int i = 0; i < FILE_SIZE; i++) {
            content[i] = (byte) ('a' + i % 26);
        }
        Files.write(file.toPath(), content);
        return file;
    }

    private static AsyncHttpClient client(boolean zeroCopy) {
        return asyncHttpClient(config()
                .setDisableZeroCopy(!zeroCopy)
                .setMaxConnections(1)
                .setRequestTimeout(Duration.ofSeconds(60)));
    }

    // Changes the file once the request is built, so its length is taken, and before its body is written.
    private static AsyncCompletionHandlerBase changeOnSend(File file, FileChange change) {
        return new AsyncCompletionHandlerBase() {
            @Override
            public void onRequestSend(NettyRequest request) {
                try {
                    change.apply(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /**
     * Reads up to two requests on one connection, answers each once its declared body is in, records their request
     * lines and returns the first request's body. Ends when the client closes the connection.
     */
    private static byte[] serveTwoRequests(ServerSocket server, List<String> requestLines) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] firstBody = null;
            for (int i = 0; i < 2; i++) {
                String head = readHead(in);
                if (head == null) {
                    break;
                }
                requestLines.add(head.substring(0, head.indexOf("\r\n")));
                byte[] body = readBody(in, contentLength(head));
                if (body == null) {
                    break;
                }
                if (firstBody == null) {
                    firstBody = body;
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII));
                out.flush();
            }
            return firstBody;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Accepts connections until the server socket is closed, reads requests on each and answers those whose
     * declared body is in, recording every request line.
     */
    private static void serveUntilClosed(ServerSocket server, List<String> requestLines) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(10_000);
                InputStream in = socket.getInputStream();
                String head;
                while ((head = readHead(in)) != null) {
                    requestLines.add(head.substring(0, head.indexOf("\r\n")));
                    if (readBody(in, contentLength(head)) == null) {
                        break;
                    }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII));
                }
            } catch (IOException e) {
                // the server socket was closed, or the client went away
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            if (head.toString(US_ASCII).endsWith("\r\n\r\n")) {
                return head.toString(US_ASCII);
            }
        }
        return null;
    }

    private static long contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                return Long.parseLong(line.substring("content-length:".length()).trim());
            }
        }
        return 0;
    }

    // The declared body, or null if the connection ended before it was complete.
    private static byte[] readBody(InputStream in, long length) throws IOException {
        byte[] body = new byte[(int) length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, body.length - read);
            if (n < 0) {
                return null;
            }
            read += n;
        }
        return body;
    }
}
