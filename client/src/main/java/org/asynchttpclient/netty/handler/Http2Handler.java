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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeadersFactory;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2DataFrame;
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
import org.asynchttpclient.netty.channel.Channels;
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
    private static final HttpHeadersFactory RESPONSE_HEADERS_FACTORY =
            DefaultHttpHeadersFactory.headersFactory().withNameValidation(false);

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
            }
            // RST_STREAM is delivered as a user event (see userEventTriggered), never via channelRead.
            // GOAWAY is a connection-level frame handled on the PARENT pipeline (ChannelManager's
            // http2-goaway-listener); Http2MultiplexHandler closes any streams above lastStreamId itself,
            // surfacing here as channelInactive. Neither is dispatched to this child handleRead.
        } catch (Exception t) {
            if (hasIOExceptionFilters && t instanceof IOException
                    && requestSender.applyIoExceptionFiltersAndReplayRequest(future, (IOException) t, channel)) {
                return;
            }
            // Stream-scoped failure (RFC 7540 §5.4.2): a processing error on ONE stream — a malformed frame,
            // or far more commonly a user AsyncHandler callback (onStatusReceived/onHeadersReceived/
            // onBodyPartReceived/onTrailingHeadersReceived) that throws — must fail only this stream, not
            // close the parent TCP connection and take down every sibling multiplexed request. streamFailed
            // routes through finishUpdate(close=false), closing only the single-use stream child channel.
            // Do NOT re-throw: that would reach exceptionCaught and close the channel a second time.
            streamFailed(channel, future, t);
        }
    }

    /**
     * Netty's {@link io.netty.handler.codec.http2.Http2MultiplexHandler} delivers RST_STREAM to the
     * stream child channel as a <em>user event</em> ({@link Http2ResetFrame} is an
     * {@link io.netty.handler.codec.http2.Http2StreamFrame}), NOT via {@code channelRead}. Without this
     * override {@link #handleHttp2ResetFrame} never runs and the stream is failed only later by the
     * generic {@code channelInactive}, discarding the server's RST error code. Handle it here so the
     * stream fails promptly carrying that code, while staying stream-scoped (RFC 7540 §6.4): the parent
     * connection and its sibling multiplexed streams are left untouched.
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof Http2ResetFrame) {
            Channel channel = ctx.channel();
            Object attribute = Channels.getAttribute(channel);
            if (attribute instanceof NettyResponseFuture) {
                handleHttp2ResetFrame((Http2ResetFrame) evt, channel, (NettyResponseFuture<?>) attribute);
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * Processes an HTTP/2 HEADERS frame, which carries the response status and headers.
     * Builds a synthetic {@link HttpResponse} from the HTTP/2 pseudo-headers so the existing
     * interceptor chain can be reused without modification.
     */
    private void handleHttp2HeadersFrame(Http2HeadersFrame headersFrame, Channel channel,
                                         NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {
        Http2Headers h2Headers = headersFrame.headers();

        // Extract :status pseudo-header and convert to HTTP status. Netty's header validation normally
        // rejects a malformed :status upstream, but guard the parse so a bad value fails just this stream
        // (via handleRead's catch) instead of throwing an unwrapped NumberFormatException.
        CharSequence statusValue = h2Headers.status();
        int statusCode;
        try {
            statusCode = statusValue != null ? Integer.parseInt(statusValue.toString()) : 200;
        } catch (NumberFormatException nfe) {
            throw new IOException("Malformed HTTP/2 :status pseudo-header: " + statusValue, nfe);
        }
        HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(statusCode);

        HttpHeaders responseHeaders = copyHttp2Headers(h2Headers);

        // Build a synthetic HttpResponse so the existing interceptor chain can be reused unchanged
        HttpResponse syntheticResponse = new DefaultHttpResponse(HTTP_2, nettyStatus, responseHeaders);

        // Respect user's keepAlive config; only multiplex/pool if keepAlive is enabled
        future.setKeepAlive(config.isKeepAlive());

        NettyResponseStatus status = new NettyResponseStatus(future.getUri(), syntheticResponse, channel);

        // RFC 9110 §15.2: a 1xx is an INTERIM response, not the final one. 100-continue must still run the
        // interceptor chain (Continue100Interceptor resumes the deferred body), but any OTHER interim — 102
        // Processing, 103 Early Hints — must NOT touch the chain at all: running it would persist the interim's
        // Set-Cookie into the CookieStore and execute response filters against a non-final response, then do it
        // again on the real response. The interim HEADERS has endStream=false, so just wait for the final frame.
        if (statusCode > 100 && statusCode < 200) {
            return;
        }

        if (!interceptors.exitAfterIntercept(channel, future, handler, syntheticResponse, status, responseHeaders)) {
            // A 100 that the interceptor chain did not consume (no Expect/100-continue in flight) is still
            // interim and must not be delivered to the AsyncHandler as the final status — that would fire
            // onStatusReceived/onHeadersReceived a second time when the real response arrives.
            if (statusCode == 100) {
                return;
            }
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

        HttpHeaders trailingHeaders = copyHttp2Headers(h2Headers);

        boolean abort = false;
        if (!trailingHeaders.isEmpty()) {
            abort = handler.onTrailingHeadersReceived(trailingHeaders) == State.ABORT;
        }

        if (abort || headersFrame.isEndStream()) {
            finishUpdate(future, channel, false);
        }
    }

    static HttpHeaders copyHttp2Headers(Http2Headers h2Headers) {
        // The HTTP/2 decoder validates names but not values by default. Avoid repeating the name scan while
        // retaining the HTTP/1 value validator that rejects CR/LF and other prohibited characters.
        HttpHeaders headers = RESPONSE_HEADERS_FACTORY.newHeaders();
        h2Headers.forEach(entry -> {
            CharSequence name = entry.getKey();
            if (name.length() > 0 && name.charAt(0) != ':') {
                headers.add(name, entry.getValue());
            }
        });
        return headers;
    }

    /**
     * Processes an HTTP/2 RST_STREAM frame, which indicates the server aborted the stream.
     */
    private void handleHttp2ResetFrame(Http2ResetFrame resetFrame, Channel channel, NettyResponseFuture<?> future) {
        long errorCode = resetFrame.errorCode();
        // RFC 7540 §5.4.2/§6.4: RST_STREAM is stream-scoped and MUST NOT terminate the connection.
        // Fail only this stream's future and close only the (single-use) stream child channel —
        // sibling streams multiplexed on the same parent connection must be left untouched.
        streamFailed(channel, future, new IOException("HTTP/2 stream reset by server, error code: " + errorCode));
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

        // The parent HTTP/2 connection stays in the HTTP/2 registry (not the regular pool)
        // to allow concurrent multiplexed requests. We only need to release the stream count.
        Channel parentChannel = (streamChannel instanceof Http2StreamChannel)
                ? ((Http2StreamChannel) streamChannel).parent()
                : null;

        if (parentChannel != null) {
            // The stream slot is released — and a fully-drained draining connection closed — by the
            // closeFuture listener bound in NettyRequestSender.openHttp2Stream, which the
            // streamChannel.close() above triggers. Releasing here instead would leak the slot on the
            // paths where an exception completes the future before finishUpdate runs (e.g. a
            // DecompressionException routed through exceptionCaught -> streamFailed, whose isDone() guard
            // then skips finishUpdate entirely).

            // Fire onConnectionOffer to maintain event lifecycle contract
            try {
                future.getAsyncHandler().onConnectionOffer(parentChannel);
            } catch (Exception e) {
                logger.error("onConnectionOffer crashed", e);
            }
        }

        // If close was requested, close the parent connection entirely
        if (close && parentChannel != null) {
            channelManager.closeChannel(parentChannel);
        }

        try {
            future.done();
        } catch (Exception t) {
            logger.debug(t.getMessage(), t);
        }
    }

    /**
     * Fails a single stream's future WITHOUT closing the parent connection. Used for stream-scoped
     * events (RST_STREAM, and the {@code channelInactive}/exception Netty delivers when one stream
     * dies). {@link #finishUpdate} with {@code close=false} closes only the single-use stream child
     * channel and releases its stream slot, leaving the parent connection and its sibling multiplexed
     * streams untouched.
     * <p>
     * When the PARENT connection genuinely drops, Netty fires {@code channelInactive} on every child
     * stream, so each in-flight future is still failed individually and promptly.
     */
    private void streamFailed(Channel channel, NettyResponseFuture<?> future, Throwable t) {
        if (future.isDone()) {
            return;
        }
        try {
            requestSender.abort(channel, future, t);
        } catch (Exception abortException) {
            logger.debug("Abort failed", abortException);
        } finally {
            finishUpdate(future, channel, false);
        }
    }

    @Override
    public void handleException(NettyResponseFuture<?> future, Throwable error) {
        // Stream-scoped: an exception on one stream child channel must not tear down the parent
        // connection that sibling multiplexed streams share (see streamFailed).
        streamFailed(future.channel(), future, error);
    }

    @Override
    public void handleChannelInactive(NettyResponseFuture<?> future) {
        // Stream-scoped (see streamFailed): closing the parent here would fail unrelated sibling
        // streams on the same connection — the RST_STREAM/single-stream-close blast-radius bug.
        streamFailed(future.channel(), future,
                new IOException("HTTP/2 stream channel closed unexpectedly"));
    }
}
