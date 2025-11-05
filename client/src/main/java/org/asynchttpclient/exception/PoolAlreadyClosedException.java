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

import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;

/**
 * Exception thrown when attempting to use a connection pool that has already been closed.
 * This typically occurs when trying to execute a request after the {@link org.asynchttpclient.AsyncHttpClient}
 * has been closed.
 *
 * <p>This is a singleton exception optimized for performance by using a shared
 * instance with no stack trace.</p>
 */
@SuppressWarnings("serial")
public class PoolAlreadyClosedException extends IOException {

  /**
   * Singleton instance of this exception with no stack trace for performance optimization.
   */
  public static final PoolAlreadyClosedException INSTANCE = unknownStackTrace(new PoolAlreadyClosedException(), PoolAlreadyClosedException.class, "INSTANCE");

  private PoolAlreadyClosedException() {
    super("Pool is already closed");
  }
}
