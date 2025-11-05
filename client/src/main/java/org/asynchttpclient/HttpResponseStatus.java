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
 * Represents the HTTP response status line containing the status code and reason phrase.
 * <p>
 * This class is delivered to {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}
 * as the first callback when processing an HTTP response. It includes the status code
 * (e.g., 200, 404), status text (e.g., "OK", "Not Found"), protocol information,
 * and network addresses.
 * </p>
 *
 * @see AsyncHandler#onStatusReceived(HttpResponseStatus)
 */
public abstract class HttpResponseStatus {

  private final Uri uri;

  public HttpResponseStatus(Uri uri) {
    this.uri = uri;
  }

  /**
   * Returns the request URI associated with this response.
   *
   * @return the request {@link Uri}
   */
  public Uri getUri() {
    return uri;
  }

  /**
   * Returns the HTTP status code.
   *
   * @return the status code (e.g., 200, 404, 500)
   */
  public abstract int getStatusCode();

  /**
   * Returns the HTTP status text (reason phrase).
   *
   * @return the status text (e.g., "OK", "Not Found", "Internal Server Error")
   */
  public abstract String getStatusText();

  /**
   * Returns the protocol name from the status line.
   *
   * @return the protocol name (e.g., "HTTP")
   */
  public abstract String getProtocolName();

  /**
   * Returns the major version number of the protocol.
   *
   * @return the major version (e.g., 1 for HTTP/1.1)
   */
  public abstract int getProtocolMajorVersion();

  /**
   * Returns the minor version number of the protocol.
   *
   * @return the minor version (e.g., 1 for HTTP/1.1)
   */
  public abstract int getProtocolMinorVersion();

  /**
   * Returns the complete protocol name and version.
   *
   * @return the protocol text (e.g., "HTTP/1.1")
   */
  public abstract String getProtocolText();

  /**
   * Returns the remote socket address that the client connected to.
   *
   * @return the remote address, or null if not available
   */
  public abstract SocketAddress getRemoteAddress();

  /**
   * Returns the local socket address that the client connected from.
   *
   * @return the local address, or null if not available
   */
  public abstract SocketAddress getLocalAddress();

  /**
   * Returns a string representation showing the status code and text.
   *
   * @return status code followed by status text (e.g., "200 OK")
   */
  @Override
  public String toString() {
    return getStatusCode() + " " + getStatusText();
  }
}
