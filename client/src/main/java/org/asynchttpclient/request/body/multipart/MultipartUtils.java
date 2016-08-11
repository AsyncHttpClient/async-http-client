/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart;

import static io.netty.handler.codec.http.HttpHeaders.Values.MULTIPART_FORM_DATA;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.asynchttpclient.request.body.multipart.part.ByteArrayMultipartPart;
import org.asynchttpclient.request.body.multipart.part.FileMultipartPart;
import org.asynchttpclient.request.body.multipart.part.MessageEndMultipartPart;
import org.asynchttpclient.request.body.multipart.part.MultipartPart;
import org.asynchttpclient.request.body.multipart.part.StringMultipartPart;
import org.asynchttpclient.util.StringUtils;

public class MultipartUtils {

    /**
     * The pool of ASCII chars to be used for generating a multipart boundary.
     */
    private static byte[] MULTIPART_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(US_ASCII);

    /**
     * Creates a new multipart entity containing the given parts.
     * 
     * @param parts the parts to include.
     * @param requestHeaders the request headers
     * @return a MultipartBody
     */
    public static MultipartBody newMultipartBody(List<Part> parts, HttpHeaders requestHeaders) {
        assertNotNull(parts, "parts");

        byte[] boundary;
        String contentType;

        String contentTypeHeader = requestHeaders.get(HttpHeaders.Names.CONTENT_TYPE);
        if (isNonEmpty(contentTypeHeader)) {
            int boundaryLocation = contentTypeHeader.indexOf("boundary=");
            if (boundaryLocation != -1) {
                // boundary defined in existing Content-Type
                contentType = contentTypeHeader;
                boundary = (contentTypeHeader.substring(boundaryLocation + "boundary=".length()).trim()).getBytes(US_ASCII);
            } else {
                // generate boundary and append it to existing Content-Type
                boundary = generateBoundary();
                contentType = computeContentType(contentTypeHeader, boundary);
            }
        } else {
            boundary = generateBoundary();
            contentType = computeContentType(MULTIPART_FORM_DATA, boundary);
        }

        List<MultipartPart<? extends Part>> multipartParts = generateMultipartParts(parts, boundary);

        return new MultipartBody(multipartParts, contentType, boundary);
    }

    public static List<MultipartPart<? extends Part>> generateMultipartParts(List<Part> parts, byte[] boundary) {
        List<MultipartPart<? extends Part>> multipartParts = new ArrayList<>(parts.size());
        for (Part part : parts) {
            if (part instanceof FilePart) {
                multipartParts.add(new FileMultipartPart((FilePart) part, boundary));

            } else if (part instanceof ByteArrayPart) {
                multipartParts.add(new ByteArrayMultipartPart((ByteArrayPart) part, boundary));

            } else if (part instanceof StringPart) {
                multipartParts.add(new StringMultipartPart((StringPart) part, boundary));

            } else {
                throw new IllegalArgumentException("Unknown part type: " + part);
            }
        }
        // add an extra fake part for terminating the message
        multipartParts.add(new MessageEndMultipartPart(boundary));

        return multipartParts;
    }

    // a random size from 30 to 40
    private static byte[] generateBoundary() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        byte[] bytes = new byte[random.nextInt(11) + 30];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = MULTIPART_CHARS[random.nextInt(MULTIPART_CHARS.length)];
        }
        return bytes;
    }

    private static String computeContentType(String base, byte[] boundary) {
        StringBuilder buffer = StringUtils.stringBuilder().append(base);
        if (!base.endsWith(";"))
            buffer.append(';');
        return buffer.append(" boundary=").append(new String(boundary, US_ASCII)).toString();
    }
}
