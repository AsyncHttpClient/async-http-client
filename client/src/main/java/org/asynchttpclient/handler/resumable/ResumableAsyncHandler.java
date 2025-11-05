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
package org.asynchttpclient.handler.resumable;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.*;
import org.asynchttpclient.Response.ResponseBuilder;
import org.asynchttpclient.handler.TransferCompletionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.RANGE;

/**
 * An {@link AsyncHandler} which support resumable download, e.g when used with an {@link ResumableIOExceptionFilter},
 * this handler can resume the download operation at the point it was before the interruption occurred. This prevent having to
 * download the entire file again. It's the responsibility of the {@link org.asynchttpclient.handler.resumable.ResumableAsyncHandler}
 * to track how many bytes has been transferred and to properly adjust the file's write position.
 * <br>
 * In case of a JVM crash/shutdown, you can create an instance of this class and pass the last valid bytes position.
 * <p>
 * Beware that it registers a shutdown hook, that will cause a ClassLoader leak when used in an appserver and only redeploying the application.
 */
public class ResumableAsyncHandler implements AsyncHandler<Response> {
  private final static Logger logger = LoggerFactory.getLogger(TransferCompletionHandler.class);
  private final static ResumableIndexThread resumeIndexThread = new ResumableIndexThread();
  private static Map<String, Long> resumableIndex;
  private final AtomicLong byteTransferred;
  private final ResumableProcessor resumableProcessor;
  private final AsyncHandler<Response> decoratedAsyncHandler;
  private final boolean accumulateBody;
  private String url;
  private ResponseBuilder responseBuilder = new ResponseBuilder();
  private ResumableListener resumableListener = new NULLResumableListener();

  private ResumableAsyncHandler(long byteTransferred, ResumableProcessor resumableProcessor,
                                AsyncHandler<Response> decoratedAsyncHandler, boolean accumulateBody) {

    this.byteTransferred = new AtomicLong(byteTransferred);

    if (resumableProcessor == null) {
      resumableProcessor = new NULLResumableHandler();
    }
    this.resumableProcessor = resumableProcessor;

    resumableIndex = resumableProcessor.load();
    resumeIndexThread.addResumableProcessor(resumableProcessor);

    this.decoratedAsyncHandler = decoratedAsyncHandler;
    this.accumulateBody = accumulateBody;
  }

  /**
   * Creates a ResumableAsyncHandler starting from the specified byte position.
   * <p>
   * Uses a NULL processor (no persistence) and does not accumulate response bytes.
   *
   * @param byteTransferred the number of bytes already transferred (for manual resume)
   */
  public ResumableAsyncHandler(long byteTransferred) {
    this(byteTransferred, null, null, false);
  }

  /**
   * Creates a ResumableAsyncHandler with optional response body accumulation.
   * <p>
   * Uses a NULL processor (no persistence) and starts from byte 0.
   *
   * @param accumulateBody {@code true} to accumulate response bytes in memory for later access
   */
  public ResumableAsyncHandler(boolean accumulateBody) {
    this(0, null, null, accumulateBody);
  }

  /**
   * Creates a ResumableAsyncHandler with default settings.
   * <p>
   * Uses a NULL processor (no persistence), starts from byte 0, and does not accumulate response bytes.
   */
  public ResumableAsyncHandler() {
    this(0, null, null, false);
  }

  /**
   * Creates a ResumableAsyncHandler that decorates another handler.
   * <p>
   * Uses a {@link PropertiesBasedResumableProcessor} for persistence, starts from byte 0,
   * and does not accumulate response bytes. The decorated handler will receive all
   * handler callbacks in addition to the resume functionality.
   *
   * @param decoratedAsyncHandler the handler to decorate with resume capability
   */
  public ResumableAsyncHandler(AsyncHandler<Response> decoratedAsyncHandler) {
    this(0, new PropertiesBasedResumableProcessor(), decoratedAsyncHandler, false);
  }

  /**
   * Creates a ResumableAsyncHandler that decorates another handler with a specified starting position.
   * <p>
   * Uses a {@link PropertiesBasedResumableProcessor} for persistence and does not accumulate response bytes.
   *
   * @param byteTransferred the number of bytes already transferred (for manual resume)
   * @param decoratedAsyncHandler the handler to decorate with resume capability
   */
  public ResumableAsyncHandler(long byteTransferred, AsyncHandler<Response> decoratedAsyncHandler) {
    this(byteTransferred, new PropertiesBasedResumableProcessor(), decoratedAsyncHandler, false);
  }

