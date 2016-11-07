/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

public class Utf8ByteBufCharsetDecoder {

    private static final ThreadLocal<Utf8ByteBufCharsetDecoder> POOL = new ThreadLocal<Utf8ByteBufCharsetDecoder>() {
        protected Utf8ByteBufCharsetDecoder initialValue() {
            return new Utf8ByteBufCharsetDecoder();
        }
    };

    private static Utf8ByteBufCharsetDecoder pooledDecoder() {
        Utf8ByteBufCharsetDecoder decoder = POOL.get();
        decoder.reset();
        return decoder;
    }

    public static String decodeUtf8(ByteBuf buf) throws CharacterCodingException {
        return pooledDecoder().decode(buf);
    }

    public static String decodeUtf8(ByteBuf... bufs) throws CharacterCodingException {
        return pooledDecoder().decode(bufs);
    }

    private final CharsetDecoder decoder = UTF_8.newDecoder();
    protected CharBuffer charBuffer = allocateCharBuffer(1024);
    private ByteBuffer splitCharBuffer;

    protected void initSplitCharBuffer() {
        if (splitCharBuffer == null) {
            // UTF-8 chars are 4 bytes max
            splitCharBuffer = ByteBuffer.allocate(4);
        }
    }

    protected CharBuffer allocateCharBuffer(int l) {
        return CharBuffer.allocate(l);
    }

    private void ensureCapacity(int l) {
        if (charBuffer.position() == 0) {
            if (charBuffer.capacity() < l) {
                charBuffer = allocateCharBuffer(l);
            }
        } else if (charBuffer.remaining() < l) {
            CharBuffer newCharBuffer = allocateCharBuffer(charBuffer.position() + l);
            charBuffer.flip();
            newCharBuffer.put(charBuffer);
            charBuffer = newCharBuffer;
        }
    }

    public void reset() {
        decoder.reset();
        charBuffer.clear();
    }

    private static int charSize(byte firstByte) throws CharacterCodingException {
        if ((firstByte >> 5) == -2 && (firstByte & 0x1e) != 0) {
            // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
            return 2;

        } else if ((firstByte >> 4) == -2) {
            // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
            return 3;

        } else if ((firstByte >> 3) == -2) {
            // 4 bytes, 21 bits: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
            return 4;

        } else {
            // charSize isn't supposed to be called for regular bytes
            throw new CharacterCodingException();
        }
    }

    private void handleSplitCharBuffer(ByteBuffer nioBuffer, boolean endOfInput) throws CharacterCodingException {
        // TODO we could save charSize
        int missingBytes = charSize(splitCharBuffer.get(0)) - splitCharBuffer.position();

        if (nioBuffer.remaining() < missingBytes) {
            if (endOfInput) {
                throw new CharacterCodingException();
            }

            // still not enough bytes
            splitCharBuffer.put(nioBuffer);

        } else {
            // FIXME better way?
            for (int i = 0; i < missingBytes; i++) {
                splitCharBuffer.put(nioBuffer.get());
            }

            splitCharBuffer.flip();
            CoderResult res = decoder.decode(splitCharBuffer, charBuffer, endOfInput && !nioBuffer.hasRemaining());
            if (res.isError()) {
                res.throwException();
            }

            splitCharBuffer.position(0);
        }
    }

    protected void decodePartial(ByteBuffer nioBuffer, boolean endOfInput) throws CharacterCodingException {
        // deal with pending splitCharBuffer
        if (splitCharBuffer != null && splitCharBuffer.position() > 0 && nioBuffer.hasRemaining()) {
            handleSplitCharBuffer(nioBuffer, endOfInput);
        }

        // decode remaining buffer
        if (nioBuffer.hasRemaining()) {
            CoderResult res = decoder.decode(nioBuffer, charBuffer, endOfInput);
            if (res.isUnderflow()) {
                if (nioBuffer.remaining() > 0) {
                    initSplitCharBuffer();
                    splitCharBuffer.put(nioBuffer);
                }
            } else if (res.isError()) {
                res.throwException();
            }
        }
    }

    private void decode(ByteBuffer[] nioBuffers, int length) throws CharacterCodingException {
        int count = nioBuffers.length;
        for (int i = 0; i < count; i++) {
            decodePartial(nioBuffers[i].duplicate(), i == count - 1);
        }
    }

    private void decodeSingleNioBuffer(ByteBuffer nioBuffer, int length) throws CharacterCodingException {
        CoderResult res = decoder.decode(nioBuffer, charBuffer, true);
        if (res.isError()) {
            res.throwException();
        }
    }

    public String decode(ByteBuf buf) throws CharacterCodingException {
        if (buf.isDirect()) {
            return buf.toString(UTF_8);
        }

        int length = buf.readableBytes();
        ensureCapacity(length);

        if (buf.nioBufferCount() == 1) {
            decodeSingleNioBuffer(buf.internalNioBuffer(buf.readerIndex(), length).duplicate(), length);
        } else {
            decode(buf.nioBuffers(), buf.readableBytes());
        }

        return charBuffer.flip().toString();
    }

    public String decode(ByteBuf... bufs) throws CharacterCodingException {
        if (bufs.length == 1) {
            return decode(bufs[0]);
        }

        int totalSize = 0;
        int totalNioBuffers = 0;
        boolean withoutArray = false;
        for (ByteBuf buf : bufs) {
            if (!buf.hasArray()) {
                withoutArray = true;
                break;
            }
            totalSize += buf.readableBytes();
            totalNioBuffers += buf.nioBufferCount();
        }

        if (withoutArray) {
            return ByteBufUtils.decodeNonOptimized(UTF_8, bufs);

        } else {
            ByteBuffer[] nioBuffers = new ByteBuffer[totalNioBuffers];
            int i = 0;
            for (ByteBuf buf : bufs) {
                for (ByteBuffer nioBuffer : buf.nioBuffers()) {
                    nioBuffers[i++] = nioBuffer;
                }
            }

            ensureCapacity(totalSize);
            decode(nioBuffers, totalSize);

            return charBuffer.flip().toString();
        }
    }
}
