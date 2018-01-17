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

public abstract class PartBase implements Part {

  /**
   * The name of the form field, part of the Content-Disposition header
   */
  private final String name;

  /**
   * The main part of the Content-Type header
   */
  private final String contentType;

  /**
   * The charset (part of Content-Type header)
   */
  private final Charset charset;

  /**
   * The Content-Transfer-Encoding header value.
   */
  private final String transferEncoding;

  /**
   * The Content-Id
   */
  private final String contentId;

  /**
   * The disposition type (part of Content-Disposition)
   */
  private String dispositionType;

  /**
   * Additional part headers
   */
  private List<Param> customHeaders;

  /**
   * Constructor.
   *
   * @param name             The name of the part, or <code>null</code>
   * @param contentType      The content type, or <code>null</code>
   * @param charset          The character encoding, or <code>null</code>
   * @param contentId        The content id, or <code>null</code>
   * @param transferEncoding The transfer encoding, or <code>null</code>
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

  public void setDispositionType(String dispositionType) {
    this.dispositionType = dispositionType;
  }

  @Override
  public List<Param> getCustomHeaders() {
    return customHeaders;
  }

  public void setCustomHeaders(List<Param> customHeaders) {
    this.customHeaders = customHeaders;
  }

  public void addCustomHeader(String name, String value) {
    if (customHeaders == null) {
      customHeaders = new ArrayList<>(2);
    }
    customHeaders.add(new Param(name, value));
  }

  public String toString() {
    return getClass().getSimpleName() +
            " name=" + getName() +
            " contentType=" + getContentType() +
            " charset=" + getCharset() +
            " tranferEncoding=" + getTransferEncoding() +
            " contentId=" + getContentId() +
            " dispositionType=" + getDispositionType();
  }
}
