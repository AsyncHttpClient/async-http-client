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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.MiscUtils.withDefault;

public class StringPart extends PartBase {

  /**
   * Default charset of string parameters
   */
  private static final Charset DEFAULT_CHARSET = UTF_8;

  /**
   * Contents of this StringPart.
   */
  private final String value;

  public StringPart(String name, String value) {
    this(name, value, null);
  }

  public StringPart(String name, String value, String contentType) {
    this(name, value, contentType, null);
  }

  public StringPart(String name, String value, String contentType, Charset charset) {
    this(name, value, contentType, charset, null);
  }

  public StringPart(String name, String value, String contentType, Charset charset, String contentId) {
    this(name, value, contentType, charset, contentId, null);
  }

  public StringPart(String name, String value, String contentType, Charset charset, String contentId, String transferEncoding) {
    super(name, contentType, charsetOrDefault(charset), contentId, transferEncoding);
    assertNotNull(value, "value");

    if (value.indexOf(0) != -1)
      // See RFC 2048, 2.8. "8bit Data"
      throw new IllegalArgumentException("NULs may not be present in string parts");

    this.value = value;
  }

  private static Charset charsetOrDefault(Charset charset) {
    return withDefault(charset, DEFAULT_CHARSET);
  }

  public String getValue() {
    return value;
  }
}
