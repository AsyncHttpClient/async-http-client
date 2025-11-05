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
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * An {@link org.asynchttpclient.AsyncHandler} that notifies registered {@link TransferListener}s
 * of various transfer events during request and response processing.
 * <p>
 * This handler extends {@link AsyncCompletionHandlerBase} and provides a mechanism to track
 * the progress of HTTP requests and responses by notifying listeners at key points:
 * <ul>
 *   <li>Request headers sent</li>
 *   <li>Response headers received</li>
 *   <li>Bytes sent (upload progress)</li>
 *   <li>Bytes received (download progress)</li>
 *   <li>Request/response completion</li>
 *   <li>Exceptions</li>
 * </ul>
 * <p>
 * By default, this handler does not accumulate response bytes in memory. To access the
 * response body via {@link org.asynchttpclient.Response#getResponseBody()}, construct
 * the handler with {@code accumulateResponseBytes = true}.
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * TransferCompletionHandler handler = new TransferCompletionHandler();
 * handler.addTransferListener(new TransferListener() {
 *     @Override
 *     public void onRequestHeadersSent(HttpHeaders headers) {
 *         System.out.println("Request headers sent");
 *     }
 *
 *     @Override
 *     public void onResponseHeadersReceived(HttpHeaders headers) {
 *         System.out.println("Response headers received");
 *     }
 *
 *     @Override
 *     public void onBytesReceived(byte[] bytes) {
 *         System.out.println("Received " + bytes.length + " bytes");
 *     }
 *
 *     @Override
 *     public void onBytesSent(long amount, long current, long total) {
 *         System.out.println("Sent " + current + " of " + total + " bytes");
 *     }
 *
 *     @Override
 *     public void onRequestResponseCompleted() {
 *         System.out.println("Transfer completed");
 *     }
 *
 *     @Override
 *     public void onThrowable(Throwable t) {
 *         System.err.println("Error: " + t.getMessage());
 *     }
 * });
 *
 * Response response = client.prepareGet("http://example.com").execute(handler).get();
 * }</pre>
 */
public class TransferCompletionHandler extends AsyncCompletionHandlerBase {
  private final static Logger logger = LoggerFactory.getLogger(TransferCompletionHandler.class);
  private final ConcurrentLinkedQueue<TransferListener> listeners = new ConcurrentLinkedQueue<>();
  private final boolean accumulateResponseBytes;
  private HttpHeaders headers;

  /**
   * Creates a TransferCompletionHandler that does not accumulate response bytes.
   * <p>
   * When using this constructor, the response body is not stored in memory.
   * Attempting to call {@link org.asynchttpclient.Response#getResponseBody()} or
   * {@link org.asynchttpclient.Response#getResponseBodyAsStream()} will throw an
   * {@link IllegalStateException}.
   * <p>
   * This is useful when you only need to track transfer progress through listeners
   * and don't need to access the response body through the Response object.
   */
  public TransferCompletionHandler() {
    this(false);
  }

  /**
   * Creates a TransferCompletionHandler with optional response byte accumulation.
   * <p>
   * When {@code accumulateResponseBytes} is {@code true}, the response body is stored
   * in memory and can be accessed via {@link org.asynchttpclient.Response#getResponseBody()}.
   * When {@code false}, response body methods will throw an {@link IllegalStateException}.
   *
   * @param accumulateResponseBytes {@code true} to accumulate response bytes in memory,
   *                                {@code false} to skip accumulation
   */
  public TransferCompletionHandler(boolean accumulateResponseBytes) {
    this.accumulateResponseBytes = accumulateResponseBytes;
  }

  /**
   * Adds a listener to be notified of transfer events.
   * <p>
   * Multiple listeners can be added. All registered listeners will be notified
   * of transfer events in the order they were added.
   *
   * @param t the listener to add
   * @return this handler for method chaining
   */
  public TransferCompletionHandler addTransferListener(TransferListener t) {
    listeners.offer(t);
    return this;
  }

  /**
   * Removes a previously registered listener.
   * <p>
   * If the listener was added multiple times, only the first occurrence is removed.
   *
   * @param t the listener to remove
   * @return this handler for method chaining
   */
  public TransferCompletionHandler removeTransferListener(TransferListener t) {
    listeners.remove(t);
    return this;
  }

