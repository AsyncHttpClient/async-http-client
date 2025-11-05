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
 *
 */
package org.asynchttpclient;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.handler.ProgressAsyncHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A convenient {@link AsyncHandler} implementation that provides a simpler completion callback.
 * <p>
 * Instead of implementing all the low-level {@link AsyncHandler} methods, subclasses only need to
 * implement {@link #onCompleted(Response)} which is called with the fully assembled {@link Response}.
 * </p>
 * <p>
 * This class handles accumulation of HTTP response status, headers, and body parts into a complete
 * Response object. It also implements {@link ProgressAsyncHandler} with default implementations
 * that simply return {@link org.asynchttpclient.AsyncHandler.State#CONTINUE}.
 * </p>
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 * client.prepareGet("http://example.com")
 *     .execute(new AsyncCompletionHandler<String>() {
 *         @Override
 *         public String onCompleted(Response response) throws Exception {
 *             return response.getResponseBody();
 *         }
 *     });
 * }</pre>
 *
 * @param <T> the type of value that will be returned by the associated {@link java.util.concurrent.Future}
 */
public abstract class AsyncCompletionHandler<T> implements ProgressAsyncHandler<T> {

  private static final Logger LOGGER = LoggerFactory.getLogger(AsyncCompletionHandler.class);
  private final Response.ResponseBuilder builder = new Response.ResponseBuilder();

  @Override
  public State onStatusReceived(HttpResponseStatus status) throws Exception {
    builder.reset();
    builder.accumulate(status);
    return State.CONTINUE;
  }

  @Override
  public State onHeadersReceived(HttpHeaders headers) throws Exception {
    builder.accumulate(headers);
    return State.CONTINUE;
  }

  @Override
  public State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
    builder.accumulate(content);
    return State.CONTINUE;
  }

  @Override
  public State onTrailingHeadersReceived(HttpHeaders headers) throws Exception {
    builder.accumulate(headers);
    return State.CONTINUE;
  }

  @Override
  public final T onCompleted() throws Exception {
    return onCompleted(builder.build());
  }

  @Override
  public void onThrowable(Throwable t) {
    LOGGER.debug(t.getMessage(), t);
  }

  /**
   * Invoked once the HTTP response has been fully received and assembled.
   * Subclasses must implement this method to process the complete response.
   *
   * @param response the fully assembled HTTP response
   * @return the value of type T that will be returned by the associated {@link java.util.concurrent.Future}
   * @throws Exception if an error occurs during response processing
   */
  abstract public T onCompleted(Response response) throws Exception;

  /**
   * Invoked when the HTTP request headers have been fully written to the I/O socket.
   * Default implementation continues processing.
   *
   * @return {@link org.asynchttpclient.AsyncHandler.State#CONTINUE} to continue processing,
   *         or {@link org.asynchttpclient.AsyncHandler.State#ABORT} to abort
   */
  @Override
  public State onHeadersWritten() {
    return State.CONTINUE;
  }

  /**
   * Invoked when the HTTP request body (e.g., {@link java.io.File}, {@link String}, or
   * {@link java.io.InputStream}) has been fully written to the I/O socket.
   * Default implementation continues processing.
   *
   * @return {@link org.asynchttpclient.AsyncHandler.State#CONTINUE} to continue processing,
   *         or {@link org.asynchttpclient.AsyncHandler.State#ABORT} to abort
   */
  @Override
  public State onContentWritten() {
    return State.CONTINUE;
  }

  /**
   * Invoked to report progress as the {@link Request} body is being written.
   * Default implementation continues processing.
   *
   * @param amount  the amount of bytes written in this progress update
   * @param current the total amount of bytes written so far
   * @param total   the total number of bytes to be written, or -1 if unknown
   * @return {@link org.asynchttpclient.AsyncHandler.State#CONTINUE} to continue processing,
   *         or {@link org.asynchttpclient.AsyncHandler.State#ABORT} to abort
   */
  @Override
  public State onContentWriteProgress(long amount, long current, long total) {
    return State.CONTINUE;
  }
}
