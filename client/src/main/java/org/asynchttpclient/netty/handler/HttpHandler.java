/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.NettyResponseStatus;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;

@Sharable
public final class HttpHandler extends AsyncHttpClientHandler {

    public HttpHandler(AsyncHttpClientConfig config, ChannelManager channelManager, NettyRequestSender requestSender) {
        super(config, channelManager, requestSender);
    }

    private void finishUpdate(final NettyResponseFuture<?> future, Channel channel, boolean expectOtherChunks) throws IOException {

        future.cancelTimeouts();

        boolean keepAlive = future.isKeepAlive();
        if (expectOtherChunks && keepAlive)
            channelManager.drainChannelAndOffer(channel, future);
        else
            channelManager.tryToOfferChannelToPool(channel, future.getAsyncHandler(), keepAlive, future.getPartitionKey());

        try {
            future.done();
        } catch (Exception t) {
            // Never propagate exception once we know we are done.
            logger.debug(t.getMessage(), t);
        }
    }

    private boolean updateBodyAndInterrupt(NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponseBodyPart bodyPart) throws Exception {
        boolean interrupt = handler.onBodyPartReceived(bodyPart) != State.CONTINUE;
        if (interrupt)
            future.setKeepAlive(false);
        return interrupt;
    }

    private void notifyHandler(Channel channel, NettyResponseFuture<?> future, HttpResponse response, AsyncHandler<?> handler, NettyResponseStatus status,
            HttpRequest httpRequest, HttpResponseHeaders responseHeaders) throws IOException, Exception {

        boolean exit = exitAfterHandlingStatus(channel, future, response, handler, status, httpRequest) || //
                exitAfterHandlingHeaders(channel, future, response, handler, responseHeaders, httpRequest) || //
                exitAfterHandlingReactiveStreams(channel, future, response, handler, httpRequest);

        if (exit)
            finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(httpRequest) || HttpHeaders.isTransferEncodingChunked(response));
    }

    private boolean exitAfterHandlingStatus(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponse response, AsyncHandler<?> handler,//
            NettyResponseStatus status,//
            HttpRequest httpRequest) throws IOException, Exception {
        return !future.isAndSetStatusReceived(true) && handler.onStatusReceived(status) != State.CONTINUE;
    }

    private boolean exitAfterHandlingHeaders(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            AsyncHandler<?> handler,//
            HttpResponseHeaders responseHeaders,//
            HttpRequest httpRequest) throws IOException, Exception {
        return !response.headers().isEmpty() && handler.onHeadersReceived(responseHeaders) != State.CONTINUE;
    }

    private boolean exitAfterHandlingReactiveStreams(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            AsyncHandler<?> handler,//
            HttpRequest httpRequest) throws IOException {
        if (handler instanceof StreamedAsyncHandler) {
            StreamedAsyncHandler<?> streamedAsyncHandler = (StreamedAsyncHandler<?>) handler;
            StreamedResponsePublisher publisher = new StreamedResponsePublisher(channel.eventLoop(), channelManager, future, channel);
            // FIXME do we really need to pass the event loop?
            // FIXME move this to ChannelManager
            channel.pipeline().addLast(channel.eventLoop(), "streamedAsyncHandler", publisher);
            Channels.setAttribute(channel, publisher);
            return streamedAsyncHandler.onStream(publisher) != State.CONTINUE;
        }
        return false;
    }

    private void handleHttpResponse(final HttpResponse response, final Channel channel, final NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {

        HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
        logger.debug("\n\nRequest {}\n\nResponse {}\n", httpRequest, response);

        future.setKeepAlive(config.getKeepAliveStrategy().keepAlive(future.getTargetRequest(), httpRequest, response));

        NettyResponseStatus status = new NettyResponseStatus(future.getUri(), config, response, channel);
        HttpResponseHeaders responseHeaders = new HttpResponseHeaders(response.headers());

        if (!interceptors.exitAfterIntercept(channel, future, handler, response, status, responseHeaders)) {
            notifyHandler(channel, future, response, handler, status, httpRequest, responseHeaders);
        }
    }

    private void handleChunk(HttpContent chunk,//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            AsyncHandler<?> handler) throws IOException, Exception {

        boolean interrupt = false;
        boolean last = chunk instanceof LastHttpContent;

        // Netty 4: the last chunk is not empty
        if (last) {
            LastHttpContent lastChunk = (LastHttpContent) chunk;
            HttpHeaders trailingHeaders = lastChunk.trailingHeaders();
            if (!trailingHeaders.isEmpty()) {
                interrupt = handler.onHeadersReceived(new HttpResponseHeaders(trailingHeaders, true)) != State.CONTINUE;
            }
        }

        ByteBuf buf = chunk.content();
        if (!interrupt && !(handler instanceof StreamedAsyncHandler) && (buf.readableBytes() > 0 || last)) {
            HttpResponseBodyPart part = config.getResponseBodyPartFactory().newResponseBodyPart(buf, last);
            interrupt = updateBodyAndInterrupt(future, handler, part);
        }

        if (interrupt || last)
            finishUpdate(future, channel, !last);
    }

    @Override
    public void handleRead(final Channel channel, final NettyResponseFuture<?> future, final Object e) throws Exception {

        // future is already done because of an exception or a timeout
        if (future.isDone()) {
            // FIXME isn't the channel already properly closed?
            channelManager.closeChannel(channel);
            return;
        }

        AsyncHandler<?> handler = future.getAsyncHandler();
        try {
            if (e instanceof HttpObject) {
                HttpObject object = (HttpObject) e;
                Throwable t = object.getDecoderResult().cause();
                if (t != null) {
                    readFailed(channel, future, t);
                    return;
                }
            }
            
            if (e instanceof HttpResponse) {
                handleHttpResponse((HttpResponse) e, channel, future, handler);

            } else if (e instanceof HttpContent) {
                handleChunk((HttpContent) e, channel, future, handler);
            }
        } catch (Exception t) {
            // e.g. an IOException when trying to open a connection and send the
            // next request
            if (hasIOExceptionFilters//
                    && t instanceof IOException//
                    && requestSender.applyIoExceptionFiltersAndReplayRequest(future, IOException.class.cast(t), channel)) {
                return;
            }

            readFailed(channel, future, t);
            throw t;
        }
    }
    
    private void readFailed(Channel channel, NettyResponseFuture<?> future, Throwable t) throws Exception {
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
    }

    @Override
    public void handleChannelInactive(NettyResponseFuture<?> future) {
    }
}
