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
   * Invoked as soon as the HTTP status line has been received.
   *
   * @param responseStatus the status code and text of the response
   * @return a {@link State} indicating whether to CONTINUE or ABORT the current processing
   * @throws Exception if an error occurs during processing
   */
  State onStatusReceived(HttpResponseStatus responseStatus) throws Exception;

  /**
   * Invoked as soon as the HTTP headers have been received.
   *
   * @param headers the HTTP response headers
   * @return a {@link State} indicating whether to CONTINUE or ABORT the current processing
   * @throws Exception if an error occurs during processing
   */
  State onHeadersReceived(HttpHeaders headers) throws Exception;

  /**
   * Invoked as soon as a response body part is received. May be invoked multiple times.
   * Note: Depending on the provider (Netty), this can be called with empty body parts.
   *
   * @param bodyPart the response body part
   * @return a {@link State} indicating whether to CONTINUE or ABORT the current processing.
   *         Aborting will also close the connection.
   * @throws Exception if an error occurs during processing
   */
  State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception;

  /**
   * Invoked when trailing HTTP headers have been received (HTTP/1.1 chunked encoding).
   * This is optional and only called if trailing headers are present in the response.
   *
   * @param headers the trailing HTTP headers
   * @return a {@link State} indicating whether to CONTINUE or ABORT the current processing
   * @throws Exception if an error occurs during processing
   */
  default State onTrailingHeadersReceived(HttpHeaders headers) throws Exception {
    return State.CONTINUE;
  }

  /**
   * Invoked when an unexpected exception occurs during response processing.
   * The exception may have been produced by the implementation of any onXXXReceived method.
   *
   * @param t the throwable that was caught
   */
  void onThrowable(Throwable t);

  /**
   * Invoked once the HTTP response processing is finished.
   * This method is always invoked as the last callback method.
   *
   * @return the value of type T that will be returned by the associated {@link java.util.concurrent.Future}
   * @throws Exception if an error occurs during processing
   */
  T onCompleted() throws Exception;

  /**
   * Notifies the handler before hostname resolution begins.
   *
   * @param name the hostname to be resolved
   */
  default void onHostnameResolutionAttempt(String name) {
  }

  // ////////// DNS /////////////////

  /**
   * Notifies the handler after hostname resolution has succeeded.
   *
   * @param name      the hostname that was resolved
   * @param addresses the list of resolved socket addresses
   */
  default void onHostnameResolutionSuccess(String name, List<InetSocketAddress> addresses) {
  }

  /**
   * Notifies the handler after hostname resolution has failed.
   *
   * @param name  the hostname that failed to resolve
   * @param cause the cause of the resolution failure
   */
  default void onHostnameResolutionFailure(String name, Throwable cause) {
  }

  // ////////////// TCP CONNECT ////////

  /**
   * Notifies the handler when attempting to open a new TCP connection.
   * This may be called multiple times if the hostname resolved to multiple addresses
   * and connection attempts to earlier addresses failed.
   *
   * @param remoteAddress the remote address being connected to
   */
  default void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
  }

  /**
   * Notifies the handler after a successful TCP connection.
   *
   * @param remoteAddress the remote address that was successfully connected to
   * @param connection    the established Netty channel
   */
  default void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
  }

  /**
   * Notifies the handler after a failed TCP connection attempt.
   * This may be called multiple times, or be followed by onTcpConnectSuccess,
   * when the hostname resolved to multiple addresses.
   *
   * @param remoteAddress the remote address that failed to connect
   * @param cause         the cause of the connection failure
   */
  default void onTcpConnectFailure(InetSocketAddress remoteAddress, Throwable cause) {
  }

  // ////////////// TLS ///////////////

  /**
   * Notifies the handler before TLS handshake begins.
   */
  default void onTlsHandshakeAttempt() {
  }

  /**
   * Notifies the handler after a successful TLS handshake.
   *
   * @param sslSession the established SSL session
   */
  default void onTlsHandshakeSuccess(SSLSession sslSession) {
  }

  /**
   * Notifies the handler after a failed TLS handshake.
   *
   * @param cause the cause of the handshake failure
   */
  default void onTlsHandshakeFailure(Throwable cause) {
  }

  // /////////// POOLING /////////////

  /**
   * Notifies the handler when attempting to fetch a connection from the pool.
   */
  default void onConnectionPoolAttempt() {
  }

  /**
   * Notifies the handler when a connection was successfully fetched from the pool.
   *
   * @param connection the pooled Netty channel
   */
  default void onConnectionPooled(Channel connection) {
  }

  /**
   * Notifies the handler when attempting to offer a connection back to the pool.
   *
   * @param connection the Netty channel being offered to the pool
   */
  default void onConnectionOffer(Channel connection) {
  }

  // //////////// SENDING //////////////

  /**
   * Notifies the handler when a request is being written to the channel.
   * If the original request causes multiple requests to be sent (e.g., due to
   * authorization challenges or retries), this will be called multiple times.
   *
   * @param request the actual request object being sent to the server
   */
  default void onRequestSend(NettyRequest request) {
  }

  /**
   * Notifies the handler each time a request is being retried.
   */
  default void onRetry() {
  }

  /**
   * State enum to control response processing flow.
   */
  enum State {

    /**
     * Abort the processing and close the connection.
     */
    ABORT,
    /**
     * Continue processing the response.
     */
    CONTINUE
  }
}
