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
 * A listener interface for receiving notifications about HTTP request and response transfer events.
 * <p>
 * Implementations of this interface can be registered with a {@link TransferCompletionHandler}
 * to track the progress of HTTP requests and responses, including header transmission,
 * byte transfer progress, completion, and error handling.
 * <p>
 * This interface is particularly useful for:
 * <ul>
 *   <li>Monitoring upload and download progress</li>
 *   <li>Implementing custom logging or metrics collection</li>
 *   <li>Building progress bars or status indicators</li>
 *   <li>Debugging network communication</li>
 * </ul>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * TransferListener listener = new TransferListener() {
 *     private long totalBytesReceived = 0;
 *
 *     @Override
 *     public void onRequestHeadersSent(HttpHeaders headers) {
 *         System.out.println("Sent headers: " + headers.size());
 *     }
 *
 *     @Override
 *     public void onResponseHeadersReceived(HttpHeaders headers) {
 *         System.out.println("Received headers: " + headers.size());
 *     }
 *
 *     @Override
 *     public void onBytesReceived(byte[] bytes) {
 *         totalBytesReceived += bytes.length;
 *         System.out.println("Downloaded: " + totalBytesReceived + " bytes");
 *     }
 *
 *     @Override
 *     public void onBytesSent(long amount, long current, long total) {
 *         int percent = (int) ((current * 100) / total);
 *         System.out.println("Upload: " + percent + "%");
 *     }
 *
 *     @Override
 *     public void onRequestResponseCompleted() {
 *         System.out.println("Transfer complete");
 *     }
 *
 *     @Override
 *     public void onThrowable(Throwable t) {
 *         System.err.println("Error: " + t.getMessage());
 *     }
 * };
 * }</pre>
 */
public interface TransferListener {

  /**
   * Invoked when the request headers begin to be sent to the server.
   * <p>
   * This is called after the connection is established but before the request body
   * (if any) is transmitted.
   *
   * @param headers the HTTP request headers being sent
   */
  void onRequestHeadersSent(HttpHeaders headers);

  /**
   * Invoked when the response headers are received from the server.
   * <p>
   * This is called after the server responds with headers but before the response
   * body (if any) is received.
   *
   * @param headers the HTTP response headers received
   */
  void onResponseHeadersReceived(HttpHeaders headers);

  /**
   * Invoked each time a chunk of the response body is received.
   * <p>
   * This method may be called multiple times for a single response as data arrives
   * in chunks from the server. The frequency depends on network conditions and
   * the size of the response.
   *
   * @param bytes the chunk of response body bytes received
   */
  void onBytesReceived(byte[] bytes);

  /**
   * Invoked periodically during the request body upload to report progress.
   * <p>
   * This method is called multiple times as the request body is sent to the server,
   * allowing tracking of upload progress. It is only invoked for requests that have
   * a body (POST, PUT, etc.) and when the body requires multiple write operations.
   *
   * @param amount the number of bytes written in the current write operation
   * @param current the cumulative number of bytes written so far
   * @param total the total number of bytes to be written
   */
  void onBytesSent(long amount, long current, long total);

  /**
   * Invoked when the request and response transfer has been fully completed.
   * <p>
   * This is called after all request bytes have been sent and all response bytes
   * have been received, indicating successful completion of the HTTP transaction.
   */
  void onRequestResponseCompleted();

  /**
   * Invoked when an error or exception occurs during the request or response processing.
   * <p>
   * This method is called for any unexpected issues that occur during the HTTP
   * transaction, including network errors, timeouts, or protocol violations.
   *
   * @param t the exception or error that occurred
   */
  void onThrowable(Throwable t);
}

