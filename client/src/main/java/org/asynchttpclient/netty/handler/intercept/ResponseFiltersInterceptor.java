/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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

public class ResponseFiltersInterceptor {

    private final AsyncHttpClientConfig config;
    private final NettyRequestSender requestSender;

    ResponseFiltersInterceptor(AsyncHttpClientConfig config, NettyRequestSender requestSender) {
        this.config = config;
        this.requestSender = requestSender;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean exitAfterProcessingFilters(Channel channel,
                                              NettyResponseFuture<?> future,
                                              AsyncHandler<?> handler,
                                              HttpResponseStatus status,
                                              HttpHeaders responseHeaders) {

        FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(future.getCurrentRequest()).responseStatus(status)
                .responseHeaders(responseHeaders).build();

        for (ResponseFilter asyncFilter : config.getResponseFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                // FIXME Is it worth protecting against this?
//                assertNotNull(fc, "filterContext");
            } catch (FilterException fe) {
                requestSender.abort(channel, future, fe);
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
