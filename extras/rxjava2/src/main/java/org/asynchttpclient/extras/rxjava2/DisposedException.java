/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.rxjava2;

import java.util.concurrent.CancellationException;

/**
 * Indicates that the HTTP request has been disposed asynchronously via RxJava 2.
 * <p>
 * This exception is used to signal early termination of HTTP request processing when
 * the RxJava 2 observer disposes of the subscription before the request completes.
 */
public class DisposedException extends CancellationException {
  private static final long serialVersionUID = -5885577182105850384L;

  /**
   * Creates a new DisposedException with the specified detail message.
   *
   * @param message the detail message explaining the disposal
   */
  public DisposedException(String message) {
    super(message);
  }
}
