/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;

import static org.asynchttpclient.util.Assertions.assertNotNull;

public class ResponseFiltersInterceptor {

  private final AsyncHttpClientConfig config;
  private final NettyRequestSender requestSender;

  ResponseFiltersInterceptor(AsyncHttpClientConfig config, NettyRequestSender requestSender) {
    this.config = config;
    this.requestSender = requestSender;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public boolean exitAfterProcessingFilters(//
                                            Channel channel,//
                                            NettyResponseFuture<?> future,//
                                            AsyncHandler<?> handler, //
                                            HttpResponseStatus status,//
                                            HttpHeaders responseHeaders) {

    FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(future.getCurrentRequest()).responseStatus(status)
            .responseHeaders(responseHeaders).build();

    for (ResponseFilter asyncFilter : config.getResponseFilters()) {
      try {
        fc = asyncFilter.filter(fc);
        // FIXME Is it worth protecting against this?
        assertNotNull("fc", "filterContext");
      } catch (FilterException efe) {
        requestSender.abort(channel, future, efe);
      }
    }

    // The handler may have been wrapped.
    future.setAsyncHandler(fc.getAsyncHandler());

    // The request has changed
    if (fc.replayRequest()) {
      requestSender.replayRequest(future, fc, channel);
      return true;
    }
    return false;
  }
}
