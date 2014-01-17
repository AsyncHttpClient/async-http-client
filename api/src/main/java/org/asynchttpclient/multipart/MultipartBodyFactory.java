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

import static org.asynchttpclient.util.MiscUtil.*;

import java.util.List;
import java.util.Random;

import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.util.StandardCharsets;

public final class MultipartBodyFactory {

    /**
     * The Content-Type for multipart/form-data.
     */
    private static final String MULTIPART_FORM_CONTENT_TYPE = "multipart/form-data";

    /**
     * The pool of ASCII chars to be used for generating a multipart boundary.
     */
    private static byte[] MULTIPART_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.US_ASCII);

    private MultipartBodyFactory() {
    }
    
    /**
     * Creates a new multipart entity containing the given parts.
     * 
     * @param parts
     *            The parts to include.
     */
    public static MultipartBody newMultipartBody(List<Part> parts, FluentCaseInsensitiveStringsMap requestHeaders) {
        if (parts == null) {
            throw new IllegalArgumentException("parts cannot be null");
        }
        
        byte[] multipartBoundary;
        String contentType;
        
        String contentTypeHeader = requestHeaders.getFirstValue("Content-Type");
        if (isNonEmpty(contentTypeHeader)) {
            int boundaryLocation = contentTypeHeader.indexOf("boundary=");
            if (boundaryLocation != -1) {
                // boundary defined in existing Content-Type
                contentType = contentTypeHeader;
                multipartBoundary = (contentTypeHeader.substring(boundaryLocation + "boundary=".length()).trim()).getBytes(StandardCharsets.US_ASCII);
            } else {
                // generate boundary and append it to existing Content-Type
                multipartBoundary = generateMultipartBoundary();
                contentType = computeContentType(contentTypeHeader, multipartBoundary);
            }
        } else {
            multipartBoundary = generateMultipartBoundary();
            contentType = computeContentType(MULTIPART_FORM_CONTENT_TYPE, multipartBoundary);
        }

        long contentLength = Part.getLengthOfParts(parts, multipartBoundary);
        
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
}