  /**
   * Creates a ResumableAsyncHandler with a custom processor.
   * <p>
   * Starts from byte 0 and does not accumulate response bytes.
   *
   * @param resumableProcessor the processor to use for persisting download state
   */
  public ResumableAsyncHandler(ResumableProcessor resumableProcessor) {
    this(0, resumableProcessor, null, false);
  }

  /**
   * Creates a ResumableAsyncHandler with a custom processor and optional response body accumulation.
   * <p>
   * Starts from byte 0.
   *
   * @param resumableProcessor the processor to use for persisting download state
   * @param accumulateBody {@code true} to accumulate response bytes in memory for later access
   */
  public ResumableAsyncHandler(ResumableProcessor resumableProcessor, boolean accumulateBody) {
    this(0, resumableProcessor, null, accumulateBody);
  }

  /**
   * Processes the HTTP response status.
   * <p>
   * Accepts status codes 200 (OK) and 206 (Partial Content). Other status codes
   * cause the request to be aborted. Delegates to the decorated handler if present.
   *
   * @param status the HTTP response status
   * @return {@link State#CONTINUE} if the status is acceptable, {@link State#ABORT} otherwise
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onStatusReceived(final HttpResponseStatus status) throws Exception {
    responseBuilder.accumulate(status);
    if (status.getStatusCode() == 200 || status.getStatusCode() == 206) {
      url = status.getUri().toUrl();
    } else {
      return AsyncHandler.State.ABORT;
    }

    if (decoratedAsyncHandler != null) {
      return decoratedAsyncHandler.onStatusReceived(status);
    }

    return AsyncHandler.State.CONTINUE;
  }

  /**
   * Handles exceptions that occur during request processing.
   * <p>
   * Delegates to the decorated handler if present, otherwise logs the exception.
   *
   * @param t the exception that occurred
   */
  @Override
  public void onThrowable(Throwable t) {
    if (decoratedAsyncHandler != null) {
      decoratedAsyncHandler.onThrowable(t);
    } else {
      logger.debug("", t);
    }
  }

  /**
   * Processes a chunk of the response body.
   * <p>
   * Optionally accumulates the bytes if configured to do so, notifies the resumable listener,
   * delegates to the decorated handler if present, and updates the transfer position in the processor.
   *
   * @param bodyPart the chunk of response body
   * @return the state returned by the decorated handler, or {@link State#CONTINUE}
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {

    if (accumulateBody) {
      responseBuilder.accumulate(bodyPart);
    }

    State state = State.CONTINUE;
    try {
      resumableListener.onBytesReceived(bodyPart.getBodyByteBuffer());
    } catch (IOException ex) {
      return AsyncHandler.State.ABORT;
    }

    if (decoratedAsyncHandler != null) {
      state = decoratedAsyncHandler.onBodyPartReceived(bodyPart);
    }

    byteTransferred.addAndGet(bodyPart.getBodyPartBytes().length);
    resumableProcessor.put(url, byteTransferred.get());

    return state;
  }

  /**
   * Completes the request processing.
   * <p>
   * Removes the URL from the processor (as download is complete), notifies the resumable listener,
   * and delegates to the decorated handler if present.
   *
   * @return the complete response
   * @throws Exception if an error occurs during completion
   */
  @Override
  public Response onCompleted() throws Exception {
    resumableProcessor.remove(url);
    resumableListener.onAllBytesReceived();

    if (decoratedAsyncHandler != null) {
      decoratedAsyncHandler.onCompleted();
    }
    // Not sure
    return responseBuilder.build();
  }

