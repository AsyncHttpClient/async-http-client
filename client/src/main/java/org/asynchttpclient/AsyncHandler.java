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
 */
package org.asynchttpclient;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.netty.request.NettyRequest;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.util.List;


/**
 * An asynchronous handler or callback which gets invoked as soon as some data is available when
 * processing an asynchronous response.
 * <br>
 * Callback methods get invoked in the following order:
 * <ol>
 * <li>{@link #onStatusReceived(HttpResponseStatus)},</li>
 * <li>{@link #onHeadersReceived(HttpHeaders)},</li>
 * <li>{@link #onBodyPartReceived(HttpResponseBodyPart)}, which could be invoked multiple times,</li>
 * <li>{@link #onTrailingHeadersReceived(HttpHeaders)}, which is only invoked if trailing HTTP headers are received</li>
 * <li>{@link #onCompleted()}, once the response has been fully read.</li>
 * </ol>
 * <br>
 * Returning a {@link AsyncHandler.State#ABORT} from any of those callback methods will interrupt asynchronous response
 * processing. After that, only {@link #onCompleted()} is going to be called.
 * <br>
 * AsyncHandlers aren't thread safe. Hence, you should avoid re-using the same instance when doing concurrent requests.
 * As an example, the following may produce unexpected results:
 * <blockquote><pre>
 *   AsyncHandler ah = new AsyncHandler() {....};
 *   AsyncHttpClient client = new AsyncHttpClient();
 *   client.prepareGet("http://...").execute(ah);
 *   client.prepareGet("http://...").execute(ah);
 * </pre></blockquote>
 * It is recommended to create a new instance instead.
 * <p>
 * Do NOT perform any blocking operations in any of these methods. A typical example would be trying to send another
 * request and calling get() on its future.
 * There's a chance you might end up in a dead lock.
 * If you really need to perform a blocking operation, execute it in a different dedicated thread pool.
 *
 * @param <T> Type of object returned by the {@link java.util.concurrent.Future#get}
 */
public interface AsyncHandler<T> {

  /**
   * Invoked as soon as the HTTP status line has been received
   *
   * @param responseStatus the status code and test of the response
   * @return a {@link State} telling to CONTINUE or ABORT the current processing.
   * @throws Exception if something wrong happens
   */
  State onStatusReceived(HttpResponseStatus responseStatus) throws Exception;

  /**
   * Invoked as soon as the HTTP headers have been received.
   *
   * @param headers the HTTP headers.
   * @return a {@link State} telling to CONTINUE or ABORT the current processing.
   * @throws Exception if something wrong happens
   */
  State onHeadersReceived(HttpHeaders headers) throws Exception;

  /**
   * Invoked as soon as some response body part are received. Could be invoked many times.
   * Beware that, depending on the provider (Netty) this can be notified with empty body parts.
   *
   * @param bodyPart response's body part.
   * @return a {@link State} telling to CONTINUE or ABORT the current processing. Aborting will also close the connection.
   * @throws Exception if something wrong happens
   */
  State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception;

  /**
   * Invoked when trailing headers have been received.
   *
   * @param headers the trailing HTTP headers.
   * @return a {@link State} telling to CONTINUE or ABORT the current processing.
   * @throws Exception if something wrong happens
   */
  default State onTrailingHeadersReceived(HttpHeaders headers) throws Exception {
    return State.CONTINUE;
  }

  /**
   * Invoked when an unexpected exception occurs during the processing of the response. The exception may have been
   * produced by implementation of onXXXReceived method invocation.
   *
   * @param t a {@link Throwable}
   */
  void onThrowable(Throwable t);

  /**
   * Invoked once the HTTP response processing is finished.
   * <br>
   * Gets always invoked as last callback method.
   *
   * @return T Value that will be returned by the associated {@link java.util.concurrent.Future}
   * @throws Exception if something wrong happens
   */
  T onCompleted() throws Exception;

  /**
   * Notify the callback before hostname resolution
   *
   * @param name the name to be resolved
   */
  default void onHostnameResolutionAttempt(String name) {
  }

  // ////////// DNS /////////////////

  /**
   * Notify the callback after hostname resolution was successful.
   *
   * @param name      the name to be resolved
   * @param addresses the resolved addresses
   */
  default void onHostnameResolutionSuccess(String name, List<InetSocketAddress> addresses) {
  }

  /**
   * Notify the callback after hostname resolution failed.
   *
   * @param name  the name to be resolved
   * @param cause the failure cause
   */
  default void onHostnameResolutionFailure(String name, Throwable cause) {
  }

  // ////////////// TCP CONNECT ////////

  /**
   * Notify the callback when trying to open a new connection.
   * <p>
   * Might be called several times if the name was resolved to multiple addresses and we failed to connect to the first(s) one(s).
   *
   * @param remoteAddress the address we try to connect to
   */
  default void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
  }

  /**
   * Notify the callback after a successful connect
   *
   * @param remoteAddress the address we try to connect to
   * @param connection    the connection
   */
  default void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
  }

  /**
   * Notify the callback after a failed connect.
   * <p>
   * Might be called several times, or be followed by onTcpConnectSuccess when the name was resolved to multiple addresses.
   *
   * @param remoteAddress the address we try to connect to
   * @param cause         the cause of the failure
   */
  default void onTcpConnectFailure(InetSocketAddress remoteAddress, Throwable cause) {
  }

  // ////////////// TLS ///////////////

  /**
   * Notify the callback before TLS handshake
   */
  default void onTlsHandshakeAttempt() {
  }

  /**
   * Notify the callback after the TLS was successful
   */
  default void onTlsHandshakeSuccess(SSLSession sslSession) {
  }

  /**
   * Notify the callback after the TLS failed
   *
   * @param cause the cause of the failure
   */
  default void onTlsHandshakeFailure(Throwable cause) {
  }

  // /////////// POOLING /////////////

  /**
   * Notify the callback when trying to fetch a connection from the pool.
   */
  default void onConnectionPoolAttempt() {
  }

  /**
   * Notify the callback when a new connection was successfully fetched from the pool.
   *
   * @param connection the connection
   */
  default void onConnectionPooled(Channel connection) {
  }

  /**
   * Notify the callback when trying to offer a connection to the pool.
   *
   * @param connection the connection
   */
  default void onConnectionOffer(Channel connection) {
  }

  // //////////// SENDING //////////////

  /**
   * Notify the callback when a request is being written on the channel. If the original request causes multiple requests to be sent, for example, because of authorization or
   * retry, it will be notified multiple times.
   *
   * @param request the real request object as passed to the provider
   */
  default void onRequestSend(NettyRequest request) {
  }

  /**
   * Notify the callback every time a request is being retried.
   */
  default void onRetry() {
  }

  enum State {

    /**
     * Stop the processing.
     */
    ABORT,
    /**
     * Continue the processing
     */
    CONTINUE
  }
}
