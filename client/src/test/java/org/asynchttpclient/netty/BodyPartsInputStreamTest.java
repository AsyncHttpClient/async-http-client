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
package org.asynchttpclient.netty;

import io.netty.buffer.Unpooled;
import org.asynchttpclient.HttpResponseBodyPart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BodyPartsInputStreamTest {

    private static List<HttpResponseBodyPart> eagerParts(int... sizes) {
        List<HttpResponseBodyPart> parts = new ArrayList<>();
        int value = 0;
        for (int size : sizes) {
            byte[] chunk = new byte[size];
            for (int i = 0; i < size; i++) {
                chunk[i] = (byte) value++;
            }
            parts.add(new EagerResponseBodyPart(Unpooled.wrappedBuffer(chunk), false));
        }
        return parts;
    }

    private static byte[] concatenation(List<HttpResponseBodyPart> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (HttpResponseBodyPart part : parts) {
            out.writeBytes(part.getBodyPartBytes());
        }
        return out.toByteArray();
    }

    private static List<List<HttpResponseBodyPart>> shapes() {
        return Arrays.asList(
                eagerParts(),
                eagerParts(0),
                eagerParts(1),
                eagerParts(11),
                eagerParts(5, 7),
                eagerParts(3, 0, 4, 0),
                eagerParts(1, 1, 1, 1, 1),
                eagerParts(300, 17, 256, 1, 90));
    }

    @Test
    public void readAllBytesMatchesTheConcatenation() {
        for (List<HttpResponseBodyPart> parts : shapes()) {
            assertArrayEquals(concatenation(parts), new BodyPartsInputStream(parts).readAllBytes());
        }
    }

    @Test
    public void singleByteReadsMatchTheConcatenation() {
        for (List<HttpResponseBodyPart> parts : shapes()) {
            byte[] expected = concatenation(parts);
            BodyPartsInputStream in = new BodyPartsInputStream(parts);
            for (byte b : expected) {
                assertEquals(b & 0xff, in.read());
            }
            assertEquals(-1, in.read());
            assertEquals(-1, in.read());
        }
    }

    @Test
    public void bulkReadsCrossPartBoundaries() {
        for (List<HttpResponseBodyPart> parts : shapes()) {
            byte[] expected = concatenation(parts);
            BodyPartsInputStream in = new BodyPartsInputStream(parts);
            byte[] actual = new byte[expected.length];
            int filled = 0;
            int n;
            byte[] buffer = new byte[7];
            while ((n = in.read(buffer, 1, 5)) != -1) {
                assertTrue(n > 0 && n <= 5);
                System.arraycopy(buffer, 1, actual, filled, n);
                filled += n;
            }
            assertEquals(expected.length, filled);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void emptyBody() {
        BodyPartsInputStream in = new BodyPartsInputStream(new ArrayList<>());
        assertEquals(0, in.available());
        assertEquals(-1, in.read());
        assertEquals(-1, in.read(new byte[4], 0, 4));
        assertEquals(0, in.skip(10));
        assertArrayEquals(new byte[0], in.readAllBytes());
        in.reset();
        assertEquals(-1, in.read());
    }

    @Test
    public void availableCountsWhatIsLeft() {
        BodyPartsInputStream in = new BodyPartsInputStream(eagerParts(3, 0, 4));
        assertEquals(7, in.available());
        in.read();
        assertEquals(6, in.available());
        in.read(new byte[4], 0, 4);
        assertEquals(2, in.available());
        in.readAllBytes();
        assertEquals(0, in.available());
    }

    @Test
    public void zeroLengthReadReturnsZeroUntilTheEndAsByteArrayInputStreamDoes() {
        BodyPartsInputStream in = new BodyPartsInputStream(eagerParts(2));
        assertEquals(0, in.read(new byte[1], 0, 0));
        in.skip(2);
        assertEquals(-1, in.read(new byte[1], 0, 0));
    }

    @Test
    public void skipIsClampedToWhatIsLeft() {
        BodyPartsInputStream in = new BodyPartsInputStream(eagerParts(3, 4, 5));
        assertEquals(0, in.skip(-1));
        assertEquals(0, in.skip(0));
        assertEquals(5, in.skip(5));
        assertEquals(5, in.read());
        assertEquals(6, in.skip(100));
        assertEquals(0, in.skip(1));
        assertEquals(-1, in.read());
    }

    @Test
    public void markAndResetAcrossParts() {
        BodyPartsInputStream in = new BodyPartsInputStream(eagerParts(3, 4, 5));
        assertTrue(in.markSupported());
        // With no mark, reset goes back to the start, as ByteArrayInputStream's does.
        in.skip(6);
        in.reset();
        assertEquals(0, in.read());

        in.skip(1);
        in.mark(0);
        byte[] first = new byte[8];
        assertEquals(8, in.read(first, 0, 8));
        in.reset();
        byte[] again = new byte[8];
        assertEquals(8, in.read(again, 0, 8));
        assertArrayEquals(first, again);
        assertEquals(2, first[0]);

        in.readAllBytes();
        in.mark(0);
        in.reset();
        assertEquals(-1, in.read());
    }

    @Test
    public void behavesAsByteArrayInputStreamOverTheConcatenation() {
        Random random = new Random(42);
        for (List<HttpResponseBodyPart> parts : shapes()) {
            byte[] expected = concatenation(parts);
            for (int round = 0; round < 20; round++) {
                ByteArrayInputStream reference = new ByteArrayInputStream(expected);
                BodyPartsInputStream in = new BodyPartsInputStream(parts);
                for (int op = 0; op < 60; op++) {
                    switch (random.nextInt(6)) {
                        case 0:
                            assertEquals(reference.read(), in.read());
                            break;
                        case 1: {
                            int len = random.nextInt(40);
                            byte[] a = new byte[len + 2];
                            byte[] b = new byte[len + 2];
                            assertEquals(reference.read(a, 1, len), in.read(b, 1, len));
                            assertArrayEquals(a, b);
                            break;
                        }
                        case 2: {
                            long n = random.nextInt(50) - 5;
                            assertEquals(reference.skip(n), in.skip(n));
                            break;
                        }
                        case 3:
                            assertEquals(reference.available(), in.available());
                            break;
                        case 4:
                            reference.mark(0);
                            in.mark(0);
                            break;
                        default:
                            reference.reset();
                            in.reset();
                            break;
                    }
                }
                assertArrayEquals(reference.readAllBytes(), in.readAllBytes());
            }
        }
    }

    @Test
    public void transferToNeverHandsOutAPartArray() throws IOException {
        List<HttpResponseBodyPart> parts = eagerParts(5, 7, 9);
        List<byte[]> handedOut = new ArrayList<>();
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        long transferred = new BodyPartsInputStream(parts).transferTo(new OutputStream() {
            @Override
            public void write(int b) {
                written.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                handedOut.add(b);
                written.write(b, off, len);
            }
        });

        assertEquals(21, transferred);
        assertArrayEquals(concatenation(parts), written.toByteArray());
        for (byte[] array : handedOut) {
            for (HttpResponseBodyPart part : parts) {
                assertNotSame(part.getBodyPartBytes(), array);
            }
        }
    }

    @Test
    public void readsOnlyTheReadableRegionOfLazyParts() {
        byte[] backing = "XXHello, YYWorldZZ".getBytes(StandardCharsets.US_ASCII);
        List<HttpResponseBodyPart> parts = new ArrayList<>();
        parts.add(new LazyResponseBodyPart(Unpooled.wrappedBuffer(backing, 2, 7), false));
        parts.add(new LazyResponseBodyPart(Unpooled.wrappedBuffer(backing, 11, 5), true));

        assertEquals("Hello, World", new String(new BodyPartsInputStream(parts).readAllBytes(), StandardCharsets.US_ASCII));
    }

    @Test
    public void rejectsOutOfBoundsReads() {
        BodyPartsInputStream in = new BodyPartsInputStream(eagerParts(4));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[2], 1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[2], -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[2], 0, -1));
        // len is checked against the array, not against what is left: only 4 bytes would be copied here.
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[5], 0, 10));
        assertEquals(4, in.available());
    }

    @Test
    public void rejectedReadDoesNotMoveThePosition() {
        // The first part fits the array and the second does not, so a read checked only by arraycopy would
        // consume the first part before throwing.
        List<HttpResponseBodyPart> parts = eagerParts(3, 4);
        BodyPartsInputStream in = new BodyPartsInputStream(parts);
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[5], 0, 6));
        assertEquals(7, in.available());
        assertArrayEquals(concatenation(parts), in.readAllBytes());
    }

    @Test
    public void bulkReadsNeverReturnAPartArray() throws IOException {
        List<HttpResponseBodyPart> parts = eagerParts(11);
        byte[] partArray = parts.get(0).getBodyPartBytes();

        byte[] all = new BodyPartsInputStream(parts).readAllBytes();
        assertArrayEquals(partArray, all);
        assertNotSame(partArray, all);

        byte[] some = new BodyPartsInputStream(parts).readNBytes(100);
        assertArrayEquals(partArray, some);
        assertNotSame(partArray, some);
    }
}
