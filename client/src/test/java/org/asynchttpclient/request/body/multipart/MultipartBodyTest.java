/*
 *    Copyright (c) 2016-2023 AsyncHttpClient Project. All rights reserved.
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

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.request.body.Body.BodyState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultipartBodyTest {

    private static final List<Part> PARTS = new ArrayList<>();
    private static final long MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE;

    static {
        try {
            PARTS.add(new FilePart("filePart", getTestfile()));
        } catch (URISyntaxException e) {
            throw new ExceptionInInitializerError(e);
        }
        PARTS.add(new ByteArrayPart("baPart", "testMultiPart".getBytes(UTF_8), "application/test", UTF_8, "fileName"));
        PARTS.add(new StringPart("stringPart", "testString"));
    }

    static {
        try (MultipartBody dummyBody = buildMultipart()) {
            // separator is random
            MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE = dummyBody.getContentLength() + 100;
        }
    }

    private static File getTestfile() throws URISyntaxException {
        final ClassLoader cl = MultipartBodyTest.class.getClassLoader();
        final URL url = cl.getResource("textfile.txt");
        assertNotNull(url);
        return new File(url.toURI());
    }

    private static MultipartBody buildMultipart() {
        return buildMultipart(EmptyHttpHeaders.INSTANCE);
    }

    private static MultipartBody buildMultipart(HttpHeaders requestHeaders) {
        List<Part> parts = new ArrayList<>(PARTS);
        try {
            File testFile = getTestfile();
            InputStream inputStream = new BufferedInputStream(new FileInputStream(testFile));
            parts.add(new InputStreamPart("isPart", inputStream, testFile.getName(), testFile.length()));
        } catch (URISyntaxException | FileNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
        return MultipartUtils.newMultipartBody(parts, requestHeaders);
    }

    /**
     * Pins the boundary so two serializations of the same parts can be compared byte for byte.
     */
    private static HttpHeaders pinnedBoundary() {
        return new DefaultHttpHeaders().add(CONTENT_TYPE, "multipart/form-data; boundary=pinnedTestBoundary");
    }

    private static long transferWithCopy(MultipartBody multipartBody, int bufferSize) throws IOException {
        long transferred = 0;
        final ByteBuf buffer = Unpooled.buffer(bufferSize);
        try {
            // Count bytes written on EVERY call, including the terminal STOP: transferTo now reports STOP on
            // the same call that writes the body's last bytes (mirroring the real consumers, which send the
            // buffer's contents before honouring STOP).
            BodyState state;
            do {
                state = multipartBody.transferTo(buffer);
                transferred += buffer.readableBytes();
                buffer.clear();
            } while (state != BodyState.STOP);
            return transferred;
        } finally {
            buffer.release();
        }
    }

    private static long transferZeroCopy(MultipartBody multipartBody, int bufferSize) throws IOException {

        final ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        final AtomicLong transferred = new AtomicLong();

        WritableByteChannel mockChannel = new WritableByteChannel() {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }

            @Override
            public int write(ByteBuffer src) {
                int written = src.remaining();
                transferred.set(transferred.get() + written);
                src.position(src.limit());
                return written;
            }
        };

        while (transferred.get() < multipartBody.getContentLength()) {
            multipartBody.transferTo(mockChannel);
            buffer.clear();
        }
        return transferred.get();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void transferWithCopy() throws Exception {
        for (int bufferLength = 1; bufferLength < MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE + 1; bufferLength++) {
            try (MultipartBody multipartBody = buildMultipart()) {
                long transferred = transferWithCopy(multipartBody, bufferLength);
                assertEquals(multipartBody.getContentLength(), transferred);
            }
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void transferZeroCopy() throws Exception {
        for (int bufferLength = 1; bufferLength < MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE + 1; bufferLength++) {
            try (MultipartBody multipartBody = buildMultipart()) {
                long transferred = transferZeroCopy(multipartBody, bufferLength);
                assertEquals(multipartBody.getContentLength(), transferred);
            }
        }
    }

    /**
     * Mimics io_uring's ByteBufWritableByteChannel: it stages into a fixed-size buffer and returns 0 once
     * that buffer is full, rather than blocking until the socket drains. The unbounded mock used by
     * {@link #transferZeroCopy} never exercises that path, which is how issue #2216 shipped.
     */
    private static final class BoundedChannel implements WritableByteChannel {

        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final int chunkCapacity;
        private int remainingInChunk;

        BoundedChannel(int chunkCapacity) {
            this.chunkCapacity = chunkCapacity;
            remainingInChunk = chunkCapacity;
        }

        @Override
        public int write(ByteBuffer src) {
            if (remainingInChunk == 0) {
                // Stays refused for the rest of this transferTo, exactly like io_uring: the staging buffer
                // is only flushed once we hand control back, so spinning here never makes progress.
                return 0;
            }
            int count = Math.min(src.remaining(), remainingInChunk);
            byte[] chunk = new byte[count];
            src.get(chunk);
            written.write(chunk, 0, count);
            remainingInChunk -= count;
            return count;
        }

        /**
         * Models Netty flushing the staging buffer between transferTo calls.
         */
        void refill() {
            remainingInChunk = chunkCapacity;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }

        byte[] toByteArray() {
            return written.toByteArray();
        }
    }

    private static byte[] drain(MultipartBody body, BoundedChannel target, int maxIterations) throws IOException {
        int iterations = 0;
        while (body.transferTo(target) != -1L) {
            target.refill();
            assertTrue(++iterations < maxIterations,
                    "transferTo did not finish within " + maxIterations + " calls; it is not making progress");
        }
        return target.toByteArray();
    }

    /**
     * A target that refuses writes must not cost bytes and must not spin. Sweeps the chunk size so the
     * refusal lands at a different offset each time, including mid-part and mid-boundary.
     */
    @RepeatedIfExceptionsTest(repeats = 5)
    public void transferZeroCopyToTargetThatRefusesWrites() {
        // A part that spins on a refusing target never returns, so bound the whole sweep in wall time:
        // that is the shape issue #2216 took, and an assertion cannot observe it from the inside.
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            byte[] expected;
            try (MultipartBody reference = buildMultipart(pinnedBoundary())) {
                BoundedChannel unbounded = new BoundedChannel(Integer.MAX_VALUE);
                expected = drain(reference, unbounded, 10_000);
                assertEquals(reference.getContentLength(), expected.length);
            }

            for (int chunkCapacity : new int[]{1, 2, 7, 64, 511, 4096, 65536}) {
                try (MultipartBody multipartBody = buildMultipart(pinnedBoundary())) {
                    BoundedChannel target = new BoundedChannel(chunkCapacity);
                    // Worst case is one byte per call plus a refusal between every chunk, hence the generous bound.
                    byte[] actual = drain(multipartBody, target, (int) (multipartBody.getContentLength() * 2 + 1000));
                    assertEquals(multipartBody.getContentLength(), actual.length,
                            "chunkCapacity=" + chunkCapacity + ": wrong number of bytes reached the target");
                    assertArrayEquals(expected, actual,
                            "chunkCapacity=" + chunkCapacity + ": body differs from the reference serialization");
                }
            }
        });
    }

    /**
     * A stream that hands over exactly its declared length must finish without the part reading again for
     * EOF. Socket-backed streams have nothing more to give and would block that extra read forever.
     */
    @RepeatedIfExceptionsTest(repeats = 5)
    public void inputStreamPartFinishesOnDeclaredLengthWithoutWaitingForEof() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            byte[] content = "declared length, no EOF to follow".getBytes(UTF_8);
            NoEofStream stream = new NoEofStream(content);

            List<Part> parts = new ArrayList<>();
            parts.add(new InputStreamPart("isPart", stream, "fileName", content.length));

            try (MultipartBody multipartBody = MultipartUtils.newMultipartBody(parts, pinnedBoundary())) {
                BoundedChannel target = new BoundedChannel(8);
                byte[] actual = drain(multipartBody, target, (int) (multipartBody.getContentLength() * 2 + 1000));
                assertEquals(multipartBody.getContentLength(), actual.length);
                assertFalse(stream.readPastDeclaredLength,
                        "the part read past the declared length; a socket-backed stream would block there");
            }
        });
    }

    /**
     * Returns EOF past its content so a regression fails an assertion instead of hanging the build, but
     * records that it was asked.
     */
    private static final class NoEofStream extends ByteArrayInputStream {

        private final int declaredLength;
        private int delivered;
        boolean readPastDeclaredLength;

        NoEofStream(byte[] content) {
            super(content);
            declaredLength = content.length;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            if (delivered >= declaredLength) {
                readPastDeclaredLength = true;
                return -1;
            }
            int read = super.read(b, off, len);
            if (read > 0) {
                delivered += read;
            }
            return read;
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void finishingChunkReportsStopAndCarriesAllBytes() throws Exception {
        try (MultipartBody multipartBody = buildMultipart()) {
            // A buffer large enough for the whole body: the single transferTo that writes the last bytes must
            // report STOP (previously it returned CONTINUE and forced an extra empty readChunk to reach STOP),
            // and that STOP call must still carry every byte of the body.
            int capacity = (int) MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE + 100;
            ByteBuf buffer = Unpooled.buffer(capacity);
            try {
                BodyState state = multipartBody.transferTo(buffer);
                assertEquals(BodyState.STOP, state, "the call that finishes the body must report STOP");
                assertEquals(multipartBody.getContentLength(), buffer.readableBytes(),
                        "the finishing STOP call must still carry all of the body's bytes");
            } finally {
                buffer.release();
            }
        }
    }
}
