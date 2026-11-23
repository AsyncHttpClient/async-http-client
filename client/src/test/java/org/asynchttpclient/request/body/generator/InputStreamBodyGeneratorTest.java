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
package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.Body.BodyState;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link InputStreamBodyGenerator}'s {@link Body#transferTo(ByteBuf)}, which now reads straight from
 * the stream into the target buffer (dropping the per-call staging {@code byte[]} and copy for heap buffers).
 * The whole stream must still be transferred byte-for-byte, CONTINUE while data remains and STOP at EOF.
 */
public class InputStreamBodyGeneratorTest {

    private static final int CHUNK_SIZE = 1024 * 8;

    @Test
    public void streamsAllBytesAcrossMultipleReads() throws IOException {
        final byte[] src = new byte[3 * CHUNK_SIZE + 42];
        new Random().nextBytes(src);

        Body body = new InputStreamBodyGenerator(new ByteArrayInputStream(src)).createBody();
        ByteBuf chunkBuffer = Unpooled.buffer(CHUNK_SIZE);
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try {
            BodyState state;
            while ((state = body.transferTo(chunkBuffer)) != BodyState.STOP) {
                assertEquals(BodyState.CONTINUE, state, "a stream with data left must report CONTINUE");
                byte[] b = new byte[chunkBuffer.readableBytes()];
                chunkBuffer.readBytes(b);
                collected.write(b);
                chunkBuffer.clear();
            }
            assertArrayEquals(src, collected.toByteArray(), "the whole stream must be transferred unchanged");
        } finally {
            chunkBuffer.release();
            body.close();
        }
    }

    @Test
    public void singleReadDrainsASmallStream() throws IOException {
        final byte[] src = new byte[CHUNK_SIZE - 100]; // fits in one writable region, so one read drains it
        new Random().nextBytes(src);

        Body body = new InputStreamBodyGenerator(new ByteArrayInputStream(src)).createBody();
        ByteBuf chunkBuffer = Unpooled.buffer(CHUNK_SIZE);
        try {
            assertEquals(BodyState.CONTINUE, body.transferTo(chunkBuffer));
            assertEquals(src.length, chunkBuffer.readableBytes(), "one read should drain a small stream");
            chunkBuffer.clear();
            assertEquals(BodyState.STOP, body.transferTo(chunkBuffer), "body at EOF");
        } finally {
            chunkBuffer.release();
            body.close();
        }
    }

    @Test
    public void emptyStreamStopsImmediately() throws IOException {
        Body body = new InputStreamBodyGenerator(new ByteArrayInputStream(new byte[0])).createBody();
        ByteBuf chunkBuffer = Unpooled.buffer(CHUNK_SIZE);
        try {
            assertEquals(BodyState.STOP, body.transferTo(chunkBuffer), "an empty stream must STOP immediately");
            assertEquals(0, chunkBuffer.readableBytes(), "nothing should be written for an empty stream");
        } finally {
            chunkBuffer.release();
            body.close();
        }
    }

    // Locks the removal of the old "writableBytes() - 10" margin: with a writable region of 10 or fewer bytes the
    // margin made the transfer length 0 (or negative), so it silently STOPped without writing / threw. The stream
    // must now still be drained through a tiny target buffer.
    @Test
    public void smallWritableRegionStillTransfers() throws IOException {
        final byte[] src = new byte[25];
        new Random().nextBytes(src);

        Body body = new InputStreamBodyGenerator(new ByteArrayInputStream(src)).createBody();
        ByteBuf chunkBuffer = Unpooled.buffer(10, 10); // writableBytes() == 10, the old margin's boundary
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try {
            BodyState state;
            while ((state = body.transferTo(chunkBuffer)) != BodyState.STOP) {
                assertEquals(BodyState.CONTINUE, state, "a stream with data left must report CONTINUE");
                byte[] b = new byte[chunkBuffer.readableBytes()];
                chunkBuffer.readBytes(b);
                collected.write(b);
                chunkBuffer.clear();
            }
            assertArrayEquals(src, collected.toByteArray(), "the whole stream must drain through a tiny buffer");
        } finally {
            chunkBuffer.release();
            body.close();
        }
    }

    @Test
    public void readFailureFailsTheBody() throws IOException {
        IOException failure = new IOException("read failed");
        Body body = new InputStreamBodyGenerator(failingAfter(new byte[3], failure)).createBody();
        ByteBuf chunkBuffer = Unpooled.buffer(CHUNK_SIZE);
        try {
            assertEquals(BodyState.CONTINUE, body.transferTo(chunkBuffer));
            chunkBuffer.clear();
            assertSame(failure, assertThrows(IOException.class, () -> body.transferTo(chunkBuffer)));
        } finally {
            chunkBuffer.release();
        }
    }

