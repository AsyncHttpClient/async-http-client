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
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link InputStreamBodyGenerator}'s {@link Body#transferTo(ByteBuf)}, which now reads straight from
 * the stream into the target buffer (dropping the per-call staging {@code byte[]} and copy for heap buffers).
 * The whole stream must still be transferred byte-for-byte, CONTINUE while data remains and STOP at EOF.
 */
public class InputStreamBodyGeneratorTest {

    private static final int CHUNK_SIZE = 1024 * 8;
    private static final String COMPLETE_REQUEST =
            "POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n7\r\npartial\r\n0\r\n\r\n";

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

        AtomicBoolean clientDone = new AtomicBoolean();
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> receiveRequest(server, clientDone));
            try (AsyncHttpClient client = asyncHttpClient(config()
                    .setMaxRequestRetry(0)
                    .setRequestTimeout(Duration.ofSeconds(10)))) {
                ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                        .preparePost("http://localhost:" + server.getLocalPort() + "/")
                        .setBody(wrapper)
                        .execute()
                        .get(10, SECONDS));
                assertSame(failure, thrown.getCause());
            } finally {
                clientDone.set(true);
            }
            // How much of the body went out before the failure depends on the transport; its last chunk must not.
            assertFalse(hasLastChunk(received.get(10, SECONDS)), "the server received the last chunk");
        }
    }

    @Test
    public void laterBodyReadsTheStreamFromItsMark() throws IOException {
        InputStreamBodyGenerator generator = new InputStreamBodyGenerator(
                new ByteArrayInputStream("FIRST---SECOND--".getBytes(US_ASCII)), 8);

        assertArrayEquals("FIRST---".getBytes(US_ASCII), drain(generator.createBody(), CHUNK_SIZE));
        assertArrayEquals("FIRST---".getBytes(US_ASCII), drain(generator.createBody(), CHUNK_SIZE));
    }

    @Test
    public void laterBodyFailsForAStreamWithoutMarkSupport() throws IOException {
        InputStreamBodyGenerator generator = new InputStreamBodyGenerator(
                withoutMarkSupport(new ByteArrayInputStream("FIRST---SECOND--".getBytes(US_ASCII))), 8);

        assertArrayEquals("FIRST---".getBytes(US_ASCII), drain(generator.createBody(), CHUNK_SIZE));
        Body later = generator.createBody();
        IOException thrown = assertThrows(IOException.class, () -> drain(later, CHUNK_SIZE));
        assertEquals("InputStream body already consumed and cannot be reset for a retry", thrown.getMessage());
    }

    @Test
    public void bodyThatDidNotReadLeavesTheStreamToTheNextOne() throws IOException {
        InputStreamBodyGenerator generator = new InputStreamBodyGenerator(
                withoutMarkSupport(new ByteArrayInputStream("FIRST---".getBytes(US_ASCII))), 8);

        generator.createBody();
        assertArrayEquals("FIRST---".getBytes(US_ASCII), drain(generator.createBody(), CHUNK_SIZE));
    }

    @Test
    public void bodyWithNoDeclaredBytesDoesNotReadItsStream() throws IOException {
        ByteArrayInputStream stream = new ByteArrayInputStream("abc".getBytes(US_ASCII));
        Body body = new InputStreamBodyGenerator(stream, 0).createBody();

        assertArrayEquals(new byte[0], drain(body, CHUNK_SIZE));
        assertEquals(3, stream.available());
    }

    // The server side of the request tests must not lose a last chunk to an error after it.
    @Test
    public void receivingKeepsWhatArrivedBeforeAReset() throws IOException {
        InputStream in = failingAfter(COMPLETE_REQUEST.getBytes(US_ASCII), new SocketException("Connection reset"));

        assertTrue(hasLastChunk(receiveUntilQuiet(in, () -> false)));
    }

    @Test
    public void receivingFailsOnAnyOtherError() {
        IOException failure = new IOException("read failed");
        InputStream in = failingAfter(COMPLETE_REQUEST.getBytes(US_ASCII), failure);

        assertSame(failure, assertThrows(IOException.class, () -> receiveUntilQuiet(in, () -> false)));
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
            BodyState state;
            do {
                state = body.transferTo(chunkBuffer);
                chunkBuffer.readBytes(collected, chunkBuffer.readableBytes());
                chunkBuffer.clear();
            } while (state != BodyState.STOP);
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

    private static InputStream withoutMarkSupport(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public boolean markSupported() {
                return false;
            }
        };
    }

    private static String receiveRequest(ServerSocket server, AtomicBoolean clientDone) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(100);
            return receiveUntilQuiet(socket.getInputStream(), clientDone::get);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // What arrives until the connection ends, or until the client is done and nothing more arrives within the read
    // timeout. That is a bounded observation, not proof that nothing would follow: on io_uring, the connection of a
    // request that failed in the middle of its body can stay open, so its end is not something to wait for. A reset
    // ends the connection too, and keeps what arrived before it; any other error fails the observation.
    private static String receiveUntilQuiet(InputStream in, BooleanSupplier clientDone) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int read;
            try {
                read = in.read(buffer);
            } catch (SocketTimeoutException e) {
                if (clientDone.getAsBoolean()) {
                    break;
                }
                continue;
            } catch (SocketException e) {
                if (e.getMessage() == null || !e.getMessage().startsWith("Connection reset")) {
                    throw e;
                }
                break;
            }
            if (read < 0) {
                break;
            }
            received.write(buffer, 0, read);
        }
        return received.toString(US_ASCII);
    }

    // Whether a chunked request, received in full or in part, ends with its last chunk.
    private static boolean hasLastChunk(String request) {
        int head = request.indexOf("\r\n\r\n");
        String body = head < 0 ? "" : request.substring(head + 4);
        return body.equals("0\r\n\r\n") || body.endsWith("\r\n0\r\n\r\n");
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
