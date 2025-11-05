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

import javax.activation.MimetypesFileTypeMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import static org.asynchttpclient.util.MiscUtils.withDefault;

/**
 * Abstract base class for file-like multipart parts.
 * <p>
 * This class provides common functionality for parts that represent file-like content,
 * including file uploads ({@link FilePart}), byte arrays ({@link ByteArrayPart}), and
 * input streams ({@link InputStreamPart}). It handles automatic content type detection
 * based on file name extensions using a MIME types mapping.
 * </p>
 * <p>
 * This class is an adaptation of the Apache HttpClient implementation and includes
 * a built-in MIME types file (ahc-mime.types) for content type detection.
 * </p>
 */
public abstract class FileLikePart extends PartBase {

  private static final MimetypesFileTypeMap MIME_TYPES_FILE_TYPE_MAP;

  static {
    try (InputStream is = FileLikePart.class.getResourceAsStream("ahc-mime.types")) {
      MIME_TYPES_FILE_TYPE_MAP = new MimetypesFileTypeMap(is);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * The file name associated with this part.
   */
  private String fileName;

  /**
   * Constructs a file-like part with the specified parameters.
   * <p>
   * If no content type is provided, it will be automatically determined from the
   * file name extension using the built-in MIME types mapping.
   * </p>
   *
   * @param name             the name of the form field
   * @param contentType      the content type, or {@code null} to auto-detect from fileName
   * @param charset          the character encoding, or {@code null} for default
   * @param fileName         the file name for content type detection and disposition header
   * @param contentId        the content ID, or {@code null} if not needed
   * @param transferEncoding the transfer encoding, or {@code null} for default
   */
  public FileLikePart(String name, String contentType, Charset charset, String fileName, String contentId, String transferEncoding) {
    super(name,
            computeContentType(contentType, fileName),
            charset,
            contentId,
            transferEncoding);
    this.fileName = fileName;
  }

  /**
   * Computes the content type based on the provided type or file name.
   * <p>
   * If a content type is explicitly provided, it is used. Otherwise, the content type
   * is determined from the file name extension using the MIME types mapping.
   * </p>
   *
   * @param contentType the explicit content type, or {@code null}
   * @param fileName    the file name for type detection, or {@code null}
   * @return the computed content type
   */
  private static String computeContentType(String contentType, String fileName) {
    return contentType != null ? contentType : MIME_TYPES_FILE_TYPE_MAP.getContentType(withDefault(fileName, ""));
  }

  /**
   * Returns the file name associated with this part.
   *
   * @return the file name, or {@code null} if not set
   */
  public String getFileName() {
    return fileName;
  }

  /**
   * Returns a string representation of this part including the file name.
   *
   * @return a string representation of this part
   */
  @Override
  public String toString() {
    return super.toString() + " filename=" + fileName;
  }
}
