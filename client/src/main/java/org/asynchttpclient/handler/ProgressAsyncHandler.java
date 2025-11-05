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

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;

/**
 * An extended {@link AsyncHandler} with additional callbacks invoked during content upload to a remote server.
 * <p>
 * This interface provides progress tracking for request body uploads, making it useful for
 * monitoring file uploads or large POST/PUT requests. It adds three methods to the standard
 * {@link AsyncHandler} that are called at different stages of the upload process.
 * <p>
 * This handler should be used with PUT and POST requests that include a request body.
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * ProgressAsyncHandler<Response> handler = new AsyncCompletionHandlerBase() {
 *     @Override
 *     public State onHeadersWritten() {
 *         System.out.println("Request headers sent");
 *         return State.CONTINUE;
 *     }
 *
 *     @Override
 *     public State onContentWriteProgress(long amount, long current, long total) {
 *         int percent = (int) ((current * 100.0) / total);
 *         System.out.println("Upload progress: " + percent + "%");
 *         return State.CONTINUE;
 *     }
 *
 *     @Override
 *     public State onContentWritten() {
 *         System.out.println("Request body fully sent");
 *         return State.CONTINUE;
 *     }
 * };
 *
 * client.preparePost("http://example.com/upload")
 *     .setBody(largeFile)
 *     .execute(handler);
 * }</pre>
 *
 * @param <T> the response type
 */
public interface ProgressAsyncHandler<T> extends AsyncHandler<T> {

  /**
   * Invoked when the request headers have been fully written to the I/O socket.
   * <p>
   * This callback is triggered after all HTTP headers have been sent to the server,
   * but before the request body (if any) is transmitted.
   *
   * @return {@link State#CONTINUE} to proceed with sending the request body, or
   *         {@link State#ABORT} to cancel the request
   */
  State onHeadersWritten();

  /**
   * Invoked when the request body has been fully written to the I/O socket.
   * <p>
   * This callback is triggered after the entire request body (such as a {@link java.io.File},
   * {@link String}, or {@link java.io.InputStream}) has been completely transmitted to the server.
   *
   * @return {@link State#CONTINUE} to proceed with receiving the response, or
   *         {@link State#ABORT} to cancel the request
   */
  State onContentWritten();

  /**
   * Invoked periodically during the request body upload to report progress.
   * <p>
   * This callback is triggered when the I/O operation associated with the {@link Request} body
   * requires multiple write operations. It is never invoked if the entire body is written
   * in a single I/O operation. This allows tracking upload progress for large request bodies.
   *
   * @param amount the number of bytes written in the current write operation
   * @param current the cumulative number of bytes written so far
   * @param total the total number of bytes to be written
   * @return {@link State#CONTINUE} to proceed with the upload, or
   *         {@link State#ABORT} to cancel the request
   */
  State onContentWriteProgress(long amount, long current, long total);
}