    /**
     * A body that its own {@link BodyGenerator} hands out, as one that wraps another generator does, goes out
     * chunked when it has no length. Ending it at a read failure sent the terminating chunk, so the server took the
     * body for complete.
     */
    @Test
    public void readFailureFailsAChunkedRequest() throws Exception {
        IOException failure = new IOException("read failed");
        InputStreamBodyGenerator generator = new InputStreamBodyGenerator(failingAfter("partial".getBytes(US_ASCII), failure));
        BodyGenerator wrapper = generator::createBody;

        try (ServerSocket server = new ServerSocket(0);
             AsyncHttpClient client = asyncHttpClient(config()
                     .setMaxRequestRetry(0)
                     .setRequestTimeout(Duration.ofSeconds(10)))) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> readChunkedRequest(server));
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                    .preparePost("http://localhost:" + server.getLocalPort() + "/")
                    .setBody(wrapper)
                    .execute()
                    .get(10, SECONDS));
            assertSame(failure, thrown.getCause());
            assertEquals("connection ended before the last chunk", received.get(10, SECONDS));
        }
    }

    @Test
    public void bodyStopsAtDeclaredLength() throws IOException {
        byte[] content = "declared content".getBytes(US_ASCII);
        byte[] longer = "declared content|and more".getBytes(US_ASCII);

        for (int bufferSize : new int[]{1, 7, 64, CHUNK_SIZE}) {
            ByteArrayInputStream stream = new ByteArrayInputStream(longer);
            Body body = new InputStreamBodyGenerator(stream, content.length).createBody();
            assertArrayEquals(content, drain(body, bufferSize), "bufferSize=" + bufferSize);
            assertEquals(longer.length - content.length, stream.available(), "bufferSize=" + bufferSize
                    + ": read past the declared length");
        }
    }

    /**
     * A stream that has its declared length must not be read for its end: a socket-backed stream has nothing more to
     * give, so that read would block until the stream ends.
     */
    @Test
    public void bodyIsNotReadPastDeclaredLength() throws IOException {
        byte[] content = "declared content".getBytes(US_ASCII);
        boolean[] readPastDeclaredLength = new boolean[1];
        InputStream stream = new ByteArrayInputStream(content) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                if (available() == 0) {
                    readPastDeclaredLength[0] = true;
                }
                return super.read(b, off, len);
            }
        };

        assertArrayEquals(content, drain(new InputStreamBodyGenerator(stream, content.length).createBody(), CHUNK_SIZE));
        assertFalse(readPastDeclaredLength[0], "the body read past its declared length");
    }

    @Test
    public void bodyFailsWhenStreamEndsBeforeDeclaredLength() {
        Body body = new InputStreamBodyGenerator(new ByteArrayInputStream("partial".getBytes(US_ASCII)), 16).createBody();

        IOException thrown = assertThrows(IOException.class, () -> drain(body, CHUNK_SIZE));
        assertEquals("InputStream body ended after 7 of its 16 declared bytes", thrown.getMessage());
    }

    private static byte[] drain(Body body, int bufferSize) throws IOException {
        ByteBuf chunkBuffer = Unpooled.buffer(bufferSize);
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try {
            while (body.transferTo(chunkBuffer) != BodyState.STOP) {
                chunkBuffer.readBytes(collected, chunkBuffer.readableBytes());
                chunkBuffer.clear();
            }
            return collected.toByteArray();
        } finally {
            chunkBuffer.release();
            body.close();
        }
    }

    // Hands out the given bytes, then fails every read.
    private static InputStream failingAfter(byte[] content, IOException failure) {
        return new FilterInputStream(new ByteArrayInputStream(content)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int read = super.read(b, off, len);
                if (read < 0) {
                    throw failure;
                }
                return read;
            }
        };
    }

    // Reads one chunked request and tells whether its last chunk arrived.
    private static String readChunkedRequest(ServerSocket server) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            String line;
            do {
                line = readLine(in);
            } while (line != null && !line.isEmpty());
            while (line != null) {
                line = readLine(in);
                if (line == null) {
                    break;
                }
                int size = Integer.parseInt(line, 16);
                if (size == 0) {
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII));
                    return "last chunk received";
                }
                if (in.readNBytes(size + 2).length < size + 2) {
                    break;
                }
            }
            return "connection ended before the last chunk";
        } catch (IOException e) {
            return "server failed: " + e;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return line.toString();
            }
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return null;
    }
}
