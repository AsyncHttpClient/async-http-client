/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.exception;

import java.io.IOException;

/**
 * Exception thrown when the maximum number of total connections has been reached.
 * This occurs when the client attempts to open a new connection but has already
 * reached the configured {@code maxConnections} limit.
 *
 * <p>To resolve this, either:</p>
 * <ul>
 *   <li>Increase the {@code maxConnections} configuration value</li>
 *   <li>Ensure connections are being properly closed and returned to the pool</li>
 *   <li>Reduce the number of concurrent requests</li>
 * </ul>
 */
@SuppressWarnings("serial")
public class TooManyConnectionsException extends IOException {

  /**
   * Constructs a new exception with the maximum number of connections.
   *
   * @param max the maximum number of connections that was exceeded
   */
  public TooManyConnectionsException(int max) {
    super("Too many connections: " + max);
  }
}
