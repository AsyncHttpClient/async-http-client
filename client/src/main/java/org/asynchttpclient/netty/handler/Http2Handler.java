/*
 *    Copyright (c) 2014-2026 AsyncHttpClient Project. All rights reserved.
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
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.NettyResponseStatus;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Http2ConnectionState;
import org.asynchttpclient.netty.request.NettyRequestSender;

import java.io.IOException;

/**
 * HTTP/2 channel handler for stream child channels created by {@link io.netty.handler.codec.http2.Http2MultiplexHandler}.
 * <p>
 * Each HTTP/2 stream is represented as a child channel. This handler is attached to each stream child channel
 * and processes {@link Http2HeadersFrame} (response status + headers) and {@link Http2DataFrame} (response body)
 * frames directly for maximum performance — no HTTP/1.1 object conversion overhead.
 * <p>
 * Follows the same structure as {@link HttpHandler} and reuses the same interceptor chain,
 * body part factory, and lifecycle methods from {@link AsyncHttpClientHandler}.
 */
@Sharable
public final class Http2Handler extends AsyncHttpClientHandler {

    private static final HttpVersion HTTP_2 = new HttpVersion("HTTP", 2, 0, true);

    public Http2Handler(AsyncHttpClientConfig config, ChannelManager channelManager, NettyRequestSender requestSender) {
        super(config, channelManager, requestSender);
    }

