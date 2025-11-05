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
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class implementing common functionality for multipart parts.
 * <p>
 * This class provides the core implementation of the {@link Part} interface,
 * managing common metadata such as name, content type, charset, transfer encoding,
 * disposition type, and custom headers. Concrete implementations extend this class
 * to provide specific content handling.
 * </p>
 */
public abstract class PartBase implements Part {

  /**
   * The name of the form field, used in the Content-Disposition header.
   */
  private final String name;

  /**
   * The MIME type of the part's content, used in the Content-Type header.
   */
  private final String contentType;

  /**
   * The character encoding, appended to the Content-Type header.
   */
  private final Charset charset;

  /**
   * The Content-Transfer-Encoding header value.
   */
  private final String transferEncoding;

  /**
   * The Content-ID header value, used to uniquely identify the part.
   */
  private final String contentId;

  /**
   * The disposition type used in the Content-Disposition header (e.g., "form-data").
   */
  private String dispositionType;

  /**
   * Additional custom headers to be included with this part.
   */
  private List<Param> customHeaders;

  /**
   * Constructs a part base with the specified metadata.
   *
   * @param name             the name of the form field, or {@code null}
   * @param contentType      the content type, or {@code null}
   * @param charset          the character encoding, or {@code null}
   * @param contentId        the content ID, or {@code null}
   * @param transferEncoding the transfer encoding, or {@code null}
   */
  public PartBase(String name, String contentType, Charset charset, String contentId, String transferEncoding) {
    this.name = name;
    this.contentType = contentType;
    this.charset = charset;
    this.contentId = contentId;
    this.transferEncoding = transferEncoding;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public String getContentType() {
    return this.contentType;
  }

  @Override
  public Charset getCharset() {
    return this.charset;
  }

  @Override
  public String getTransferEncoding() {
    return transferEncoding;
  }

  @Override
  public String getContentId() {
    return contentId;
  }

  @Override
  public String getDispositionType() {
    return dispositionType;
  }

  /**
   * Sets the disposition type for this part.
   *
   * @param dispositionType the disposition type (e.g., "form-data", "attachment")
   */
  public void setDispositionType(String dispositionType) {
    this.dispositionType = dispositionType;
  }

  @Override
  public List<Param> getCustomHeaders() {
    return customHeaders;
  }

  /**
   * Sets the custom headers for this part.
   *
   * @param customHeaders the list of custom headers, or {@code null} to clear
   */
  public void setCustomHeaders(List<Param> customHeaders) {
    this.customHeaders = customHeaders;
  }

  /**
   * Adds a custom header to this part.
   * <p>
   * If no custom headers have been set yet, a new list is created.
   * </p>
   *
   * @param name  the header name
   * @param value the header value
   */
  public void addCustomHeader(String name, String value) {
    if (customHeaders == null) {
      customHeaders = new ArrayList<>(2);
    }
    customHeaders.add(new Param(name, value));
  }

  /**
   * Returns a string representation of this part.
   *
   * @return a string containing the part's metadata
   */
  public String toString() {
    return getClass().getSimpleName() +
            " name=" + getName() +
            " contentType=" + getContentType() +
            " charset=" + getCharset() +
            " transferEncoding=" + getTransferEncoding() +
            " contentId=" + getContentId() +
            " dispositionType=" + getDispositionType();
  }
}
