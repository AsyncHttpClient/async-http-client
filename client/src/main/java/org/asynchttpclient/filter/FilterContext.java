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
 * Context object passed through the filter chain, allowing filters to inspect and modify
 * requests, handlers, and responses. This class is used by {@link RequestFilter},
 * {@link ResponseFilter}, and {@link IOExceptionFilter} to process HTTP transactions.
 *
 * <p>{@link RequestFilter} executes before the HTTP request is sent to the remote server.
 * {@link ResponseFilter} executes after the response is received but before authorization,
 * redirection, and {@link AsyncHandler} processing occurs. {@link IOExceptionFilter} executes
 * when an IOException occurs during the request.</p>
 *
 * <p>Filters can use this context to:</p>
 * <ul>
 *   <li>Inspect or modify the {@link Request}</li>
 *   <li>Wrap or replace the {@link AsyncHandler}</li>
 *   <li>Access response status and headers</li>
 *   <li>Trigger request replay by setting {@code replayRequest} to true</li>
 *   <li>Handle IOExceptions</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // In a RequestFilter - add custom header
 * public <T> FilterContext<T> filter(FilterContext<T> ctx) {
 *     Request newRequest = new RequestBuilder(ctx.getRequest())
 *         .addHeader("X-Custom-Header", "value")
 *         .build();
 *     return new FilterContext.FilterContextBuilder<>(ctx)
 *         .request(newRequest)
 *         .build();
 * }
 *
 * // In a ResponseFilter - replay on specific status
 * public <T> FilterContext<T> filter(FilterContext<T> ctx) {
 *     if (ctx.getResponseStatus().getStatusCode() == 503) {
 *         return new FilterContext.FilterContextBuilder<>(ctx)
 *             .replayRequest(true)
 *             .build();
 *     }
 *     return ctx;
 * }
 * }</pre>
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
