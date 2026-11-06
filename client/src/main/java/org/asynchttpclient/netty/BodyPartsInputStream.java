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

import org.asynchttpclient.HttpResponseBodyPart;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Reads a response body straight out of its parts' arrays, with the semantics of a
 * {@link java.io.ByteArrayInputStream} over their concatenation, but without building that concatenation.
 * <p>
 * Unlike {@code ByteArrayInputStream}, it never hands one of its arrays to anything it is given: every read
 * copies into the caller's array, and {@link #transferTo} is {@link InputStream}'s, which goes through a buffer
 * of its own. The arrays are the parts' own storage, so letting them out would let a caller write into the body
 * every other accessor reads.
 */
final class BodyPartsInputStream extends InputStream {

    private static final byte[][] NO_CHUNKS = new byte[0][];

    // Never holds an empty array, so that the current chunk has a byte to read whenever remaining is not 0.
    private final byte[][] chunks;
    private int index;
    private int offset;
    private long remaining;

    private int markIndex;
    private int markOffset;
    private long markRemaining;

    BodyPartsInputStream(List<HttpResponseBodyPart> parts) {
        byte[][] chunks = parts.isEmpty() ? NO_CHUNKS : new byte[parts.size()][];
        int count = 0;
        long length = 0;
        for (HttpResponseBodyPart part : parts) {
            // Once per part: a lazy part copies out of its buffer on every call.
            byte[] bytes = part.getBodyPartBytes();
            if (bytes.length != 0) {
                chunks[count++] = bytes;
                length += bytes.length;
            }
        }
        this.chunks = chunks;
        remaining = length;
        markRemaining = length;
    }

    @Override
    public int read() {
        if (remaining == 0) {
            return -1;
        }
        byte[] chunk = chunks[index];
        int b = chunk[offset++] & 0xff;
        remaining--;
        if (offset == chunk.length) {
            index++;
            offset = 0;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        if (remaining == 0) {
            return -1;
        }
        int n = (int) Math.min(len, remaining);
        copyTo(b, off, n);
        return n;
    }

    @Override
    public byte[] readAllBytes() {
        if (remaining > Integer.MAX_VALUE - 8) {
            throw new OutOfMemoryError("Required array size too large");
        }
        byte[] all = new byte[(int) remaining];
        copyTo(all, 0, all.length);
        return all;
    }

    @Override
    public long skip(long n) {
        long k = Math.min(Math.max(n, 0), remaining);
        long left = k;
        while (left != 0) {
            int step = (int) Math.min(left, chunks[index].length - offset);
            advance(step);
            left -= step;
        }
        return k;
    }

    @Override
    public int available() {
        return (int) Math.min(remaining, Integer.MAX_VALUE);
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void mark(int readAheadLimit) {
        markIndex = index;
        markOffset = offset;
        markRemaining = remaining;
    }

    @Override
    public void reset() {
        index = markIndex;
        offset = markOffset;
        remaining = markRemaining;
    }

    private void copyTo(byte[] dst, int off, int n) {
        while (n != 0) {
            int step = Math.min(n, chunks[index].length - offset);
            System.arraycopy(chunks[index], offset, dst, off, step);
            advance(step);
            off += step;
            n -= step;
        }
    }

    private void advance(int step) {
        offset += step;
        remaining -= step;
        if (offset == chunks[index].length) {
            index++;
            offset = 0;
        }
    }
}
