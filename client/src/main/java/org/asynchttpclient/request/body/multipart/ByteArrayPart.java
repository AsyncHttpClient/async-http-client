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

import java.nio.charset.Charset;

import static org.asynchttpclient.util.Assertions.assertNotNull;

/**
 * A multipart part representing binary data from a byte array.
 * <p>
 * This class is used for in-memory binary data in multipart/form-data requests.
 * It provides a convenient way to upload binary content without requiring file I/O.
 * The content type is automatically determined from the file name if not explicitly
 * specified.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Simple byte array part with auto-detected content type
 * byte[] imageData = Files.readAllBytes(Paths.get("image.png"));
 * Part imagePart = new ByteArrayPart("image", imageData);
 *
 * // Byte array part with explicit content type
 * byte[] jsonData = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
 * Part jsonPart = new ByteArrayPart("data", jsonData, "application/json");
 *
 * // Byte array part with file name for content type detection
 * Part filePart = new ByteArrayPart("document", pdfBytes,
 *     null, null, "document.pdf");
 *
 * // Use in a multipart request
 * AsyncHttpClient client = asyncHttpClient();
 * client.preparePost("http://example.com/upload")
 *     .addBodyPart(imagePart)
 *     .addBodyPart(jsonPart)
 *     .execute();
 * }</pre>
 */
public class ByteArrayPart extends FileLikePart {

  private final byte[] bytes;

  /**
   * Constructs a byte array part with name and data.
   *
   * @param name  the name of the form field
   * @param bytes the binary data
   */
  public ByteArrayPart(String name, byte[] bytes) {
    this(name, bytes, null);
  }

  /**
   * Constructs a byte array part with name, data, and content type.
   *
   * @param name        the name of the form field
   * @param bytes       the binary data
   * @param contentType the content type, or {@code null} to auto-detect
   */
  public ByteArrayPart(String name, byte[] bytes, String contentType) {
    this(name, bytes, contentType, null);
  }

  /**
   * Constructs a byte array part with name, data, content type, and charset.
   *
   * @param name        the name of the form field
   * @param bytes       the binary data
   * @param contentType the content type, or {@code null} to auto-detect
   * @param charset     the character encoding, or {@code null} for default
   */
  public ByteArrayPart(String name, byte[] bytes, String contentType, Charset charset) {
    this(name, bytes, contentType, charset, null);
  }

  /**
   * Constructs a byte array part with name, data, content type, charset, and file name.
   *
   * @param name        the name of the form field
   * @param bytes       the binary data
   * @param contentType the content type, or {@code null} to auto-detect from fileName
   * @param charset     the character encoding, or {@code null} for default
   * @param fileName    the file name for content type detection and disposition header
   */
  public ByteArrayPart(String name, byte[] bytes, String contentType, Charset charset, String fileName) {
    this(name, bytes, contentType, charset, fileName, null);
  }

  /**
   * Constructs a byte array part with name, data, content type, charset, file name, and content ID.
   *
   * @param name        the name of the form field
   * @param bytes       the binary data
   * @param contentType the content type, or {@code null} to auto-detect from fileName
   * @param charset     the character encoding, or {@code null} for default
   * @param fileName    the file name for content type detection and disposition header
   * @param contentId   the content ID, or {@code null} if not needed
   */
  public ByteArrayPart(String name, byte[] bytes, String contentType, Charset charset, String fileName, String contentId) {
    this(name, bytes, contentType, charset, fileName, contentId, null);
  }

  /**
   * Constructs a byte array part with all optional parameters.
   *
   * @param name             the name of the form field
   * @param bytes            the binary data
   * @param contentType      the content type, or {@code null} to auto-detect from fileName
   * @param charset          the character encoding, or {@code null} for default
   * @param fileName         the file name for content type detection and disposition header
   * @param contentId        the content ID, or {@code null} if not needed
   * @param transferEncoding the transfer encoding, or {@code null} for default
   */
  public ByteArrayPart(String name, byte[] bytes, String contentType, Charset charset, String fileName, String contentId, String transferEncoding) {
    super(name, contentType, charset, fileName, contentId, transferEncoding);
    this.bytes = assertNotNull(bytes, "bytes");
  }

  /**
   * Returns the binary data of this part.
   *
   * @return the byte array containing the data
   */
  public byte[] getBytes() {
    return bytes;
  }
}
