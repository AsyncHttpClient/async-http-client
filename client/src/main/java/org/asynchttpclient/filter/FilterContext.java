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
package org.asynchttpclient.filter;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Request;

import java.io.IOException;

/**
 * A {@link FilterContext} can be used to decorate {@link Request} and {@link AsyncHandler} from a list of {@link RequestFilter}.
 * {@link RequestFilter} gets executed before the HTTP request is made to the remote server. Once the response bytes are
 * received, a {@link FilterContext} is then passed to the list of {@link ResponseFilter}. {@link ResponseFilter}
 * gets invoked before the response gets processed, e.g. before authorization, redirection and invocation of {@link AsyncHandler}
 * gets processed.
 * <br>
 * Invoking {@link FilterContext#getResponseStatus()} returns an instance of {@link HttpResponseStatus}
 * that can be used to decide if the response processing should continue or not. You can stop the current response processing
 * and replay the request but creating a {@link FilterContext}. The {@link org.asynchttpclient.AsyncHttpClient}
 * will interrupt the processing and "replay" the associated {@link Request} instance.
 *
 * @param <T> the handler result type
 */
public class FilterContext<T> {

  private final FilterContextBuilder<T> b;

  /**
   * Create a new {@link FilterContext}
   *
   * @param b a {@link FilterContextBuilder}
   */
  private FilterContext(FilterContextBuilder<T> b) {
    this.b = b;
  }

  /**
   * @return the original or decorated {@link AsyncHandler}
   */
  public AsyncHandler<T> getAsyncHandler() {
    return b.asyncHandler;
  }

  /**
   * @return the original or decorated {@link Request}
   */
  public Request getRequest() {
    return b.request;
  }

  /**
   * @return the unprocessed response's {@link HttpResponseStatus}
   */
  public HttpResponseStatus getResponseStatus() {
    return b.responseStatus;
  }

  /**
   * @return the response {@link HttpHeaders}
   */
  public HttpHeaders getResponseHeaders() {
    return b.headers;
  }

  /**
   * @return true if the current response's processing needs to be interrupted and a new {@link Request} be executed.
   */
  public boolean replayRequest() {
    return b.replayRequest;
  }

  /**
   * @return the {@link IOException}
   */
  public IOException getIOException() {
    return b.ioException;
  }

  public static class FilterContextBuilder<T> {
    private AsyncHandler<T> asyncHandler = null;
    private Request request = null;
    private HttpResponseStatus responseStatus = null;
    private boolean replayRequest = false;
    private IOException ioException = null;
    private HttpHeaders headers;

    public FilterContextBuilder() {
    }

    public FilterContextBuilder(FilterContext<T> clone) {
      asyncHandler = clone.getAsyncHandler();
      request = clone.getRequest();
      responseStatus = clone.getResponseStatus();
      replayRequest = clone.replayRequest();
      ioException = clone.getIOException();
    }

    public AsyncHandler<T> getAsyncHandler() {
      return asyncHandler;
    }

    public FilterContextBuilder<T> asyncHandler(AsyncHandler<T> asyncHandler) {
      this.asyncHandler = asyncHandler;
      return this;
    }

    public Request getRequest() {
      return request;
    }

    public FilterContextBuilder<T> request(Request request) {
      this.request = request;
      return this;
    }

    public FilterContextBuilder<T> responseStatus(HttpResponseStatus responseStatus) {
      this.responseStatus = responseStatus;
      return this;
    }

    public FilterContextBuilder<T> responseHeaders(HttpHeaders headers) {
      this.headers = headers;
      return this;
    }

    public FilterContextBuilder<T> replayRequest(boolean replayRequest) {
      this.replayRequest = replayRequest;
      return this;
    }

    public FilterContextBuilder<T> ioException(IOException ioException) {
      this.ioException = ioException;
      return this;
    }

    public FilterContext<T> build() {
      return new FilterContext<>(this);
    }
  }

}
