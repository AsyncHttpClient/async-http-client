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

public class FilePart extends FileLikePart {

  private final File file;

  public FilePart(String name, File file) {
    this(name, file, null);
  }

  public FilePart(String name, File file, String contentType) {
    this(name, file, contentType, null);
  }

  public FilePart(String name, File file, String contentType, Charset charset) {
    this(name, file, contentType, charset, null);
  }

  public FilePart(String name, File file, String contentType, Charset charset, String fileName) {
    this(name, file, contentType, charset, fileName, null);
  }

  public FilePart(String name, File file, String contentType, Charset charset, String fileName, String contentId) {
    this(name, file, contentType, charset, fileName, contentId, null);
  }

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

  public File getFile() {
    return file;
  }
}
