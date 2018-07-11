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

  public BodyDeferringAsyncHandler(final OutputStream os) {
    this.output = os;
    this.responseSet = false;
  }

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

  @Override
  public State onStatusReceived(HttpResponseStatus responseStatus) {
    responseBuilder.reset();
    responseBuilder.accumulate(responseStatus);
    return State.CONTINUE;
  }

  @Override
  public State onHeadersReceived(HttpHeaders headers) {
    responseBuilder.accumulate(headers);
    return State.CONTINUE;
  }

  @Override
  public State onTrailingHeadersReceived(HttpHeaders headers) {
    responseBuilder.accumulate(headers);
    return State.CONTINUE;
  }

  @Override
  public void onRetry() {
    throw new UnsupportedOperationException(this.getClass().getSimpleName() + " cannot retry a request.");
  }

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

  protected void closeOut() throws IOException {
    try {
      output.flush();
    } finally {
      output.close();
    }
  }

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
   * This method -- unlike Future&lt;Reponse&gt;.get() -- will block only as long,
   * as headers arrive. This is useful for large transfers, to examine headers
   * ASAP, and defer body streaming to it's fine destination and prevent
   * unneeded bandwidth consumption. The response here will contain the very
   * 1st response from server, so status code and headers, but it might be
   * incomplete in case of broken servers sending trailing headers. In that
   * case, the "usual" Future&lt;Response&gt;.get() method will return complete
   * headers, but multiple invocations of getResponse() will always return the
   * 1st cached, probably incomplete one. Note: the response returned by this
   * method will contain everything <em>except</em> the response body itself,
   * so invoking any method like Response.getResponseBodyXXX() will result in
   * error! Also, please not that this method might return <code>null</code>
   * in case of some errors.
   *
   * @return a {@link Response}
   * @throws InterruptedException if the latch is interrupted
   * @throws IOException          if the handler completed with an exception
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
   * A simple helper class that is used to perform automatic "join" for async
   * download and the error checking of the Future of the request.
   */
  public static class BodyDeferringInputStream extends FilterInputStream {
    private final Future<Response> future;

    private final BodyDeferringAsyncHandler bdah;

    public BodyDeferringInputStream(final Future<Response> future, final BodyDeferringAsyncHandler bdah, final InputStream in) {
      super(in);
      this.future = future;
      this.bdah = bdah;
    }

    /**
     * Closes the input stream, and "joins" (wait for complete execution
     * together with potential exception thrown) of the async request.
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
     * Delegates to {@link BodyDeferringAsyncHandler#getResponse()}. Will
     * blocks as long as headers arrives only. Might return
     * <code>null</code>. See
     * {@link BodyDeferringAsyncHandler#getResponse()} method for details.
     *
     * @return a {@link Response}
     * @throws InterruptedException if the latch is interrupted
     * @throws IOException          if the handler completed with an exception
     */
    public Response getAsapResponse() throws InterruptedException, IOException {
      return bdah.getResponse();
    }

    /**
     * Delegates to <code>Future$lt;Response&gt;#get()</code> method. Will block
     * as long as complete response arrives.
     *
     * @return a {@link Response}
     * @throws ExecutionException   if the computation threw an exception
     * @throws InterruptedException if the current thread was interrupted
     */
    public Response getLastResponse() throws InterruptedException, ExecutionException {
      return future.get();
    }
  }
}