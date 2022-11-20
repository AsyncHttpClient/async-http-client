/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.fileupload2.util.mime;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * @since 1.3
 */
final class Base64Decoder {

    /**
     * Decoding table value for invalid bytes.
     */
    private static final byte INVALID_BYTE = -1; // must be outside range 0-63

    /**
     * Decoding table value for padding bytes, so can detect PAD after conversion.
     */
    private static final int PAD_BYTE = -2; // must be outside range 0-63

    /**
     * Mask to treat byte as unsigned integer.
     */
    private static final int MASK_BYTE_UNSIGNED = 0xFF;

    /**
     * Number of bytes per encoded chunk - 4 6bit bytes produce 3 8bit bytes on output.
     */
    private static final int INPUT_BYTES_PER_CHUNK = 4;

    /**
     * Set up the encoding table.
     */
    private static final byte[] ENCODING_TABLE = {
        (byte) 'A', (byte) 'B', (byte) 'C', (byte) 'D', (byte) 'E', (byte) 'F', (byte) 'G',
        (byte) 'H', (byte) 'I', (byte) 'J', (byte) 'K', (byte) 'L', (byte) 'M', (byte) 'N',
        (byte) 'O', (byte) 'P', (byte) 'Q', (byte) 'R', (byte) 'S', (byte) 'T', (byte) 'U',
        (byte) 'V', (byte) 'W', (byte) 'X', (byte) 'Y', (byte) 'Z',
        (byte) 'a', (byte) 'b', (byte) 'c', (byte) 'd', (byte) 'e', (byte) 'f', (byte) 'g',
        (byte) 'h', (byte) 'i', (byte) 'j', (byte) 'k', (byte) 'l', (byte) 'm', (byte) 'n',
        (byte) 'o', (byte) 'p', (byte) 'q', (byte) 'r', (byte) 's', (byte) 't', (byte) 'u',
        (byte) 'v', (byte) 'w', (byte) 'x', (byte) 'y', (byte) 'z',
        (byte) '0', (byte) '1', (byte) '2', (byte) '3', (byte) '4', (byte) '5', (byte) '6',
        (byte) '7', (byte) '8', (byte) '9',
        (byte) '+', (byte) '/'
    };

    /**
     * The padding byte.
     */
    private static final byte PADDING = (byte) '=';

    /**
     * Set up the decoding table; this is indexed by a byte converted to an unsigned int,
     * so must be at least as large as the number of different byte values,
     * positive and negative and zero.
     */
    private static final byte[] DECODING_TABLE = new byte[Byte.MAX_VALUE - Byte.MIN_VALUE + 1];

    static {
        // Initialize as all invalid characters
        Arrays.fill(DECODING_TABLE, INVALID_BYTE);
        // set up valid characters
        for (int i = 0; i < ENCODING_TABLE.length; i++) {
            DECODING_TABLE[ENCODING_TABLE[i]] = (byte) i;
        }
        // Allow pad byte to be easily detected after conversion
        DECODING_TABLE[PADDING] = PAD_BYTE;
    }

    /**
     * Hidden constructor, this class must not be instantiated.
     */
    private Base64Decoder() {
        // do nothing
    }

    /**
     * Decode the base 64 encoded byte data writing it to the given output stream,
     * whitespace characters will be ignored.
     *
     * @param data the buffer containing the Base64-encoded data
     * @param out the output stream to hold the decoded bytes
     *
     * @return the number of bytes produced.
     * @throws IOException thrown when the padding is incorrect or the input is truncated.
     */
    public static int decode(final byte[] data, final OutputStream out) throws IOException {
        int        outLen = 0;
        final byte[] cache = new byte[INPUT_BYTES_PER_CHUNK];
        int cachedBytes = 0;

        for (final byte b : data) {
            final byte d = DECODING_TABLE[MASK_BYTE_UNSIGNED & b];
            if (d == INVALID_BYTE) {
                continue; // Ignore invalid bytes
            }
            cache[cachedBytes++] = d;
            if (cachedBytes == INPUT_BYTES_PER_CHUNK) {
                // CHECKSTYLE IGNORE MagicNumber FOR NEXT 4 LINES
                final byte b1 = cache[0];
                final byte b2 = cache[1];
                final byte b3 = cache[2];
                final byte b4 = cache[3];
                if (b1 == PAD_BYTE || b2 == PAD_BYTE) {
                    throw new IOException("Invalid Base64 input: incorrect padding, first two bytes cannot be padding");
                }
                // Convert 4 6-bit bytes to 3 8-bit bytes
                // CHECKSTYLE IGNORE MagicNumber FOR NEXT 1 LINE
                out.write((b1 << 2) | (b2 >> 4)); // 6 bits of b1 plus 2 bits of b2
                outLen++;
                if (b3 != PAD_BYTE) {
                    // CHECKSTYLE IGNORE MagicNumber FOR NEXT 1 LINE
                    out.write((b2 << 4) | (b3 >> 2)); // 4 bits of b2 plus 4 bits of b3
                    outLen++;
                    if (b4 != PAD_BYTE) {
                        // CHECKSTYLE IGNORE MagicNumber FOR NEXT 1 LINE
                        out.write((b3 << 6) | b4);        // 2 bits of b3 plus 6 bits of b4
                        outLen++;
                    }
                } else if (b4 != PAD_BYTE) { // if byte 3 is pad, byte 4 must be pad too
                    throw new // line wrap to avoid 120 char limit
                    IOException("Invalid Base64 input: incorrect padding, 4th byte must be padding if 3rd byte is");
                }
                cachedBytes = 0;
            }
        }
        // Check for anything left over
        if (cachedBytes != 0) {
            throw new IOException("Invalid Base64 input: truncated");
        }
        return outLen;
    }
}
