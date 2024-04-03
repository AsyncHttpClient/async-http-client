/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.request.body.multipart.part.ByteArrayMultipartPart;
import org.asynchttpclient.request.body.multipart.part.FileMultipartPart;
import org.asynchttpclient.request.body.multipart.part.InputStreamMultipartPart;
import org.asynchttpclient.request.body.multipart.part.MessageEndMultipartPart;
import org.asynchttpclient.request.body.multipart.part.MultipartPart;
import org.asynchttpclient.request.body.multipart.part.StringMultipartPart;

import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;
import static org.asynchttpclient.util.HttpUtils.computeMultipartBoundary;
import static org.asynchttpclient.util.HttpUtils.patchContentTypeWithBoundaryAttribute;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

public final class MultipartUtils {

    private MultipartUtils() {
        // Prevent outside initialization
    }

    /**
     * Creates a new multipart entity containing the given parts.
     *
     * @param parts          the parts to include.
     * @param requestHeaders the request headers
     * @return a MultipartBody
     */
    public static MultipartBody newMultipartBody(List<Part> parts, HttpHeaders requestHeaders) {
        requireNonNull(parts, "parts");

        byte[] boundary;
        String contentType;

        String contentTypeHeader = requestHeaders.get(CONTENT_TYPE);
        if (isNonEmpty(contentTypeHeader)) {
            int boundaryLocation = contentTypeHeader.indexOf("boundary=");
            if (boundaryLocation != -1) {
                // boundary defined in existing Content-Type
                contentType = contentTypeHeader;
                boundary = contentTypeHeader.substring(boundaryLocation + "boundary=".length()).trim().getBytes(US_ASCII);
            } else {
                // generate boundary and append it to existing Content-Type
                boundary = computeMultipartBoundary();
                contentType = patchContentTypeWithBoundaryAttribute(contentTypeHeader, boundary);
            }
        } else {
            boundary = computeMultipartBoundary();
            contentType = patchContentTypeWithBoundaryAttribute(HttpHeaderValues.MULTIPART_FORM_DATA.toString(), boundary);
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

            } else if (part instanceof InputStreamPart) {
                multipartParts.add(new InputStreamMultipartPart((InputStreamPart) part, boundary));

            } else {
                throw new IllegalArgumentException("Unknown part type: " + part);
            }
        }
        // add an extra fake part for terminating the message
        multipartParts.add(new MessageEndMultipartPart(boundary));
        return multipartParts;
    }
}
