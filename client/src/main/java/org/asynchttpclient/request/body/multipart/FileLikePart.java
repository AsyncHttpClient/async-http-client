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
 * This class is an adaptation of the Apache HttpClient implementation
 */
public abstract class FileLikePart extends PartBase {

  private static final MimetypesFileTypeMap MIME_TYPES_FILE_TYPE_MAP;

  static {
    try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("ahc-mime.types")) {
      MIME_TYPES_FILE_TYPE_MAP = new MimetypesFileTypeMap(is);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Default content encoding of file attachments.
   */
  private String fileName;

  /**
   * FilePart Constructor.
   *
   * @param name              the name for this part
   * @param contentType       the content type for this part, if <code>null</code> try to figure out from the fileName mime type
   * @param charset           the charset encoding for this part
   * @param fileName          the fileName
   * @param contentId         the content id
   * @param transfertEncoding the transfer encoding
   */
  public FileLikePart(String name, String contentType, Charset charset, String fileName, String contentId, String transfertEncoding) {
    super(name,//
            computeContentType(contentType, fileName),//
            charset,//
            contentId,//
            transfertEncoding);
    this.fileName = fileName;
  }

  private static String computeContentType(String contentType, String fileName) {
    return contentType != null ? contentType : MIME_TYPES_FILE_TYPE_MAP.getContentType(withDefault(fileName, ""));
  }

  public String getFileName() {
    return fileName;
  }

  @Override
  public String toString() {
    return super.toString() + " filename=" + fileName;
  }
}
