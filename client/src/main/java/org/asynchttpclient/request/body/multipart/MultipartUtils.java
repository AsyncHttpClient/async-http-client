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

/**
 * Utility class for creating multipart request bodies.
 * <p>
 * This class provides static methods for constructing {@link MultipartBody} instances
 * from a list of {@link Part}s. It handles boundary generation, Content-Type header
 * construction, and conversion of parts to their multipart representation.
 * </p>
 */
public class MultipartUtils {

  /**
   * Creates a new multipart body containing the specified parts.
   * <p>
   * This method generates a multipart boundary, constructs the appropriate Content-Type
   * header, and creates a {@link MultipartBody} that encodes all the parts according to
   * the multipart/form-data specification. If a Content-Type header with a boundary is
   * already present in the request headers, that boundary is used; otherwise, a new
   * boundary is generated.
   * </p>
   *
   * @param parts          the parts to include in the multipart body
   * @param requestHeaders the request headers, used to check for existing boundary
   * @return a new multipart body containing the specified parts
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

  /**
   * Generates multipart part representations from a list of parts.
   * <p>
   * This method converts high-level {@link Part} objects into their corresponding
   * {@link MultipartPart} implementations that handle the actual encoding and transfer
   * of data. Each part type (FilePart, ByteArrayPart, etc.) is mapped to its specific
   * multipart implementation. A message end part is automatically appended to properly
   * terminate the multipart message.
   * </p>
   *
   * @param parts    the list of parts to convert
   * @param boundary the multipart boundary bytes
   * @return a list of multipart part implementations, including a terminating message end part
   */
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
