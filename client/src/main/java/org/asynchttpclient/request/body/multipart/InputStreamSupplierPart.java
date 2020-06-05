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
import java.util.function.Supplier;

import static org.asynchttpclient.util.Assertions.assertNotNull;

public class InputStreamSupplierPart extends FileLikePart {

  private final Supplier<InputStream> inputStreamSupplier;
  private final long contentLength;

  public InputStreamSupplierPart(String name, Supplier<InputStream> inputStreamSupplier, String fileName) {
    this(name, inputStreamSupplier, fileName, -1);
  }

  public InputStreamSupplierPart(String name, Supplier<InputStream> inputStreamSupplier, String fileName, long contentLength) {
    this(name, inputStreamSupplier, fileName, contentLength, null);
  }

  public InputStreamSupplierPart(String name, Supplier<InputStream> inputStreamSupplier, String fileName, long contentLength, String contentType) {
    this(name, inputStreamSupplier, fileName, contentLength, contentType, null);
  }

  public InputStreamSupplierPart(String name, Supplier<InputStream> inputStreamSupplier, String fileName, long contentLength, String contentType, Charset charset) {
    this(name, inputStreamSupplier, fileName, contentLength, contentType, charset, null);
  }

  public InputStreamSupplierPart(String name, Supplier<InputStream> inputStreamSupplier, String fileName, long contentLength, String contentType, Charset charset,
                                 String contentId) {
    this(name, inputStreamSupplier, fileName, contentLength, contentType, charset, contentId, null);
  }

  public InputStreamSupplierPart(String name, Supplier<InputStream> inputStreamSupplier, String fileName, long contentLength, String contentType, Charset charset,
                                 String contentId, String transferEncoding) {
    super(name,
            contentType,
            charset,
            fileName,
            contentId,
            transferEncoding);
    this.inputStreamSupplier = assertNotNull(inputStreamSupplier, "inputStreamSupplier");
    this.contentLength = contentLength;
  }

  public InputStreamPart createInputStreamPart() {
    return new InputStreamPart(getName(), inputStreamSupplier.get(), getFileName(), contentLength, getContentType(), getCharset(), getContentId(), getTransferEncoding());
  }
}
