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

import org.asynchttpclient.Request;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;

/**
 * An {@link IOExceptionFilter} that enables automatic retry with resume capability for failed downloads.
 * <p>
 * This filter detects I/O exceptions during request processing and, if the handler is a
 * {@link ResumableAsyncHandler}, automatically adjusts the request to resume from where it failed
 * by setting the appropriate Range header. This allows downloads to continue from the last
 * successfully received byte rather than starting over.
 * <p>
 * The filter works in conjunction with {@link ResumableAsyncHandler} to implement resumable
 * downloads that can recover from network interruptions, timeouts, or other I/O errors.
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = new DefaultAsyncHttpClient(
 *     new DefaultAsyncHttpClientConfig.Builder()
 *         .addIOExceptionFilter(new ResumableIOExceptionFilter())
 *         .build()
 * );
 *
 * ResumableAsyncHandler handler = new ResumableAsyncHandler();
 * handler.setResumableListener(new ResumableRandomAccessFileListener(outputFile));
 *
 * // The filter will automatically retry with Range header if I/O exceptions occur
 * client.prepareGet("http://example.com/largefile.zip")
 *     .execute(handler)
 *     .get();
 * }</pre>
 */
public class ResumableIOExceptionFilter implements IOExceptionFilter {

  /**
   * Filters I/O exceptions and enables request replay with resume capability.
   * <p>
   * If an I/O exception occurs and the handler is a {@link ResumableAsyncHandler},
   * this method adjusts the request to include a Range header starting from the
   * last successfully transferred byte and marks the request for replay.
   * <p>
   * For other handlers or if no exception occurred, the context is returned unchanged.
   *
   * @param ctx the filter context containing the exception, request, and handler
   * @param <T> the response type
   * @return a new filter context with an adjusted request if resumption is possible,
   *         or the original context otherwise
   */
  public <T> FilterContext<T> filter(FilterContext<T> ctx) {
    if (ctx.getIOException() != null && ctx.getAsyncHandler() instanceof ResumableAsyncHandler) {

      Request request = ResumableAsyncHandler.class.cast(ctx.getAsyncHandler()).adjustRequestRange(ctx.getRequest());

      return new FilterContext.FilterContextBuilder<>(ctx).request(request).replayRequest(true).build();
    }
    return ctx;
  }
}
