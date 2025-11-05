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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import io.netty.handler.codec.http.HttpHeaders;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;

/**
 * An AsyncHandler that returns Response (without body, so status code and
 * headers only) as fast as possible for inspection, but leaves you the option
 * to defer body consumption.
 * <br>
 * This class introduces new call: getResponse(), that blocks caller thread as
 * long as headers are received, and return Response as soon as possible, but
 * still pouring response body into supplied output stream. This handler is
 * meant for situations when the "recommended" way (using
 * <code>client.prepareGet("http://foo.com/aResource").execute().get()</code>
 * would not work for you, since a potentially large response body is about to
 * be GETted, but you need headers first, or you don't know yet (depending on
 * some logic, maybe coming from headers) where to save the body, or you just
 * want to leave body stream to some other component to consume it.
 * <br>
 * All these above means that this AsyncHandler needs a bit of different
 * handling than "recommended" way. Some examples:
 * <br>
 * <pre>
 *     OutputStream fos = ...
 *     BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(fos);
 *     // client executes async
 *     Future&lt;Response&gt; fr = client.prepareGet(&quot;http://foo.com/aresource&quot;).execute(
 * 	bdah);
 *     // main thread will block here until headers are available
 *     Response response = bdah.getResponse();
 *     // you can continue examine headers while actual body download happens
 *     // in separate thread
 *     // ...
 *     // finally &quot;join&quot; the download
 *     fr.get();
 * </pre>
 * <br>
 * <pre>
 *     PipedOutputStream pout = new PipedOutputStream();
 *     try (PipedInputStream pin = new PipedInputStream(pout)) {
 *         BodyDeferringAsyncHandler handler = new BodyDeferringAsyncHandler(pout);
 *         ListenableFuture&lt;Response&gt; respFut = client.prepareGet(getTargetUrl()).execute(handler);
 *         Response resp = handler.getResponse();
 *         // main thread will block here until headers are available
 *         if (resp.getStatusCode() == 200) {
 *             try (InputStream is = new BodyDeferringInputStream(respFut, handler, pin)) {
 *                 // consume InputStream
 *                 ...
 *             }
 *         } else {
 *             // handle unexpected response status code
 *             ...
 *         }
 *     }
 * </pre>
 */
public class BodyDeferringAsyncHandler implements AsyncHandler<Response> {

  private final Response.ResponseBuilder responseBuilder = new Response.ResponseBuilder();

  private final CountDownLatch headersArrived = new CountDownLatch(1);

  private final OutputStream output;
  private final Semaphore semaphore = new Semaphore(1);
  private boolean responseSet;
  private volatile Response response;
  private volatile Throwable throwable;

  /**
   * Creates a new BodyDeferringAsyncHandler with the specified output stream.
   *
   * @param os the output stream where the response body will be written
   */
  public BodyDeferringAsyncHandler(final OutputStream os) {
    this.output = os;
    this.responseSet = false;
  }

  /**
   * Handles exceptions that occur during the request processing.
   * This method ensures the latch is released and the output stream is closed
   * to prevent blocking threads waiting on {@link #getResponse()}.
   *
   * @param t the exception that occurred during request processing
   */
  @Override
  public void onThrowable(Throwable t) {
    this.throwable = t;
    // Counting down to handle error cases too.
    // In "premature exceptions" cases, the onBodyPartReceived() and
    // onCompleted()
    // methods will never be invoked, leaving caller of getResponse() method
    // blocked forever.
    try {
      semaphore.acquire();
    } catch (InterruptedException e) {
      // Ignore
    } finally {
      headersArrived.countDown();
      semaphore.release();
    }

    try {
      closeOut();
    } catch (IOException e) {
      // ignore
    }
  }

