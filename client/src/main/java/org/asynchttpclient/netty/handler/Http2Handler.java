/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.netty.Http2ResponseStatus;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.handler.intercept.Interceptors;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.OK_200;

@Sharable
public final class Http2Handler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Http2Handler.class);

    private final AsyncHttpClientConfig config;
    private final ChannelManager channelManager;
    private final NettyRequestSender requestSender;
    private final Interceptors interceptors;
    private final boolean hasIOExceptionFilters;

    public Http2Handler(AsyncHttpClientConfig config, ChannelManager channelManager, NettyRequestSender requestSender) {
        this.config = config;
        this.channelManager = channelManager;
        this.requestSender = requestSender;
        this.interceptors = new Interceptors(config, channelManager, requestSender);
        this.hasIOExceptionFilters = !config.getIoExceptionFilters().isEmpty();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel channel = ctx.channel();
        Object attribute = Channels.getAttribute(channel);

        try {
            if (attribute instanceof NettyResponseFuture) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
                future.touch();
                handleRead(channel, future, msg);
            } else {
                logger.debug("Orphan HTTP/2 stream channel {} with attribute {} received message {}, closing", channel, attribute, msg);
                Channels.silentlyCloseChannel(channel);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void handleRead(Channel channel, NettyResponseFuture<?> future, Object msg) throws Exception {
        if (future.isDone()) {
            channelManager.closeChannel(channel);
            return;
        }

        AsyncHandler<?> handler = future.getAsyncHandler();
        try {
            if (msg instanceof Http2HeadersFrame) {
                handleHeaders((Http2HeadersFrame) msg, channel, future, handler);
            } else if (msg instanceof Http2DataFrame) {
                handleData((Http2DataFrame) msg, channel, future, handler);
            } else if (msg instanceof Http2ResetFrame) {
                handleReset((Http2ResetFrame) msg, channel, future);
            } else if (msg instanceof Http2GoAwayFrame) {
                handleGoAway((Http2GoAwayFrame) msg, channel, future);
            }
        } catch (Exception t) {
            if (hasIOExceptionFilters && t instanceof IOException
                    && requestSender.applyIoExceptionFiltersAndReplayRequest(future, (IOException) t, channel)) {
                return;
            }
            readFailed(channel, future, t);
            throw t;
        }
    }

    private void handleHeaders(Http2HeadersFrame headersFrame, Channel channel, NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {
        io.netty.handler.codec.http2.Http2Headers h2Headers = headersFrame.headers();

        if (h2Headers.status() != null) {
            // Initial response headers
            int statusCode = Integer.parseInt(h2Headers.status().toString());
            Http2ResponseStatus status = new Http2ResponseStatus(future.getUri(), statusCode, channel);

            HttpHeaders responseHeaders = new DefaultHttpHeaders();
            for (Map.Entry<CharSequence, CharSequence> entry : h2Headers) {
                String name = entry.getKey().toString();
                // Skip pseudo-headers
                if (!name.startsWith(":")) {
                    responseHeaders.add(name, entry.getValue().toString());
                }
            }

            future.setKeepAlive(true); // HTTP/2 connections are always kept alive

            HttpMethod httpMethod = HttpMethod.valueOf(future.getTargetRequest().getMethod());

            // Check interceptors (redirects, auth, etc.)
            if (interceptors.exitAfterIntercept(channel, future, handler, status, responseHeaders, statusCode)) {
                // Interceptor handled the response (e.g., redirect). Close the stream channel
                // without completing the future, as the interceptor will send a new request.
                // Detach the future first to prevent channelInactive from triggering retries.
                Channels.setAttribute(channel, null);
                Channels.silentlyCloseChannel(channel);
                return;
            }

            boolean abort = abortAfterHandlingStatus(handler, httpMethod, status)
                    || abortAfterHandlingHeaders(handler, responseHeaders);
            if (abort) {
                finishUpdate(future, channel, true);
                return;
            }

            // If this is the end of stream with headers only (no body)
            if (headersFrame.isEndStream()) {
                finishUpdate(future, channel, false);
            }
        } else {
            // Trailing headers
            HttpHeaders trailingHeaders = new DefaultHttpHeaders();
            for (Map.Entry<CharSequence, CharSequence> entry : h2Headers) {
                String name = entry.getKey().toString();
                if (!name.startsWith(":")) {
                    trailingHeaders.add(name, entry.getValue().toString());
                }
            }
            if (!trailingHeaders.isEmpty()) {
                handler.onTrailingHeadersReceived(trailingHeaders);
            }
            if (headersFrame.isEndStream()) {
                finishUpdate(future, channel, false);
            }
        }
    }

    private void handleData(Http2DataFrame dataFrame, Channel channel, NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {
        boolean abort = false;
        boolean last = dataFrame.isEndStream();

        ByteBuf content = dataFrame.content();
        if (content.isReadable() || last) {
            HttpResponseBodyPart bodyPart = config.getResponseBodyPartFactory().newResponseBodyPart(content, last);
            abort = handler.onBodyPartReceived(bodyPart) == State.ABORT;
        }

        if (abort || last) {
            finishUpdate(future, channel, abort);
        }
    }

    private void handleReset(Http2ResetFrame resetFrame, Channel channel, NettyResponseFuture<?> future) {
        long errorCode = resetFrame.errorCode();
        logger.debug("HTTP/2 stream reset with error code: {}", errorCode);
        readFailed(channel, future, new IOException("HTTP/2 stream reset, error code: " + errorCode));
    }

    private void handleGoAway(Http2GoAwayFrame goAwayFrame, Channel channel, NettyResponseFuture<?> future) {
        long errorCode = goAwayFrame.errorCode();
        logger.debug("HTTP/2 GOAWAY received with error code: {}", errorCode);
        readFailed(channel, future, new IOException("HTTP/2 GOAWAY received, error code: " + errorCode));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (requestSender.isClosed()) {
            return;
        }

        Channel channel = ctx.channel();
        Object attribute = Channels.getAttribute(channel);
        logger.debug("HTTP/2 stream channel closed: {} with attribute {}", channel, attribute);

        if (attribute instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
            future.touch();
            requestSender.handleUnexpectedClosedChannel(channel, future);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel channel = ctx.channel();
        logger.debug("HTTP/2 stream exception on channel {}", channel, cause);

        Object attribute = Channels.getAttribute(channel);
        if (attribute instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
            future.attachChannel(null, false);
            future.touch();
            requestSender.abort(channel, future, cause);
        }

        Channels.silentlyCloseChannel(channel);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.read();
    }

    private static boolean abortAfterHandlingStatus(AsyncHandler<?> handler, HttpMethod httpMethod, Http2ResponseStatus status) throws Exception {
        return handler.onStatusReceived(status) == State.ABORT
                || httpMethod == HttpMethod.CONNECT && status.getStatusCode() != OK_200;
    }

    private static boolean abortAfterHandlingHeaders(AsyncHandler<?> handler, HttpHeaders responseHeaders) throws Exception {
        return !responseHeaders.isEmpty() && handler.onHeadersReceived(responseHeaders) == State.ABORT;
    }

    private void finishUpdate(NettyResponseFuture<?> future, Channel channel, boolean close) {
        future.cancelTimeouts();

        if (close) {
            channelManager.closeChannel(channel);
        } else {
            // For HTTP/2 stream channels, just close the stream, not the connection
            Channels.silentlyCloseChannel(channel);
        }

        try {
            future.done();
        } catch (Exception t) {
            logger.debug(t.getMessage(), t);
        }
    }

    private void readFailed(Channel channel, NettyResponseFuture<?> future, Throwable t) {
        try {
            requestSender.abort(channel, future, t);
        } catch (Exception abortException) {
            logger.debug("Abort failed", abortException);
        } finally {
            finishUpdate(future, channel, true);
        }
    }
}
