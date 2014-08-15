/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.multipart;

import static org.asynchttpclient.multipart.Part.CRLF_BYTES;
import static org.asynchttpclient.multipart.Part.EXTRA_BYTES;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.util.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class MultipartUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultipartUtils.class);

    /**
     * The Content-Type for multipart/form-data.
     */
    private static final String MULTIPART_FORM_CONTENT_TYPE = "multipart/form-data";

    /**
     * The pool of ASCII chars to be used for generating a multipart boundary.
     */
    private static byte[] MULTIPART_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            .getBytes(StandardCharsets.US_ASCII);

    private MultipartUtils() {
    }

    /**
     * Creates a new multipart entity containing the given parts.
     * 
     * @param parts
     *            The parts to include.
     */
    public static MultipartBody newMultipartBody(List<Part> parts, FluentCaseInsensitiveStringsMap requestHeaders) {
        if (parts == null) {
            throw new NullPointerException("parts");
        }

        byte[] multipartBoundary;
        String contentType;

        String contentTypeHeader = requestHeaders.getFirstValue("Content-Type");
        if (isNonEmpty(contentTypeHeader)) {
            int boundaryLocation = contentTypeHeader.indexOf("boundary=");
            if (boundaryLocation != -1) {
                // boundary defined in existing Content-Type
                contentType = contentTypeHeader;
                multipartBoundary = (contentTypeHeader.substring(boundaryLocation + "boundary=".length()).trim())
                        .getBytes(StandardCharsets.US_ASCII);
            } else {
                // generate boundary and append it to existing Content-Type
                multipartBoundary = generateMultipartBoundary();
                contentType = computeContentType(contentTypeHeader, multipartBoundary);
            }
        } else {
            multipartBoundary = generateMultipartBoundary();
            contentType = computeContentType(MULTIPART_FORM_CONTENT_TYPE, multipartBoundary);
        }

        long contentLength = getLengthOfParts(parts, multipartBoundary);

        return new MultipartBody(parts, contentType, contentLength, multipartBoundary);
    }

    private static byte[] generateMultipartBoundary() {
        Random rand = new Random();
        byte[] bytes = new byte[rand.nextInt(11) + 30]; // a random size from 30 to 40
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = MULTIPART_CHARS[rand.nextInt(MULTIPART_CHARS.length)];
        }
        return bytes;
    }

    private static String computeContentType(String base, byte[] multipartBoundary) {
        StringBuilder buffer = new StringBuilder(base);
        if (!base.endsWith(";"))
            buffer.append(";");
        return buffer.append(" boundary=").append(new String(multipartBoundary, StandardCharsets.US_ASCII)).toString();
    }

    public static long writeBytesToChannel(WritableByteChannel target, byte[] bytes) throws IOException {

        int written = 0;
        int maxSpin = 0;
        ByteBuffer message = ByteBuffer.wrap(bytes);

        if (target instanceof SocketChannel) {
            final Selector selector = Selector.open();
            try {
                final SocketChannel channel = (SocketChannel) target;
                channel.register(selector, SelectionKey.OP_WRITE);

                while (written < bytes.length) {
                    selector.select(1000);
                    maxSpin++;
                    final Set<SelectionKey> selectedKeys = selector.selectedKeys();

                    for (SelectionKey key : selectedKeys) {
                        if (key.isWritable()) {
                            written += target.write(message);
                            maxSpin = 0;
                        }
                    }
                    if (maxSpin >= 10) {
                        throw new IOException("Unable to write on channel " + target);
                    }
                }
            } finally {
                selector.close();
            }
        } else {
            while ((target.isOpen()) && (written < bytes.length)) {
                long nWrite = target.write(message);
                written += nWrite;
                if (nWrite == 0 && maxSpin++ < 10) {
                    LOGGER.info("Waiting for writing...");
                    try {
                        bytes.wait(1000);
                    } catch (InterruptedException e) {
                        LOGGER.trace(e.getMessage(), e);
                    }
                } else {
                    if (maxSpin >= 10) {
                        throw new IOException("Unable to write on channel " + target);
                    }
                    maxSpin = 0;
                }
            }
        }
        return written;
    }

    public static byte[] getMessageEnd(byte[] partBoundary) throws IOException {

        if (!isNonEmpty(partBoundary))
            throw new IllegalArgumentException("partBoundary may not be empty");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamPartVisitor visitor = new OutputStreamPartVisitor(out);
        visitor.withBytes(EXTRA_BYTES);
        visitor.withBytes(partBoundary);
        visitor.withBytes(EXTRA_BYTES);
        visitor.withBytes(CRLF_BYTES);

        return out.toByteArray();
    }

    public static long getLengthOfParts(List<Part> parts, byte[] partBoundary) {

        try {
            if (parts == null) {
                throw new NullPointerException("parts");
            }
            long total = 0;
            for (Part part : parts) {
                long l = part.length(partBoundary);
                if (l < 0) {
                    return -1;
                }
                total += l;
            }
            total += EXTRA_BYTES.length;
            total += partBoundary.length;
            total += EXTRA_BYTES.length;
            total += CRLF_BYTES.length;
            return total;
        } catch (Exception e) {
            LOGGER.error("An exception occurred while getting the length of the parts", e);
            return 0L;
        }
    }
}
