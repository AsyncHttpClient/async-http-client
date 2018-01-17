package org.asynchttpclient.extras.simple;

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

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.uri.Uri;

/**
 * A simple transfer listener for use with the {@link SimpleAsyncHttpClient}.
 * <br>
 * Note: This listener does not cover requests failing before a connection is
 * established. For error handling, see
 * {@link SimpleAsyncHttpClient.Builder#setDefaultThrowableHandler(ThrowableHandler)}
 *
 * @author Benjamin Hanzelmann
 */
public interface SimpleAHCTransferListener {

  /**
   * This method is called after the connection status is received.
   *
   * @param uri        the uri
   * @param statusCode the received status code.
   * @param statusText the received status text.
   */
  void onStatus(Uri uri, int statusCode, String statusText);

  /**
   * This method is called after the response headers are received.
   *
   * @param uri     the uri
   * @param headers the received headers, never {@code null}.
   */
  void onHeaders(Uri uri, HttpHeaders headers);

  /**
   * This method is called when bytes of the responses body are received.
   *
   * @param uri     the uri
   * @param amount  the number of transferred bytes so far.
   * @param current the number of transferred bytes since the last call to this
   *                method.
   * @param total   the total number of bytes to be transferred. This is taken
   *                from the Content-Length-header and may be unspecified (-1).
   */
  void onBytesReceived(Uri uri, long amount, long current, long total);

  /**
   * This method is called when bytes are sent.
   *
   * @param uri     the uri
   * @param amount  the number of transferred bytes so far.
   * @param current the number of transferred bytes since the last call to this
   *                method.
   * @param total   the total number of bytes to be transferred. This is taken
   *                from the Content-Length-header and may be unspecified (-1).
   */
  void onBytesSent(Uri uri, long amount, long current, long total);

  /**
   * This method is called when the request is completed.
   *
   * @param uri        the uri
   * @param statusCode the received status code.
   * @param statusText the received status text.
   */
  void onCompleted(Uri uri, int statusCode, String statusText);
}

