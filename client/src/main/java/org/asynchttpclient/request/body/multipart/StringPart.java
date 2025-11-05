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

/**
 * A multipart part representing a string value (text field).
 * <p>
 * This class is used for text-based form fields in multipart/form-data requests.
 * String parts default to UTF-8 encoding if no charset is specified. The string
 * value must not contain NUL characters (U+0000) as per RFC 2048 section 2.8.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Simple text field
 * Part username = new StringPart("username", "john_doe");
 *
 * // Text field with explicit content type
 * Part description = new StringPart("description", "User profile", "text/plain");
 *
 * // Text field with custom charset
 * Part comment = new StringPart("comment", "Comment text",
 *     "text/plain", StandardCharsets.ISO_8859_1);
 *
 * // Use in a multipart request
 * AsyncHttpClient client = asyncHttpClient();
 * client.preparePost("http://example.com/form")
 *     .addBodyPart(username)
 *     .addBodyPart(description)
 *     .execute();
 * }</pre>
 */
public class StringPart extends PartBase {

  /**
   * Default charset for string parameters (UTF-8).
   */
  private static final Charset DEFAULT_CHARSET = UTF_8;

  /**
   * The string value of this part.
   */
  private final String value;

  /**
   * Constructs a string part with the specified name and value.
   *
   * @param name  the name of the form field
   * @param value the string value
   */
  public StringPart(String name, String value) {
    this(name, value, null);
  }

  /**
   * Constructs a string part with name, value, and content type.
   *
   * @param name        the name of the form field
   * @param value       the string value
   * @param contentType the content type, or {@code null} for default
   */
  public StringPart(String name, String value, String contentType) {
    this(name, value, contentType, null);
  }

  /**
   * Constructs a string part with name, value, content type, and charset.
   *
   * @param name        the name of the form field
   * @param value       the string value
   * @param contentType the content type, or {@code null} for default
   * @param charset     the character encoding, or {@code null} for UTF-8
   */
  public StringPart(String name, String value, String contentType, Charset charset) {
    this(name, value, contentType, charset, null);
  }

  /**
   * Constructs a string part with name, value, content type, charset, and content ID.
   *
   * @param name        the name of the form field
   * @param value       the string value
   * @param contentType the content type, or {@code null} for default
   * @param charset     the character encoding, or {@code null} for UTF-8
   * @param contentId   the content ID, or {@code null} if not needed
   */
  public StringPart(String name, String value, String contentType, Charset charset, String contentId) {
    this(name, value, contentType, charset, contentId, null);
  }

  /**
   * Constructs a string part with all optional parameters.
   *
   * @param name             the name of the form field
   * @param value            the string value
   * @param contentType      the content type, or {@code null} for default
   * @param charset          the character encoding, or {@code null} for UTF-8
   * @param contentId        the content ID, or {@code null} if not needed
   * @param transferEncoding the transfer encoding, or {@code null} for default
   * @throws IllegalArgumentException if the value contains NUL characters
   */
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

  /**
   * Returns the string value of this part.
   *
   * @return the string value
   */
  public String getValue() {
    return value;
  }
}
