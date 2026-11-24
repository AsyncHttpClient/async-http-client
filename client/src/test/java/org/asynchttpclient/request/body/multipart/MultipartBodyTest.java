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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.request.body.Body.BodyState;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    public void transferWithCopy() throws Exception {
        for (int bufferLength = 1; bufferLength < MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE + 1; bufferLength++) {
            try (MultipartBody multipartBody = buildMultipart()) {
                long transferred = transferWithCopy(multipartBody, bufferLength);
                assertEquals(multipartBody.getContentLength(), transferred);
            }
        }
    }

    @Test
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
    @Test
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
    @Test
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

    /**
     * A stream with more bytes than its part declares must stop at the declared length: the bytes past it would go
     * out after the end of the body, where the server reads them as the start of the next request.
     */
    @Test
    public void inputStreamPartStopsAtDeclaredLength() throws Exception {
        byte[] content = "declared content".getBytes(UTF_8);
        byte[] extra = "GET /next HTTP/1.1\r\n\r\n".getBytes(UTF_8);
        byte[] longer = new byte[content.length + extra.length];
        System.arraycopy(content, 0, longer, 0, content.length);
        System.arraycopy(extra, 0, longer, content.length, extra.length);

        byte[] expected;
        try (MultipartBody reference = streamPartBody(new ByteArrayInputStream(content), content.length)) {
            expected = copy(reference, 8192);
            assertEquals(reference.getContentLength(), expected.length);
        }

        for (boolean trickle : new boolean[]{false, true}) {
            for (int bufferSize : new int[]{1, 7, 64, 8192}) {
                String label = "trickle=" + trickle + ", bufferSize=" + bufferSize;
                ByteArrayInputStream source = new ByteArrayInputStream(longer);
                try (MultipartBody multipartBody = streamPartBody(trickle ? new TrickleStream(source) : source, content.length)) {
                    assertArrayEquals(expected, copy(multipartBody, bufferSize), label);
                }
                assertEquals(extra.length, source.available(), label + ": read past the declared length");
            }
            for (int chunkCapacity : new int[]{1, 7, 64, 65536}) {
                String label = "trickle=" + trickle + ", chunkCapacity=" + chunkCapacity;
                ByteArrayInputStream source = new ByteArrayInputStream(longer);
                try (MultipartBody multipartBody = streamPartBody(trickle ? new TrickleStream(source) : source, content.length)) {
                    assertArrayEquals(expected, drain(multipartBody, new BoundedChannel(chunkCapacity), 10_000), label);
                }
                assertEquals(extra.length, source.available(), label + ": read past the declared length");
            }
        }
    }

    @Test
    public void inputStreamPartWithNoDeclaredBytes() throws Exception {
        for (int bufferSize : new int[]{1, 7, 64, 8192}) {
            try (MultipartBody multipartBody = streamPartBody(new ByteArrayInputStream(new byte[0]), 0)) {
                assertEquals(multipartBody.getContentLength(), copy(multipartBody, bufferSize).length, "bufferSize=" + bufferSize);
            }
        }
        for (int chunkCapacity : new int[]{1, 7, 64, 65536}) {
            try (MultipartBody multipartBody = streamPartBody(new ByteArrayInputStream(new byte[0]), 0)) {
                assertEquals(multipartBody.getContentLength(),
                        drain(multipartBody, new BoundedChannel(chunkCapacity), 10_000).length, "chunkCapacity=" + chunkCapacity);
            }
        }
    }

    /**
     * In a body of unknown length, which goes out chunked because another part has no length, a part's declared length
     * frames nothing. It is still how much of its stream the part sends: whether the body has a length must not change
     * that. A caller who wants the whole stream leaves the part's length unknown.
     */
    @Test
    public void inputStreamPartKeepsItsDeclaredLengthInABodyOfUnknownLength() throws Exception {
        byte[] content = "declared content".getBytes(UTF_8);
        byte[] longer = "declared content|and more".getBytes(UTF_8);
        int extra = longer.length - content.length;

        byte[] expected;
        try (MultipartBody reference = withPartOfUnknownLength(new ByteArrayInputStream(content), content.length)) {
            assertEquals(-1, reference.getContentLength());
            expected = copy(reference, 8192);
        }

        for (int bufferSize : new int[]{1, 7, 64, 8192}) {
            ByteArrayInputStream source = new ByteArrayInputStream(longer);
            try (MultipartBody multipartBody = withPartOfUnknownLength(source, content.length)) {
                assertArrayEquals(expected, copy(multipartBody, bufferSize), "bufferSize=" + bufferSize);
            }
            assertEquals(extra, source.available(), "bufferSize=" + bufferSize + ": read past the declared length");

            IOException thrown = assertThrows(IOException.class, () -> {
                try (MultipartBody multipartBody = withPartOfUnknownLength(new ByteArrayInputStream(content, 0, 7), content.length)) {
                    copy(multipartBody, bufferSize);
                }
            });
            assertEquals("Multipart InputStream body part 'isPart' ended after 7 of its 16 declared bytes", thrown.getMessage(),
                    "bufferSize=" + bufferSize);
        }
        for (int chunkCapacity : new int[]{1, 7, 64, 65536}) {
            ByteArrayInputStream source = new ByteArrayInputStream(longer);
            try (MultipartBody multipartBody = withPartOfUnknownLength(source, content.length)) {
                assertArrayEquals(expected, drain(multipartBody, new BoundedChannel(chunkCapacity), 10_000),
                        "chunkCapacity=" + chunkCapacity);
            }
            assertEquals(extra, source.available(), "chunkCapacity=" + chunkCapacity + ": read past the declared length");

            IOException thrown = assertThrows(IOException.class, () -> {
                try (MultipartBody multipartBody = withPartOfUnknownLength(new ByteArrayInputStream(content, 0, 7), content.length)) {
                    drain(multipartBody, new BoundedChannel(chunkCapacity), 10_000);
                }
            });
            assertEquals("Multipart InputStream body part 'isPart' ended after 7 of its 16 declared bytes", thrown.getMessage(),
                    "chunkCapacity=" + chunkCapacity);
        }
    }

    private static MultipartBody withPartOfUnknownLength(InputStream stream, long contentLength) {
        List<Part> parts = new ArrayList<>();
        parts.add(new InputStreamPart("isPart", stream, "fileName", contentLength));
        parts.add(new InputStreamPart("unknown", new ByteArrayInputStream("tail".getBytes(UTF_8)), "other"));
        return MultipartUtils.newMultipartBody(parts, pinnedBoundary());
    }

    /**
     * Hands out at most 7 bytes per read and reports none available, like a stream that receives its data in small
     * pieces, so that a part reads it several times while bytes from an earlier read wait for the target.
     */
    private static final class TrickleStream extends FilterInputStream {

        TrickleStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, Math.min(len, 7));
        }

        @Override
        public int available() {
            return 0;
        }
    }

    /**
     * A stream with fewer bytes than its part declares must fail the body: the server would wait for the rest.
     */
    @Test
    public void inputStreamPartFailsWhenItsStreamEndsBeforeDeclaredLength() {
        byte[] content = "declared content".getBytes(UTF_8);
        String message = "Multipart InputStream body part 'isPart' ended after 7 of its 16 declared bytes";

        for (int bufferSize : new int[]{1, 7, 64, 8192}) {
            IOException thrown = assertThrows(IOException.class, () -> {
                try (MultipartBody multipartBody = streamPartBody(new ByteArrayInputStream(content, 0, 7), content.length)) {
                    copy(multipartBody, bufferSize);
                }
            });
            assertEquals(message, thrown.getMessage(), "bufferSize=" + bufferSize);
        }
        for (int chunkCapacity : new int[]{1, 7, 64, 65536}) {
            IOException thrown = assertThrows(IOException.class, () -> {
                try (MultipartBody multipartBody = streamPartBody(new ByteArrayInputStream(content, 0, 7), content.length)) {
                    drain(multipartBody, new BoundedChannel(chunkCapacity), 10_000);
                }
            });
            assertEquals(message, thrown.getMessage(), "chunkCapacity=" + chunkCapacity);
        }
    }

    private static MultipartBody streamPartBody(InputStream stream, long contentLength) {
        List<Part> parts = new ArrayList<>();
        parts.add(new InputStreamPart("isPart", stream, "fileName", contentLength));
        return MultipartUtils.newMultipartBody(parts, pinnedBoundary());
    }

    private static byte[] copy(MultipartBody multipartBody, int bufferSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuf buffer = Unpooled.buffer(bufferSize);
        try {
            BodyState state;
            do {
                state = multipartBody.transferTo(buffer);
                buffer.readBytes(out, buffer.readableBytes());
                buffer.clear();
            } while (state != BodyState.STOP);
            return out.toByteArray();
        } finally {
            buffer.release();
        }
    }

    @Test
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

    // A FilePart takes the file's length when the request is built, and the Content-Length announces it. The file
    // can still change before or while it is sent. Both paths must send exactly that length, or fail.

    private interface FileChange {
        void apply(File file) throws IOException;
    }

    private static File filePartFile(int size) throws IOException {
        File file = File.createTempFile("ahc-file-part-", ".bin");
        file.deleteOnExit();
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) ('a' + i % 26);
        }
        Files.write(file.toPath(), content);
        return file;
    }

    private static MultipartBody filePartBody(File file) {
        return MultipartUtils.newMultipartBody(Collections.singletonList(new FilePart("file", file)), pinnedBoundary());
    }

    private static void grow(File file) throws IOException {
        Files.write(file.toPath(), new byte[3000], StandardOpenOption.APPEND);
    }

    private static FileChange truncateTo(long size) {
        return file -> {
            try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                channel.truncate(size);
            }
        };
    }

    /**
     * Sends the body through a ByteBuf or, with zeroCopy, a channel that takes at most 64 bytes per call, and
     * applies the change once at least changeAfter bytes went out. Fails instead of spinning.
     */
    private static byte[] send(MultipartBody body, File file, boolean zeroCopy, long changeAfter, FileChange change)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean changed = false;
        for (int calls = 0; ; calls++) {
            assertTrue(calls < 10_000, "the body made no progress");
            if (!changed && out.size() >= changeAfter) {
                change.apply(file);
                changed = true;
            }
            if (zeroCopy) {
                BoundedChannel target = new BoundedChannel(64);
                long transferred = body.transferTo(target);
                out.write(target.toByteArray());
                if (transferred == -1L) {
                    return out.toByteArray();
                }
            } else {
                ByteBuf buffer = Unpooled.buffer(64);
                try {
                    BodyState state = body.transferTo(buffer);
                    buffer.readBytes(out, buffer.readableBytes());
                    if (state == BodyState.STOP) {
                        return out.toByteArray();
                    }
                } finally {
                    buffer.release();
                }
            }
        }
    }

    private static byte[] sendUnchanged(File file, boolean zeroCopy) throws IOException {
        try (MultipartBody body = filePartBody(file)) {
            byte[] sent = send(body, file, zeroCopy, Long.MAX_VALUE, f -> {
            });
            assertEquals(body.getContentLength(), sent.length);
            return sent;
        }
    }

    @Test
    public void filePartSendsOnlyItsLengthWhenTheFileGrows() throws Exception {
        for (boolean zeroCopy : new boolean[]{false, true}) {
            // Before any of the file went out, and in the middle of it.
            for (long changeAfter : new long[]{0, 400}) {
                File file = filePartFile(1000);
                byte[] expected = sendUnchanged(file, zeroCopy);
                try (MultipartBody body = filePartBody(file)) {
                    byte[] sent = send(body, file, zeroCopy, changeAfter, MultipartBodyTest::grow);
                    assertArrayEquals(expected, sent, "zeroCopy=" + zeroCopy + ", changeAfter=" + changeAfter);
                }
            }
        }
    }

    @Test
    public void filePartFailsWhenTheFileShrinks() throws Exception {
        for (boolean zeroCopy : new boolean[]{false, true}) {
            for (long changeAfter : new long[]{0, 400}) {
                File file = filePartFile(1000);
                try (MultipartBody body = filePartBody(file)) {
                    EOFException e = assertThrows(EOFException.class,
                            () -> send(body, file, zeroCopy, changeAfter, truncateTo(500)));
                    assertEquals("Multipart file part 'file' ended after 500 of its 1000 bytes", e.getMessage(),
                            "zeroCopy=" + zeroCopy + ", changeAfter=" + changeAfter);
                }
            }
        }
    }

    // Shorter than what already went out: the next read finds the end at once.
    @Test
    public void filePartFailsWhenTheFileShrinksBelowWhatWasSent() throws Exception {
        for (boolean zeroCopy : new boolean[]{false, true}) {
            File file = filePartFile(1000);
            try (MultipartBody body = filePartBody(file)) {
                EOFException e = assertThrows(EOFException.class,
                        () -> send(body, file, zeroCopy, 600, truncateTo(10)));
                assertTrue(e.getMessage().startsWith("Multipart file part 'file' ended after "), e.getMessage());
                assertTrue(e.getMessage().endsWith(" of its 1000 bytes"), e.getMessage());
            }
        }
    }

    @Test
    public void emptyFilePartSendsNothingWhenTheFileGrows() throws Exception {
        for (boolean zeroCopy : new boolean[]{false, true}) {
            File file = filePartFile(0);
            byte[] expected = sendUnchanged(file, zeroCopy);
            try (MultipartBody body = filePartBody(file)) {
                assertArrayEquals(expected, send(body, file, zeroCopy, 0, MultipartBodyTest::grow),
                        "zeroCopy=" + zeroCopy);
            }
        }
    }

    // A target that takes nothing makes transferTo return 0, as the end of the file does. The unchanged file must
    // still be sent whole.
    @Test
    public void unchangedFilePartSurvivesATargetThatTakesNothing() throws Exception {
        File file = filePartFile(1000);
        byte[] expected = sendUnchanged(file, false);
        try (MultipartBody body = filePartBody(file)) {
            // The part's headers and the start of the file.
            BoundedChannel target = new BoundedChannel(64);
            for (int i = 0; i < 6; i++) {
                body.transferTo(target);
                target.refill();
            }
            int contentStart = new String(expected, UTF_8).indexOf("\r\n\r\n") + 4;
            int sent = target.toByteArray().length;
            assertTrue(sent > contentStart && sent < contentStart + 1000, "not inside the file: " + sent);
            BoundedChannel refusing = new BoundedChannel(0);
            for (int i = 0; i < 5; i++) {
                assertEquals(0, body.transferTo(refusing));
            }
            assertArrayEquals(expected, drain(body, target, 10_000));
        }
    }
}