    /**
     * Handles incoming frames on the HTTP/2 stream child channel.
     * Dispatches to the appropriate handler based on frame type.
     */
    @Override
    public void handleRead(final Channel channel, final NettyResponseFuture<?> future, final Object e) throws Exception {
        if (future.isDone()) {
            channelManager.closeChannel(channel);
            return;
        }

        AsyncHandler<?> handler = future.getAsyncHandler();
        try {
            if (e instanceof Http2HeadersFrame) {
                Http2HeadersFrame headersFrame = (Http2HeadersFrame) e;
                if (headersFrame.headers().status() != null) {
                    handleHttp2HeadersFrame(headersFrame, channel, future, handler);
                } else {
                    handleHttp2TrailingHeadersFrame(headersFrame, channel, future, handler);
                }
            } else if (e instanceof Http2DataFrame) {
                handleHttp2DataFrame((Http2DataFrame) e, channel, future, handler);
            } else if (e instanceof Http2ResetFrame) {
                handleHttp2ResetFrame((Http2ResetFrame) e, channel, future);
            } else if (e instanceof Http2GoAwayFrame) {
                handleHttp2GoAwayFrame((Http2GoAwayFrame) e, channel, future);
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

    /**
     * Processes an HTTP/2 HEADERS frame, which carries the response status and headers.
     * Builds a synthetic {@link HttpResponse} from the HTTP/2 pseudo-headers so the existing
     * interceptor chain can be reused without modification.
     */
    private void handleHttp2HeadersFrame(Http2HeadersFrame headersFrame, Channel channel,
                                         NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {
        Http2Headers h2Headers = headersFrame.headers();

        // Extract :status pseudo-header and convert to HTTP status
        CharSequence statusValue = h2Headers.status();
        int statusCode = statusValue != null ? Integer.parseInt(statusValue.toString()) : 200;
        HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(statusCode);

        // Build HTTP/1.1-style headers, skipping HTTP/2 pseudo-headers (start with ':')
        HttpHeaders responseHeaders = new DefaultHttpHeaders();
        h2Headers.forEach(entry -> {
            CharSequence name = entry.getKey();
            if (name.length() > 0 && name.charAt(0) != ':') {
                responseHeaders.add(name, entry.getValue());
            }
        });

        // Build a synthetic HttpResponse so the existing interceptor chain can be reused unchanged
        HttpResponse syntheticResponse = new DefaultHttpResponse(HTTP_2, nettyStatus, responseHeaders);

        // Respect user's keepAlive config; only multiplex/pool if keepAlive is enabled
        future.setKeepAlive(config.isKeepAlive());

        NettyResponseStatus status = new NettyResponseStatus(future.getUri(), syntheticResponse, channel);

        if (!interceptors.exitAfterIntercept(channel, future, handler, syntheticResponse, status, responseHeaders)) {
            boolean abort = handler.onStatusReceived(status) == State.ABORT;
            if (!abort) {
                abort = handler.onHeadersReceived(responseHeaders) == State.ABORT;
            }
            if (abort) {
                finishUpdate(future, channel, false);
                return;
            }
        }

        // If headers frame also ends the stream (no body), finish the response
        if (headersFrame.isEndStream()) {
            finishUpdate(future, channel, false);
        }
    }

    /**
     * Processes an HTTP/2 DATA frame, which carries response body bytes.
     * Passes body content directly to {@link AsyncHandler#onBodyPartReceived} using the
     * configured {@link org.asynchttpclient.ResponseBodyPartFactory} — same as HTTP/1.1.
     */
    private void handleHttp2DataFrame(Http2DataFrame dataFrame, Channel channel,
                                      NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {
        boolean last = dataFrame.isEndStream();
        ByteBuf data = dataFrame.content();

        if (data.isReadable() || last) {
            HttpResponseBodyPart bodyPart = config.getResponseBodyPartFactory().newResponseBodyPart(data, last);
            boolean abort = handler.onBodyPartReceived(bodyPart) == State.ABORT;
            if (abort || last) {
                finishUpdate(future, channel, false);
            }
        }
    }

    /**
     * Processes trailing HTTP/2 HEADERS frame (no :status pseudo-header), which carries trailer headers
     * sent after the DATA frames. Delegates to {@link AsyncHandler#onTrailingHeadersReceived}.
     */
    private void handleHttp2TrailingHeadersFrame(Http2HeadersFrame headersFrame, Channel channel,
                                                  NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {
        Http2Headers h2Headers = headersFrame.headers();

        HttpHeaders trailingHeaders = new DefaultHttpHeaders();
        h2Headers.forEach(entry -> {
            CharSequence name = entry.getKey();
            if (name.length() > 0 && name.charAt(0) != ':') {
                trailingHeaders.add(name, entry.getValue());
            }
        });

        boolean abort = false;
        if (!trailingHeaders.isEmpty()) {
            abort = handler.onTrailingHeadersReceived(trailingHeaders) == State.ABORT;
        }

        if (abort || headersFrame.isEndStream()) {
            finishUpdate(future, channel, false);
        }
    }

    /**
     * Processes an HTTP/2 RST_STREAM frame, which indicates the server aborted the stream.
     */
    private void handleHttp2ResetFrame(Http2ResetFrame resetFrame, Channel channel, NettyResponseFuture<?> future) {
        long errorCode = resetFrame.errorCode();
        readFailed(channel, future, new IOException("HTTP/2 stream reset by server, error code: " + errorCode));
    }

    /**
     * Processes an HTTP/2 GOAWAY frame, which indicates the server is shutting down the connection.
     * The parent connection is removed from the pool to prevent new streams from being created on it.
     * The current stream's future is failed so the request can be retried on a new connection.
     */
    private void handleHttp2GoAwayFrame(Http2GoAwayFrame goAwayFrame, Channel channel, NettyResponseFuture<?> future) {
        long errorCode = goAwayFrame.errorCode();
        int lastStreamId = goAwayFrame.lastStreamId();

        // Remove the parent connection from the pool so no new streams are opened on it
        Channel parentChannel = (channel instanceof Http2StreamChannel)
                ? ((Http2StreamChannel) channel).parent()
                : channel;
        channelManager.removeAll(parentChannel);

        // Mark the connection as draining
        Http2ConnectionState state = parentChannel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
        if (state != null) {
            state.setDraining(lastStreamId);
        }

        // Check if this stream's ID is within the allowed range
        if (channel instanceof Http2StreamChannel) {
            int streamId = ((Http2StreamChannel) channel).stream().id();
            if (streamId <= lastStreamId) {
                // This stream is allowed to complete — don't fail it
                return;
            }
        }

        readFailed(channel, future, new IOException("HTTP/2 connection GOAWAY received, error code: " + errorCode
                + ", lastStreamId: " + lastStreamId));
    }

    /**
     * Overrides the base {@link AsyncHttpClientHandler#finishUpdate} to correctly handle HTTP/2
     * connection pooling. HTTP/2 stream channels are single-use — after the stream completes,
     * it must be closed. The reusable resource is the parent TCP connection channel, which is
     * offered back to the pool so future requests can open new streams on the same connection.
     *
     * @param future      the completed request future
     * @param streamChannel the stream child channel (single-use, will be closed)
     * @param close       if {@code true}, close the parent connection entirely rather than pooling it
     */
    @Override
    void finishUpdate(NettyResponseFuture<?> future, Channel streamChannel, boolean close) {
        future.cancelTimeouts();

        // Stream channels are single-use in HTTP/2 — close the stream
        streamChannel.close();

        // Offer the parent connection back to the pool for connection reuse (multiplexing)
        Channel parentChannel = (streamChannel instanceof Http2StreamChannel)
                ? ((Http2StreamChannel) streamChannel).parent()
                : null;

        // Release the stream count so pending openers can proceed
        if (parentChannel != null) {
            Http2ConnectionState state = parentChannel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
            if (state != null) {
                state.releaseStream();
            }
        }

        if (!close && future.isKeepAlive() && parentChannel != null && parentChannel.isActive()) {
            Http2ConnectionState connState = parentChannel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
            if (connState != null && connState.isDraining()) {
                // Connection is draining; close parent when no more active streams
                if (connState.getActiveStreams() <= 0) {
                    channelManager.closeChannel(parentChannel);
                }
                // else: leave parent open for remaining streams to complete
            } else {
                channelManager.tryToOfferChannelToPool(parentChannel, future.getAsyncHandler(), true, future.getPartitionKey());
            }
        } else if (parentChannel != null) {
            channelManager.closeChannel(parentChannel);
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

    @Override
    public void handleException(NettyResponseFuture<?> future, Throwable error) {
    }

    @Override
    public void handleChannelInactive(NettyResponseFuture<?> future) {
    }
}
