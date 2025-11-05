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

import java.io.File;
import java.nio.charset.Charset;

import static org.asynchttpclient.util.Assertions.assertNotNull;

/**
 * A multipart part representing a file upload.
 * <p>
 * This class is used for file uploads in multipart/form-data requests. The file must
 * be a regular file (not a directory) and must be readable. The content type is
 * automatically determined from the file name if not explicitly specified. If no file
 * name is provided, the actual file name is used.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Simple file upload
 * File document = new File("/path/to/document.pdf");
 * Part filePart = new FilePart("document", document);
 *
 * // File upload with explicit content type
 * File image = new File("/path/to/photo.jpg");
 * Part imagePart = new FilePart("photo", image, "image/jpeg");
 *
 * // File upload with custom file name in request
 * File data = new File("/path/to/data.bin");
 * Part dataPart = new FilePart("data", data, "application/octet-stream",
 *     null, "custom-name.bin");
 *
 * // Use in a multipart request
 * AsyncHttpClient client = asyncHttpClient();
 * client.preparePost("http://example.com/upload")
 *     .addBodyPart(filePart)
 *     .addBodyPart(imagePart)
 *     .execute();
 * }</pre>
 */
public class FilePart extends FileLikePart {

  private final File file;

  /**
   * Constructs a file part with name and file.
   *
   * @param name the name of the form field
   * @param file the file to upload
   * @throws IllegalArgumentException if the file is not a regular file or is not readable
   */
  public FilePart(String name, File file) {
    this(name, file, null);
  }

  /**
   * Constructs a file part with name, file, and content type.
   *
   * @param name        the name of the form field
   * @param file        the file to upload
   * @param contentType the content type, or {@code null} to auto-detect from file name
   * @throws IllegalArgumentException if the file is not a regular file or is not readable
   */
  public FilePart(String name, File file, String contentType) {
    this(name, file, contentType, null);
  }

  /**
   * Constructs a file part with name, file, content type, and charset.
   *
   * @param name        the name of the form field
   * @param file        the file to upload
   * @param contentType the content type, or {@code null} to auto-detect from file name
   * @param charset     the character encoding, or {@code null} for default
   * @throws IllegalArgumentException if the file is not a regular file or is not readable
   */
  public FilePart(String name, File file, String contentType, Charset charset) {
    this(name, file, contentType, charset, null);
  }

  /**
   * Constructs a file part with name, file, content type, charset, and custom file name.
   *
   * @param name        the name of the form field
   * @param file        the file to upload
   * @param contentType the content type, or {@code null} to auto-detect from fileName
   * @param charset     the character encoding, or {@code null} for default
   * @param fileName    the file name to use in the request, or {@code null} to use actual file name
   * @throws IllegalArgumentException if the file is not a regular file or is not readable
   */
  public FilePart(String name, File file, String contentType, Charset charset, String fileName) {
    this(name, file, contentType, charset, fileName, null);
  }

  /**
   * Constructs a file part with name, file, content type, charset, file name, and content ID.
   *
   * @param name        the name of the form field
   * @param file        the file to upload
   * @param contentType the content type, or {@code null} to auto-detect from fileName
   * @param charset     the character encoding, or {@code null} for default
   * @param fileName    the file name to use in the request, or {@code null} to use actual file name
   * @param contentId   the content ID, or {@code null} if not needed
   * @throws IllegalArgumentException if the file is not a regular file or is not readable
   */
  public FilePart(String name, File file, String contentType, Charset charset, String fileName, String contentId) {
    this(name, file, contentType, charset, fileName, contentId, null);
  }

  /**
   * Constructs a file part with all optional parameters.
   *
   * @param name             the name of the form field
   * @param file             the file to upload
   * @param contentType      the content type, or {@code null} to auto-detect from fileName
   * @param charset          the character encoding, or {@code null} for default
   * @param fileName         the file name to use in the request, or {@code null} to use actual file name
   * @param contentId        the content ID, or {@code null} if not needed
   * @param transferEncoding the transfer encoding, or {@code null} for default
   * @throws IllegalArgumentException if the file is not a regular file or is not readable
   */
  public FilePart(String name, File file, String contentType, Charset charset, String fileName, String contentId, String transferEncoding) {
    super(name,
            contentType,
            charset,
            fileName != null ? fileName : file.getName(),
            contentId,
            transferEncoding);
    if (!assertNotNull(file, "file").isFile())
      throw new IllegalArgumentException("File is not a normal file " + file.getAbsolutePath());
    if (!file.canRead())
      throw new IllegalArgumentException("File is not readable " + file.getAbsolutePath());
    this.file = file;
  }

  /**
   * Returns the file being uploaded.
   *
   * @return the file
   */
  public File getFile() {
    return file;
  }
}