  /**
   * Processes the HTTP response status line.
   * Resets and begins building the response object.
   *
   * @param responseStatus the HTTP response status received from the server
   * @return {@link State#CONTINUE} to proceed with the request processing
   * @throws Exception if an error occurs while processing the status
   */
  @Override
  public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
    responseBuilder.reset();
    responseBuilder.accumulate(responseStatus);
    return State.CONTINUE;
  }

  /**
   * Processes the HTTP response headers.
   * Accumulates headers for the response that will be available via {@link #getResponse()}.
   *
   * @param headers the HTTP response headers received from the server
   * @return {@link State#CONTINUE} to proceed with the request processing
   * @throws Exception if an error occurs while processing the headers
   */
  @Override
  public State onHeadersReceived(HttpHeaders headers) throws Exception {
    responseBuilder.accumulate(headers);
    return State.CONTINUE;
  }

  /**
   * Processes trailing HTTP headers (HTTP/2 and chunked transfer encoding).
   * Accumulates trailing headers for the final response.
   *
   * @param headers the trailing HTTP headers received from the server
   * @return {@link State#CONTINUE} to proceed with the request processing
   * @throws Exception if an error occurs while processing the trailing headers
   */
  @Override
  public State onTrailingHeadersReceived(HttpHeaders headers) throws Exception {
    responseBuilder.accumulate(headers);
    return State.CONTINUE;
  }

  /**
   * Invoked when the request is being retried.
   * This handler does not support retries due to the streaming nature of body handling.
   *
   * @throws UnsupportedOperationException always thrown as retries are not supported
   */
  @Override
  public void onRetry() {
    throw new UnsupportedOperationException(this.getClass().getSimpleName() + " cannot retry a request.");
  }

  /**
   * Processes a chunk of the response body.
   * On receiving the first body chunk, the response headers are finalized and made available
   * via {@link #getResponse()}. The body bytes are immediately written to the output stream.
   *
   * @param bodyPart the chunk of response body received from the server
   * @return {@link State#CONTINUE} to proceed with the request processing
   * @throws Exception if an error occurs while processing or writing the body part
   */
  @Override
  public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
    // body arrived, flush headers
    if (!responseSet) {
      response = responseBuilder.build();
      responseSet = true;
      headersArrived.countDown();
    }

    output.write(bodyPart.getBodyPartBytes());
    return State.CONTINUE;
  }

  /**
   * Flushes and closes the output stream.
   *
   * @throws IOException if an I/O error occurs while flushing or closing the stream
   */
  protected void closeOut() throws IOException {
    try {
      output.flush();
    } finally {
      output.close();
    }
  }

  /**
   * Completes the request processing.
   * Ensures the response is finalized, releases waiting threads, closes the output stream,
   * and returns the complete response.
   *
   * @return the complete {@link Response} object
   * @throws IOException if an error occurs while completing the request or an exception was thrown earlier
   */
  @Override
  public Response onCompleted() throws IOException {

    if (!responseSet) {
      response = responseBuilder.build();
      responseSet = true;
    }

    // Counting down to handle error cases too.
    // In "normal" cases, latch is already at 0 here
    // But in other cases, for example when because of some error
    // onBodyPartReceived() is never called, the caller
    // of getResponse() would remain blocked infinitely.
    // By contract, onCompleted() is always invoked, even in case of errors
    headersArrived.countDown();

    closeOut();

    try {
      semaphore.acquire();
      if (throwable != null) {
        throw new IOException(throwable);
      } else {
        // sending out current response
        return responseBuilder.build();
      }
    } catch (InterruptedException e) {
      return null;
    } finally {
      semaphore.release();
    }
  }

  /**
   * Retrieves the response as soon as headers are available, without waiting for the body.
   * <p>
   * Unlike {@code Future<Response>.get()}, this method blocks only until headers arrive,
   * making it useful for large transfers where you need to examine headers immediately
   * and defer body streaming to prevent unnecessary bandwidth consumption.
   * <p>
   * The returned response contains the initial response from the server (status code and headers),
   * but may be incomplete if the server sends trailing headers. The complete headers can be
   * obtained via {@code Future<Response>.get()}, but multiple invocations of this method
   * will always return the same cached, potentially incomplete response.
   * <p>
   * <b>Important:</b> The response returned by this method does not contain the response body.
   * Invoking any {@code Response.getResponseBodyXXX()} method will result in an error.
   * This method may return {@code null} in case of errors.
   *
   * @return a {@link Response} containing status and headers (but no body), or {@code null} on error
   * @throws InterruptedException if the current thread is interrupted while waiting for headers
   * @throws IOException if the handler completed with an exception
   */
  public Response getResponse() throws InterruptedException, IOException {
    // block here as long as headers arrive
    headersArrived.await();

    try {
      semaphore.acquire();
      if (throwable != null) {
        throw new IOException(throwable.getMessage(), throwable);
      } else {
        return response;
      }
    } finally {
      semaphore.release();
    }
  }

  // ==

  /**
   * A helper input stream that automatically "joins" (waits for completion of) the async
   * download and performs error checking on the request's Future.
   * <p>
   * This class wraps an input stream connected to the response body and ensures proper
   * cleanup and error handling when the stream is closed.
   */
  public static class BodyDeferringInputStream extends FilterInputStream {
    private final Future<Response> future;

    private final BodyDeferringAsyncHandler bdah;

    /**
     * Creates a new BodyDeferringInputStream.
     *
     * @param future the future representing the async request
     * @param bdah the handler managing the request
     * @param in the input stream containing the response body
     */
    public BodyDeferringInputStream(final Future<Response> future, final BodyDeferringAsyncHandler bdah, final InputStream in) {
      super(in);
      this.future = future;
      this.bdah = bdah;
    }

    /**
     * Closes the input stream and waits for the async request to complete.
     * <p>
     * This method ensures the async request finishes completely and
     * propagates any exceptions that occurred during the request.
     *
     * @throws IOException if an I/O error occurs or if the request completed with an exception
     */
    @Override
    public void close() throws IOException {
      // close
      super.close();
      // "join" async request
      try {
        getLastResponse();
      } catch (ExecutionException e) {
        throw new IOException(e.getMessage(), e.getCause());
      } catch (InterruptedException e) {
        throw new IOException(e.getMessage(), e);
      }
    }

    /**
     * Retrieves the response as soon as headers are available.
     * <p>
     * Delegates to {@link BodyDeferringAsyncHandler#getResponse()}.
     * Blocks only until headers arrive. May return {@code null} in case of errors.
     * See {@link BodyDeferringAsyncHandler#getResponse()} for details.
     *
     * @return a {@link Response} containing status and headers (but no body), or {@code null} on error
     * @throws InterruptedException if the current thread is interrupted while waiting for headers
     * @throws IOException if the handler completed with an exception
     */
    public Response getAsapResponse() throws InterruptedException, IOException {
      return bdah.getResponse();
    }

    /**
     * Retrieves the complete response after it has fully arrived.
     * <p>
     * Delegates to {@code Future<Response>#get()}. Blocks until the complete response
     * (including body) has been received.
     *
     * @return the complete {@link Response}
     * @throws ExecutionException if the request threw an exception
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    public Response getLastResponse() throws InterruptedException, ExecutionException {
      return future.get();
    }
  }
}