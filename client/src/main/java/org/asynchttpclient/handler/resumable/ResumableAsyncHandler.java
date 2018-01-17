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

  public ResumableAsyncHandler(long byteTransferred) {
    this(byteTransferred, null, null, false);
  }

  public ResumableAsyncHandler(boolean accumulateBody) {
    this(0, null, null, accumulateBody);
  }

  public ResumableAsyncHandler() {
    this(0, null, null, false);
  }

  public ResumableAsyncHandler(AsyncHandler<Response> decoratedAsyncHandler) {
    this(0, new PropertiesBasedResumableProcessor(), decoratedAsyncHandler, false);
  }

  public ResumableAsyncHandler(long byteTransferred, AsyncHandler<Response> decoratedAsyncHandler) {
    this(byteTransferred, new PropertiesBasedResumableProcessor(), decoratedAsyncHandler, false);
  }

  public ResumableAsyncHandler(ResumableProcessor resumableProcessor) {
    this(0, resumableProcessor, null, false);
  }

  public ResumableAsyncHandler(ResumableProcessor resumableProcessor, boolean accumulateBody) {
    this(0, resumableProcessor, null, accumulateBody);
  }

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

  @Override
  public void onThrowable(Throwable t) {
    if (decoratedAsyncHandler != null) {
      decoratedAsyncHandler.onThrowable(t);
    } else {
      logger.debug("", t);
    }
  }

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

  @Override
  public State onTrailingHeadersReceived(HttpHeaders headers) {
    responseBuilder.accumulate(headers);
    return State.CONTINUE;
  }

  /**
   * Invoke this API if you want to set the Range header on your {@link Request} based on the last valid bytes
   * position.
   *
   * @param request {@link Request}
   * @return a {@link Request} with the Range header properly set.
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

    RequestBuilder builder = new RequestBuilder(request);
    if (request.getHeaders().get(RANGE) == null && byteTransferred.get() != 0) {
      builder.setHeader(RANGE, "bytes=" + byteTransferred.get() + "-");
    }
    return builder.build();
  }

  /**
   * Set a {@link ResumableListener}
   *
   * @param resumableListener a {@link ResumableListener}
   * @return this
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
