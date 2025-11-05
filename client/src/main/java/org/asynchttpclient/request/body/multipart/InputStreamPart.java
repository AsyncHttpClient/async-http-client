/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
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

import java.io.InputStream;
import java.nio.charset.Charset;

import static org.asynchttpclient.util.Assertions.assertNotNull;

/**
 * A multipart part representing data from an input stream.
 * <p>
 * This class is used for streaming data uploads in multipart/form-data requests.
 * It allows uploading data from an {@link InputStream} without loading the entire
 * content into memory. The content length can be specified if known, or -1 if unknown.
 * The content type is automatically determined from the file name if not explicitly
 * specified.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Stream upload with known length
 * InputStream stream = new FileInputStream("data.bin");
 * long length = new File("data.bin").length();
 * Part streamPart = new InputStreamPart("file", stream, "data.bin", length);
 *
 * // Stream upload with unknown length
 * InputStream urlStream = new URL("http://example.com/data").openStream();
 * Part urlPart = new InputStreamPart("download", urlStream, "download.dat", -1);
 *
 * // Stream upload with explicit content type
 * InputStream jsonStream = new ByteArrayInputStream(jsonBytes);
 * Part jsonPart = new InputStreamPart("json", jsonStream, "data.json",
 *     jsonBytes.length, "application/json");
 *
 * // Use in a multipart request
 * AsyncHttpClient client = asyncHttpClient();
 * client.preparePost("http://example.com/upload")
 *     .addBodyPart(streamPart)
 *     .execute();
 * }</pre>
 */
public class InputStreamPart extends FileLikePart {

  private final InputStream inputStream;
  private final long contentLength;

  /**
   * Constructs an input stream part with name, stream, and file name.
   *
   * @param name        the name of the form field
   * @param inputStream the input stream to read from
   * @param fileName    the file name for content type detection and disposition header
   */
  public InputStreamPart(String name, InputStream inputStream, String fileName) {
    this(name, inputStream, fileName, -1);
  }

  /**
   * Constructs an input stream part with name, stream, file name, and content length.
   *
   * @param name          the name of the form field
   * @param inputStream   the input stream to read from
   * @param fileName      the file name for content type detection and disposition header
   * @param contentLength the total number of bytes to read, or -1 if unknown
   */
  public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength) {
    this(name, inputStream, fileName, contentLength, null);
  }

  /**
   * Constructs an input stream part with name, stream, file name, content length, and content type.
   *
   * @param name          the name of the form field
   * @param inputStream   the input stream to read from
   * @param fileName      the file name for content type detection and disposition header
   * @param contentLength the total number of bytes to read, or -1 if unknown
   * @param contentType   the content type, or {@code null} to auto-detect from fileName
   */
  public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength, String contentType) {
    this(name, inputStream, fileName, contentLength, contentType, null);
  }

  /**
   * Constructs an input stream part with name, stream, file name, content length, content type, and charset.
   *
   * @param name          the name of the form field
   * @param inputStream   the input stream to read from
   * @param fileName      the file name for content type detection and disposition header
   * @param contentLength the total number of bytes to read, or -1 if unknown
   * @param contentType   the content type, or {@code null} to auto-detect from fileName
   * @param charset       the character encoding, or {@code null} for default
   */
  public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength, String contentType, Charset charset) {
    this(name, inputStream, fileName, contentLength, contentType, charset, null);
  }

  /**
   * Constructs an input stream part with name, stream, file name, content length, content type, charset, and content ID.
   *
   * @param name          the name of the form field
   * @param inputStream   the input stream to read from
   * @param fileName      the file name for content type detection and disposition header
   * @param contentLength the total number of bytes to read, or -1 if unknown
   * @param contentType   the content type, or {@code null} to auto-detect from fileName
   * @param charset       the character encoding, or {@code null} for default
   * @param contentId     the content ID, or {@code null} if not needed
   */
  public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength, String contentType, Charset charset,
                         String contentId) {
    this(name, inputStream, fileName, contentLength, contentType, charset, contentId, null);
  }

  /**
   * Constructs an input stream part with all optional parameters.
   *
   * @param name             the name of the form field
   * @param inputStream      the input stream to read from
   * @param fileName         the file name for content type detection and disposition header
   * @param contentLength    the total number of bytes to read, or -1 if unknown
   * @param contentType      the content type, or {@code null} to auto-detect from fileName
   * @param charset          the character encoding, or {@code null} for default
   * @param contentId        the content ID, or {@code null} if not needed
   * @param transferEncoding the transfer encoding, or {@code null} for default
   */
  public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength, String contentType, Charset charset,
                         String contentId, String transferEncoding) {
    super(name,
            contentType,
            charset,
            fileName,
            contentId,
            transferEncoding);
    this.inputStream = assertNotNull(inputStream, "inputStream");
    this.contentLength = contentLength;
  }

  /**
   * Returns the input stream that provides the part's data.
   *
   * @return the input stream
   */
  public InputStream getInputStream() {
    return inputStream;
  }

  /**
   * Returns the content length of this part.
   *
   * @return the content length in bytes, or -1 if unknown
   */
  public long getContentLength() {
    return contentLength;
  }
}