  /**
   * Processes the HTTP response headers.
   * <p>
   * Checks for invalid Content-Length values and aborts if -1 is encountered.
   * Delegates to the decorated handler if present.
   *
   * @param headers the HTTP response headers
   * @return the state returned by the decorated handler, or {@link State#CONTINUE}
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onHeadersReceived(HttpHeaders headers) throws Exception {
    responseBuilder.accumulate(headers);
    String contentLengthHeader = headers.get(CONTENT_LENGTH);
    if (contentLengthHeader != null) {
      if (Long.parseLong(contentLengthHeader) == -1L) {
        return AsyncHandler.State.ABORT;
      }
    }

    if (decoratedAsyncHandler != null) {
      return decoratedAsyncHandler.onHeadersReceived(headers);
    }
    return State.CONTINUE;
  }

  /**
   * Processes trailing HTTP headers.
   * <p>
   * Accumulates trailing headers in the response builder.
   *
   * @param headers the trailing HTTP headers
   * @return {@link State#CONTINUE} to proceed with processing
   */
  @Override
  public State onTrailingHeadersReceived(HttpHeaders headers) {
    responseBuilder.accumulate(headers);
    return State.CONTINUE;
  }

  /**
   * Adjusts the request to include a Range header for resuming the download.
   * <p>
   * This method determines the last successfully transferred byte position from the processor
   * and the resumable listener, then sets the appropriate Range header on the request to
   * resume from that position. If the request already has a Range header, it is not modified.
   * <p>
   * This method is typically called by {@link ResumableIOExceptionFilter} when retrying
   * a failed request.
   *
   * @param request the original request
   * @return a new request with the Range header set to resume from the last valid position
   */
  public Request adjustRequestRange(Request request) {

    Long ri = resumableIndex.get(request.getUrl());
    if (ri != null) {
      byteTransferred.set(ri);
    }

    // The Resumable
    if (resumableListener != null && resumableListener.length() > 0 && byteTransferred.get() != resumableListener.length()) {
      byteTransferred.set(resumableListener.length());
    }

    RequestBuilder builder = request.toBuilder();
    if (request.getHeaders().get(RANGE) == null && byteTransferred.get() != 0) {
      builder.setHeader(RANGE, "bytes=" + byteTransferred.get() + "-");
    }
    return builder.build();
  }

  /**
   * Sets the listener that will receive and process response body bytes.
   * <p>
   * The resumable listener is responsible for writing bytes to the final destination
   * (e.g., a file) and tracking the current download position. Common implementations
   * include {@link ResumableRandomAccessFileListener}.
   *
   * @param resumableListener the listener that will handle response body bytes
   * @return this handler for method chaining
   */
  public ResumableAsyncHandler setResumableListener(ResumableListener resumableListener) {
    this.resumableListener = resumableListener;
    return this;
  }

  /**
   * An interface to implement in order to manage the way the incomplete file management are handled.
   */
  public interface ResumableProcessor {

    /**
     * Associate a key with the number of bytes successfully transferred.
     *
     * @param key              a key. The recommended way is to use an url.
     * @param transferredBytes The number of bytes successfully transferred.
     */
    void put(String key, long transferredBytes);

    /**
     * Remove the key associate value.
     *
     * @param key key from which the value will be discarded
     */
    void remove(String key);

    /**
     * Save the current {@link Map} instance which contains information about the current transfer state.
     * This method *only* invoked when the JVM is shutting down.
     *
     * @param map the current transfer state
     */
    void save(Map<String, Long> map);

    /**
     * Load the {@link Map} in memory, contains information about the transferred bytes.
     *
     * @return {@link Map} current transfer state
     */
    Map<String, Long> load();

  }

  private static class ResumableIndexThread extends Thread {

    public final ConcurrentLinkedQueue<ResumableProcessor> resumableProcessors = new ConcurrentLinkedQueue<>();

    public ResumableIndexThread() {
      Runtime.getRuntime().addShutdownHook(this);
    }

    public void addResumableProcessor(ResumableProcessor p) {
      resumableProcessors.offer(p);
    }

    public void run() {
      for (ResumableProcessor p : resumableProcessors) {
        p.save(resumableIndex);
      }
    }
  }

  private static class NULLResumableHandler implements ResumableProcessor {

    public void put(String url, long transferredBytes) {
    }

    public void remove(String uri) {
    }

    public void save(Map<String, Long> map) {
    }

    public Map<String, Long> load() {
      return new HashMap<>();
    }
  }

  private static class NULLResumableListener implements ResumableListener {

    private long length = 0L;

    public void onBytesReceived(ByteBuffer byteBuffer) {
      length += byteBuffer.remaining();
    }

    public void onAllBytesReceived() {
    }

    public long length() {
      return length;
    }
  }
}
