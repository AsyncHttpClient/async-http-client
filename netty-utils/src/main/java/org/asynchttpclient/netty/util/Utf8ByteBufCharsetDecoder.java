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
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

public class Utf8ByteBufCharsetDecoder {

  private static final int INITIAL_CHAR_BUFFER_SIZE = 1024;
  private static final int UTF_8_MAX_BYTES_PER_CHAR = 4;
  private static final char INVALID_CHAR_REPLACEMENT = '�';

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

  public static String decodeUtf8(ByteBuf buf) {
    return pooledDecoder().decode(buf);
  }

  public static String decodeUtf8(ByteBuf... bufs) {
    return pooledDecoder().decode(bufs);
  }

  private static CharsetDecoder configureReplaceCodingErrorActions(CharsetDecoder decoder) {
    return decoder.onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
  }

  private final CharsetDecoder decoder = configureReplaceCodingErrorActions(UTF_8.newDecoder());
  protected CharBuffer charBuffer = allocateCharBuffer(INITIAL_CHAR_BUFFER_SIZE);
  private ByteBuffer splitCharBuffer = ByteBuffer.allocate(UTF_8_MAX_BYTES_PER_CHAR);

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
    configureReplaceCodingErrorActions(decoder.reset());
    charBuffer.clear();
    splitCharBuffer.clear();
  }

  private static int moreThanOneByteCharSize(byte firstByte) {
    if (firstByte >> 5 == -2 && (firstByte & 0x1e) != 0) {
      // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
      return 2;

    } else if (firstByte >> 4 == -2) {
      // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
      return 3;

    } else if (firstByte >> 3 == -2) {
      // 4 bytes, 21 bits: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
      return 4;

    } else {
      // charSize isn't supposed to be called for regular bytes
      // is that even possible?
      return -1;
    }
  }

  private static boolean isContinuation(byte b) {
    // 10xxxxxx
    return b >> 6 == -2;
  }

  private boolean stashContinuationBytes(ByteBuffer nioBuffer, int missingBytes) {
    for (int i = 0; i < missingBytes; i++) {
      byte b = nioBuffer.get();
      // make sure we only add continuation bytes in buffer
      if (isContinuation(b)) {
        splitCharBuffer.put(b);
      } else {
        // we hit a non-continuation byte
        // push it back and flush
        nioBuffer.position(nioBuffer.position() - 1);
        charBuffer.append(INVALID_CHAR_REPLACEMENT);
        splitCharBuffer.clear();
        return false;
      }
    }
    return true;
  }

  private void handlePendingSplitCharBuffer(ByteBuffer nioBuffer, boolean endOfInput) {

    int charSize = moreThanOneByteCharSize(splitCharBuffer.get(0));

    if (charSize > 0) {
      int missingBytes = charSize - splitCharBuffer.position();

      if (nioBuffer.remaining() < missingBytes) {
        if (endOfInput) {
          charBuffer.append(INVALID_CHAR_REPLACEMENT);
        } else {
          stashContinuationBytes(nioBuffer, nioBuffer.remaining());
        }

      } else if (stashContinuationBytes(nioBuffer, missingBytes)) {
        splitCharBuffer.flip();
        decoder.decode(splitCharBuffer, charBuffer, endOfInput && !nioBuffer.hasRemaining());
        splitCharBuffer.clear();
      }
    } else {
      // drop chars until we hit a non continuation one
      charBuffer.append(INVALID_CHAR_REPLACEMENT);
      splitCharBuffer.clear();
    }
  }

  protected void decodePartial(ByteBuffer nioBuffer, boolean endOfInput) {
    // deal with pending splitCharBuffer
    if (splitCharBuffer.position() > 0 && nioBuffer.hasRemaining()) {
      handlePendingSplitCharBuffer(nioBuffer, endOfInput);
    }

    // decode remaining buffer
    if (nioBuffer.hasRemaining()) {
      CoderResult res = decoder.decode(nioBuffer, charBuffer, endOfInput);
      if (res.isUnderflow()) {
        if (nioBuffer.remaining() > 0) {
          splitCharBuffer.put(nioBuffer);
        }
      }
    }
  }

  private void decode(ByteBuffer[] nioBuffers, int length) {
    int count = nioBuffers.length;
    for (int i = 0; i < count; i++) {
      decodePartial(nioBuffers[i].duplicate(), i == count - 1);
    }
  }

  private void decodeSingleNioBuffer(ByteBuffer nioBuffer, int length) {
    decoder.decode(nioBuffer, charBuffer, true);
  }

  public String decode(ByteBuf buf) {
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

  public String decode(ByteBuf... bufs) {
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
      return ByteBufUtils.byteBuf2StringDefault(UTF_8, bufs);

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
