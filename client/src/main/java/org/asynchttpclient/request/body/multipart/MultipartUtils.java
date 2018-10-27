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

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.request.body.multipart.part.*;

import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.HttpUtils.*;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

public class MultipartUtils {

  /**
   * Creates a new multipart entity containing the given parts.
   *
   * @param parts          the parts to include.
   * @param requestHeaders the request headers
   * @return a MultipartBody
   */
  public static MultipartBody newMultipartBody(List<Part> parts, HttpHeaders requestHeaders) {
    assertNotNull(parts, "parts");

    byte[] boundary;
    String contentType;

    String contentTypeHeader = requestHeaders.get(CONTENT_TYPE);
    if (isNonEmpty(contentTypeHeader)) {
      int boundaryLocation = contentTypeHeader.indexOf("boundary=");
      if (boundaryLocation != -1) {
        // boundary defined in existing Content-Type
        contentType = contentTypeHeader;
        boundary = (contentTypeHeader.substring(boundaryLocation + "boundary=".length()).trim()).getBytes(US_ASCII);
      } else {
        // generate boundary and append it to existing Content-Type
        boundary = computeMultipartBoundary();
        contentType = patchContentTypeWithBoundaryAttribute(contentTypeHeader, boundary);
      }
    } else {
      boundary = computeMultipartBoundary();
      contentType = patchContentTypeWithBoundaryAttribute(HttpHeaderValues.MULTIPART_FORM_DATA, boundary);
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
