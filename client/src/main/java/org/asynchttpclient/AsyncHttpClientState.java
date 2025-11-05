/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents the lifecycle state of an {@link AsyncHttpClient} instance.
 * This class provides thread-safe access to the client's closed state.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 * AsyncHttpClientState state = client.getState();
 * if (!state.isClosed()) {
 *     // Safe to use client
 *     client.prepareGet("http://example.com").execute();
 * }
 * }</pre>
 */
public class AsyncHttpClientState {

  private final AtomicBoolean closed;

  AsyncHttpClientState(AtomicBoolean closed) {
    this.closed = closed;
  }

  /**
   * Returns whether the associated {@link AsyncHttpClient} has been closed.
   * Once closed, the client cannot be used to execute new requests.
   *
   * @return {@code true} if the client has been closed, {@code false} otherwise
   */
  public boolean isClosed() {
    return closed.get();
  }
}
