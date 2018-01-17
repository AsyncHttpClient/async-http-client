/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.handler;

import io.netty.handler.codec.http.HttpHeaders;

/**
 * A simple interface an application can implements in order to received byte transfer information.
 */
public interface TransferListener {

  /**
   * Invoked when the request bytes are starting to get send.
   *
   * @param headers the headers
   */
  void onRequestHeadersSent(HttpHeaders headers);

  /**
   * Invoked when the response bytes are starting to get received.
   *
   * @param headers the headers
   */
  void onResponseHeadersReceived(HttpHeaders headers);

  /**
   * Invoked every time response's chunk are received.
   *
   * @param bytes a {@link byte[]}
   */
  void onBytesReceived(byte[] bytes);

  /**
   * Invoked every time request's chunk are sent.
   *
   * @param amount  The amount of bytes to transfer
   * @param current The amount of bytes transferred
   * @param total   The total number of bytes transferred
   */
  void onBytesSent(long amount, long current, long total);

  /**
   * Invoked when the response bytes are been fully received.
   */
  void onRequestResponseCompleted();

  /**
   * Invoked when there is an unexpected issue.
   *
   * @param t a {@link Throwable}
   */
  void onThrowable(Throwable t);
}

