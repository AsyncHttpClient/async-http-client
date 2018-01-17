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
 * Simple {@link org.asynchttpclient.filter.IOExceptionFilter} that replay the current {@link org.asynchttpclient.Request} using a {@link ResumableAsyncHandler}
 */
public class ResumableIOExceptionFilter implements IOExceptionFilter {
  public <T> FilterContext<T> filter(FilterContext<T> ctx) {
    if (ctx.getIOException() != null && ctx.getAsyncHandler() instanceof ResumableAsyncHandler) {

      Request request = ResumableAsyncHandler.class.cast(ctx.getAsyncHandler()).adjustRequestRange(ctx.getRequest());

      return new FilterContext.FilterContextBuilder<>(ctx).request(request).replayRequest(true).build();
    }
    return ctx;
  }
}
