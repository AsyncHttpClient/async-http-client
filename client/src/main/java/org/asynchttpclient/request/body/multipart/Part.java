/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.request.body.multipart;

import org.asynchttpclient.Param;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Represents a part in a multipart HTTP request.
 * <p>
 * A part is a component of a multipart/form-data request, which allows combining
 * multiple pieces of data (text fields, files, etc.) in a single HTTP request body.
 * Each part has a name, optional content type, charset, transfer encoding, and other
 * metadata that controls how it is encoded in the request.
 * </p>
 * <p>
 * Common implementations include {@link StringPart} for text fields, {@link FilePart}
 * for file uploads, {@link ByteArrayPart} for in-memory binary data, and
 * {@link InputStreamPart} for streaming data.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create different types of parts
 * Part textField = new StringPart("username", "john_doe");
 * Part fileUpload = new FilePart("avatar", new File("photo.jpg"), "image/jpeg");
 * Part binaryData = new ByteArrayPart("data", bytes, "application/octet-stream");
 *
 * // Use parts in a multipart request
 * List<Part> parts = Arrays.asList(textField, fileUpload, binaryData);
 * AsyncHttpClient client = asyncHttpClient();
 * client.preparePost("http://example.com/upload")
 *     .setBodyParts(parts)
 *     .execute();
 * }</pre>
 */
public interface Part {

  /**
   * Returns the name of this part.
   * <p>
   * The name is used in the Content-Disposition header and corresponds to the
   * form field name in HTML form submissions.
   * </p>
   *
   * @return the part name, or {@code null} if no name is set
   */
  String getName();

  /**
   * Returns the content type of this part.
   * <p>
   * The content type specifies the MIME type of the part's content and is included
   * in the Content-Type header. If {@code null}, the Content-Type header may be
   * omitted or a default type may be used.
   * </p>
   *
   * @return the content type, or {@code null} to exclude the Content-Type header
   */
  String getContentType();

  /**
   * Returns the character encoding of this part.
   * <p>
   * The charset is appended to the Content-Type header and specifies how text
   * content should be decoded. This is primarily used for text-based parts.
   * </p>
   *
   * @return the character encoding, or {@code null} to exclude the charset parameter
   */
  Charset getCharset();

  /**
   * Returns the transfer encoding of this part.
   * <p>
   * The transfer encoding specifies how the part's content is encoded for transmission
   * and is included in the Content-Transfer-Encoding header.
   * </p>
   *
   * @return the transfer encoding, or {@code null} to exclude the Content-Transfer-Encoding header
   */
  String getTransferEncoding();

  /**
   * Returns the content ID of this part.
   * <p>
   * The content ID provides a unique identifier for the part and is included
   * in the Content-ID header. This is useful for referencing parts within
   * multipart messages.
   * </p>
   *
   * @return the content ID, or {@code null} to exclude the Content-ID header
   */
  String getContentId();

  /**
   * Returns the disposition type to be used in the Content-Disposition header.
   * <p>
   * The disposition type is typically "form-data" for multipart/form-data requests.
   * Other values like "attachment" may be used in different contexts.
   * </p>
   *
   * @return the disposition type, or {@code null} to use the default
   */
  String getDispositionType();

  /**
   * Returns the list of custom headers for this part.
   * <p>
   * Custom headers allow adding arbitrary HTTP headers to the part beyond the
   * standard Content-Disposition, Content-Type, and other predefined headers.
   * </p>
   *
   * @return the list of custom headers, or {@code null} if no custom headers are set
   */
  List<Param> getCustomHeaders();
}
