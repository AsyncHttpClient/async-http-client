/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.asynchttpclient;

import org.asynchttpclient.uri.Uri;

import java.net.SocketAddress;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public abstract class HttpResponseStatus {

  private final Uri uri;

  public HttpResponseStatus(Uri uri) {
    this.uri = uri;
  }

  /**
   * Return the request {@link Uri}
   *
   * @return the request {@link Uri}
   */
  public Uri getUri() {
    return uri;
  }

  /**
   * Return the response status code
   *
   * @return the response status code
   */
  public abstract int getStatusCode();

  /**
   * Return the response status text
   *
   * @return the response status text
   */
  public abstract String getStatusText();

  /**
   * Protocol name from status line.
   *
   * @return Protocol name.
   */
  public abstract String getProtocolName();

  /**
   * Protocol major version.
   *
   * @return Major version.
   */
  public abstract int getProtocolMajorVersion();

  /**
   * Protocol minor version.
   *
   * @return Minor version.
   */
  public abstract int getProtocolMinorVersion();

  /**
   * Full protocol name + version
   *
   * @return protocol name + version
   */
  public abstract String getProtocolText();

  /**
   * Get remote address client initiated request to.
   *
   * @return remote address client initiated request to, may be {@code null}
   * if asynchronous provider is unable to provide the remote address
   */
  public abstract SocketAddress getRemoteAddress();

  /**
   * Get local address client initiated request from.
   *
   * @return local address client initiated request from, may be {@code null}
   * if asynchronous provider is unable to provide the local address
   */
  public abstract SocketAddress getLocalAddress();

  /**
   * Code followed by text.
   */
  @Override
  public String toString() {
    return getStatusCode() + " " + getStatusText();
  }
}