  /**
   * Sets the request headers that will be sent to listeners when {@link #onHeadersWritten()} is called.
   * <p>
   * This method should be called before the request is executed to ensure listeners
   * are notified with the correct headers.
   *
   * @param headers the request headers to be sent to listeners
   */
  public void headers(HttpHeaders headers) {
    this.headers = headers;
  }

  /**
   * Processes response headers and notifies all registered listeners.
   *
   * @param headers the HTTP response headers received from the server
   * @return the state returned by the superclass method
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onHeadersReceived(final HttpHeaders headers) throws Exception {
    fireOnHeaderReceived(headers);
    return super.onHeadersReceived(headers);
  }

  /**
   * Processes trailing response headers and notifies all registered listeners.
   *
   * @param headers the trailing HTTP response headers received from the server
   * @return the state returned by the superclass method
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onTrailingHeadersReceived(HttpHeaders headers) throws Exception {
    fireOnHeaderReceived(headers);
    return super.onHeadersReceived(headers);
  }

  /**
   * Processes a response body chunk and notifies all registered listeners.
   * <p>
   * If byte accumulation is enabled, delegates to the superclass to store the bytes.
   *
   * @param content the chunk of response body received from the server
   * @return {@link State#CONTINUE} to proceed with processing
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
    State s = State.CONTINUE;
    if (accumulateResponseBytes) {
      s = super.onBodyPartReceived(content);
    }
    fireOnBytesReceived(content.getBodyPartBytes());
    return s;
  }

  /**
   * Completes the request and notifies all registered listeners.
   *
   * @param response the complete response received from the server
   * @return the response object
   * @throws Exception if an error occurs during completion
   */
  @Override
  public Response onCompleted(Response response) throws Exception {
    fireOnEnd();
    return response;
  }

  /**
   * Invoked when request headers have been written, notifies all registered listeners.
   *
   * @return {@link State#CONTINUE} to proceed with the request
   */
  @Override
  public State onHeadersWritten() {
    if (headers != null) {
      fireOnHeadersSent(headers);
    }
    return State.CONTINUE;
  }

  /**
   * Invoked during request body upload to report progress, notifies all registered listeners.
   *
   * @param amount the number of bytes written in the current write operation
   * @param current the cumulative number of bytes written so far
   * @param total the total number of bytes to be written
   * @return {@link State#CONTINUE} to proceed with the upload
   */
  @Override
  public State onContentWriteProgress(long amount, long current, long total) {
    fireOnBytesSent(amount, current, total);
    return State.CONTINUE;
  }

  /**
   * Handles exceptions that occur during request processing, notifies all registered listeners.
   *
   * @param t the exception that occurred
   */
  @Override
  public void onThrowable(Throwable t) {
    fireOnThrowable(t);
  }

  private void fireOnHeadersSent(HttpHeaders headers) {
    for (TransferListener l : listeners) {
      try {
        l.onRequestHeadersSent(headers);
      } catch (Throwable t) {
        l.onThrowable(t);
      }
    }
  }

  private void fireOnHeaderReceived(HttpHeaders headers) {
    for (TransferListener l : listeners) {
      try {
        l.onResponseHeadersReceived(headers);
      } catch (Throwable t) {
        l.onThrowable(t);
      }
    }
  }

  private void fireOnEnd() {
    for (TransferListener l : listeners) {
      try {
        l.onRequestResponseCompleted();
      } catch (Throwable t) {
        l.onThrowable(t);
      }
    }
  }

  private void fireOnBytesReceived(byte[] b) {
    for (TransferListener l : listeners) {
      try {
        l.onBytesReceived(b);
      } catch (Throwable t) {
        l.onThrowable(t);
      }
    }
  }

  private void fireOnBytesSent(long amount, long current, long total) {
    for (TransferListener l : listeners) {
      try {
        l.onBytesSent(amount, current, total);
      } catch (Throwable t) {
        l.onThrowable(t);
      }
    }
  }

  private void fireOnThrowable(Throwable t) {
    for (TransferListener l : listeners) {
      try {
        l.onThrowable(t);
      } catch (Throwable t2) {
        logger.warn("onThrowable", t2);
      }
    }
  }
}
