package org.asynchttpclient.providers.netty4;

import static org.asynchttpclient.providers.netty4.util.HttpUtil.WEBSOCKET;
import static org.asynchttpclient.providers.netty4.util.HttpUtil.isSecure;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Body;
import org.asynchttpclient.BodyGenerator;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.RandomAccessBody;
import org.asynchttpclient.Request;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.generators.InputStreamBodyGenerator;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.multipart.MultipartBody;
import org.asynchttpclient.providers.netty4.FeedableBodyGenerator.FeedListener;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.ProxyUtils;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyRequestSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);

    private final AtomicBoolean isClose;
    private final AsyncHttpClientConfig config;
    private final Channels channels;

    public NettyRequestSender(AtomicBoolean isClose, AsyncHttpClientConfig config, Channels channels) {
        this.isClose = isClose;
        this.config = config;
        this.channels = channels;
    }

    public boolean retry(Channel channel, NettyResponseFuture<?> future) {

        boolean success = false;

        if (!isClose.get()) {
            channels.removeAll(channel);

            if (future == null) {
                Object attachment = Channels.getDefaultAttribute(channel);
                if (attachment instanceof NettyResponseFuture)
                    future = (NettyResponseFuture<?>) attachment;
            }

            if (future != null && future.canBeReplayed()) {
                future.setState(NettyResponseFuture.STATE.RECONNECTED);
                future.getAndSetStatusReceived(false);

                LOGGER.debug("Trying to recover request {}\n", future.getNettyRequest());

                try {
                    execute(future.getRequest(), future);
                    success = true;

                } catch (IOException iox) {
                    future.setState(NettyResponseFuture.STATE.CLOSED);
                    future.abort(iox);
                    LOGGER.error("Remotely Closed, unable to recover", iox);
                }
            } else {
                LOGGER.debug("Unable to recover future {}\n", future);
            }
        }
        return success;
    }

    // FIXME Netty 3: only called from nextRequest, useCache, asyncConnect and
    // reclaimCache always passed as true
    public <T> void execute(final Request request, final NettyResponseFuture<T> f) throws IOException {
        doConnect(request, f.getAsyncHandler(), f, true, true, true);
    }

    private final boolean validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        return request.getMethod().equals(HttpMethod.GET.name()) && asyncHandler instanceof WebSocketUpgradeHandler;
    }

    public <T> ListenableFuture<T> doConnect(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> future, boolean useCache, boolean asyncConnect,
            boolean reclaimCache) throws IOException {

        if (isClose.get()) {
            throw new IOException("Closed");
        }

        if (request.getUrl().startsWith(WEBSOCKET) && !validateWebSocketRequest(request, asyncHandler)) {
            throw new IOException("WebSocket method must be a GET");
        }

        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
        boolean useProxy = proxyServer != null;
        URI uri;
        if (config.isUseRawUrl()) {
            uri = request.getRawURI();
        } else {
            uri = request.getURI();
        }
        Channel channel = null;

        if (useCache) {
            if (future != null && future.reuseChannel() && future.channel() != null) {
                channel = future.channel();
            } else {
                URI connectionKeyUri = useProxy ? proxyServer.getURI() : uri;
                channel = channels.lookupInCache(connectionKeyUri, request.getConnectionPoolKeyStrategy());
            }
        }

        boolean useSSl = isSecure(uri) && !useProxy;
        if (channel != null && channel.isOpen() && channel.isActive()) {
            HttpRequest nettyRequest = null;

            if (future == null) {
                nettyRequest = NettyRequests.newNettyRequest(config, request, uri, false, proxyServer);
                future = NettyResponseFutures.newNettyResponseFuture(uri, request, asyncHandler, nettyRequest, config, proxyServer);
            } else {
                nettyRequest = NettyRequests.newNettyRequest(config, request, uri, future.isConnectAllowed(), proxyServer);
                future.setNettyRequest(nettyRequest);
            }
            future.setState(NettyResponseFuture.STATE.POOLED);
            future.attachChannel(channel, false);

            LOGGER.debug("\nUsing cached Channel {}\n for request \n{}\n", channel, nettyRequest);
            Channels.setDefaultAttribute(channel, future);

            try {
                writeRequest(channel, config, future);
            } catch (Exception ex) {
                LOGGER.debug("writeRequest failure", ex);
                if (useSSl && ex.getMessage() != null && ex.getMessage().contains("SSLEngine")) {
                    LOGGER.debug("SSLEngine failure", ex);
                    future = null;
                } else {
                    try {
                        asyncHandler.onThrowable(ex);
                    } catch (Throwable t) {
                        LOGGER.warn("doConnect.writeRequest()", t);
                    }
                    IOException ioe = new IOException(ex.getMessage());
                    ioe.initCause(ex);
                    throw ioe;
                }
            }
            return future;
        }

        // Do not throw an exception when we need an extra connection for a
        // redirect.
        boolean acquiredConnection = !reclaimCache && channels.acquireConnection(asyncHandler);

        NettyConnectListener<T> cl = new NettyConnectListener.Builder<T>(config, this, request, asyncHandler, future).build(uri);
        
        boolean avoidProxy = ProxyUtils.avoidProxy(proxyServer, uri.getHost());

        if (useSSl) {
            channels.constructSSLPipeline(cl.future());
        }

        Bootstrap bootstrap = channels.getBootstrap(request.getUrl(), useSSl);

        ChannelFuture channelFuture;
        try {
            InetSocketAddress remoteAddress;
            if (request.getInetAddress() != null) {
                remoteAddress = new InetSocketAddress(request.getInetAddress(), AsyncHttpProviderUtils.getPort(uri));
            } else if (proxyServer == null || avoidProxy) {
                remoteAddress = new InetSocketAddress(AsyncHttpProviderUtils.getHost(uri), AsyncHttpProviderUtils.getPort(uri));
            } else {
                remoteAddress = new InetSocketAddress(proxyServer.getHost(), proxyServer.getPort());
            }

            if (request.getLocalAddress() != null) {
                channelFuture = bootstrap.connect(remoteAddress, new InetSocketAddress(request.getLocalAddress(), 0));
            } else {
                channelFuture = bootstrap.connect(remoteAddress);
            }

        } catch (Throwable t) {
            if (acquiredConnection) {
                channels.releaseFreeConnections();
            }
            channels.abort(cl.future(), t.getCause() == null ? t : t.getCause());
            return cl.future();
        }

        // FIXME when can we have a direct invokation???
        // boolean directInvokation = !(IN_IO_THREAD.get() &&
        // DefaultChannelFuture.isUseDeadLockChecker());
        boolean directInvokation = !Constants.IN_IO_THREAD.get();

        // FIXME what does it have to do with the presence of a file?
        if (directInvokation && !asyncConnect && request.getFile() == null) {
            int timeOut = config.getConnectionTimeoutInMs() > 0 ? config.getConnectionTimeoutInMs() : Integer.MAX_VALUE;
            if (!channelFuture.awaitUninterruptibly(timeOut, TimeUnit.MILLISECONDS)) {
                if (acquiredConnection) {
                    channels.releaseFreeConnections();
                }
                // FIXME false or true?
                channelFuture.cancel(false);
                channels.abort(cl.future(), new ConnectException(String.format("Connect operation to %s timeout %s", uri, timeOut)));
            }

            try {
                cl.operationComplete(channelFuture);
            } catch (Exception e) {
                if (acquiredConnection) {
                    channels.releaseFreeConnections();
                }
                IOException ioe = new IOException(e.getMessage());
                ioe.initCause(e);
                try {
                    asyncHandler.onThrowable(ioe);
                } catch (Throwable t) {
                    LOGGER.warn("c.operationComplete()", t);
                }
                throw ioe;
            }
        } else {
            channelFuture.addListener(cl);
        }

        // FIXME Why non cached???
        LOGGER.debug("\nNon cached request \n{}\n\nusing Channel \n{}\n", cl.future().getNettyRequest(), channelFuture.channel());

        if (!cl.future().isCancelled() || !cl.future().isDone()) {
            channels.registerChannel(channelFuture.channel());
            cl.future().attachChannel(channelFuture.channel(), false);
        }
        return cl.future();
    }
    
    protected final <T> void writeRequest(final Channel channel, final AsyncHttpClientConfig config, final NettyResponseFuture<T> future) {
        try {
            // If the channel is dead because it was pooled and the remote
            // server decided to close it, we just let it go and the
            // closeChannel do it's work.
            if (!channel.isOpen() || !channel.isActive()) {
                return;
            }

            HttpRequest nettyRequest = future.getNettyRequest();
            Body body = null;
            if (!nettyRequest.getMethod().equals(HttpMethod.CONNECT)) {
                BodyGenerator bg = future.getRequest().getBodyGenerator();
                if (bg != null) {
                    try {
                        body = bg.createBody();
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                    long length = body.getContentLength();
                    if (length >= 0) {
                        nettyRequest.headers().set(HttpHeaders.Names.CONTENT_LENGTH, length);
                    } else {
                        nettyRequest.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
                    }
                } else if (future.getRequest().getParts() != null) {
                    String contentType = nettyRequest.headers().get(HttpHeaders.Names.CONTENT_TYPE);
                    String length = nettyRequest.headers().get(HttpHeaders.Names.CONTENT_LENGTH);
                    body = new MultipartBody(future.getRequest().getParts(), contentType, length);
                }
            }

            if (future.getAsyncHandler() instanceof TransferCompletionHandler) {

                FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
                for (Map.Entry<String, String> entries : future.getNettyRequest().headers()) {
                    h.add(entries.getKey(), entries.getValue());
                }

                ByteBuf content = nettyRequest instanceof FullHttpRequest ? FullHttpRequest.class.cast(nettyRequest).content() : Unpooled.buffer(0);
                TransferCompletionHandler.class.cast(future.getAsyncHandler()).transferAdapter(new NettyTransferAdapter(h, content, future.getRequest().getFile()));
            }

            // Leave it to true.
            // FIXME Yeah... explain why instead of saying the same thing as the
            // code
            if (future.getAndSetWriteHeaders(true)) {
                try {
                    channel.writeAndFlush(nettyRequest, channel.newProgressivePromise()).addListener(new ProgressListener(config, true, future.getAsyncHandler(), future));
                } catch (Throwable cause) {
                    // FIXME why not notify?
                    LOGGER.debug(cause.getMessage(), cause);
                    try {
                        channel.close();
                    } catch (RuntimeException ex) {
                        LOGGER.debug(ex.getMessage(), ex);
                    }
                    return;
                }
            }

            if (future.getAndSetWriteBody(true)) {
                if (!future.getNettyRequest().getMethod().equals(HttpMethod.CONNECT)) {

                    if (future.getRequest().getFile() != null) {
                        final File file = future.getRequest().getFile();
                        long fileLength = 0;
                        final RandomAccessFile raf = new RandomAccessFile(file, "r");

                        try {
                            fileLength = raf.length();

                            ChannelFuture writeFuture;
                            if (Channels.getSslHandler(channel) != null) {
                                writeFuture = channel.write(new ChunkedFile(raf, 0, fileLength, Constants.MAX_BUFFERED_BYTES), channel.newProgressivePromise());
                            } else {
                                FileRegion region = new OptimizedFileRegion(raf, 0, fileLength);
                                writeFuture = channel.write(region, channel.newProgressivePromise());
                            }
                            writeFuture.addListener(new ProgressListener(config, false, future.getAsyncHandler(), future) {
                                public void operationComplete(ChannelProgressiveFuture cf) {
                                    try {
                                        raf.close();
                                    } catch (IOException e) {
                                        LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                                    }
                                    super.operationComplete(cf);
                                }
                            });
                            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                        } catch (IOException ex) {
                            if (raf != null) {
                                try {
                                    raf.close();
                                } catch (IOException e) {
                                }
                            }
                            throw ex;
                        }
                    } else if (future.getRequest().getStreamData() != null || future.getRequest().getBodyGenerator() instanceof InputStreamBodyGenerator) {
                        final InputStream is = future.getRequest().getStreamData() != null ? future.getRequest().getStreamData() : InputStreamBodyGenerator.class.cast(
                                future.getRequest().getBodyGenerator()).getInputStream();

                        if (future.getAndSetStreamWasAlreadyConsumed()) {
                            if (is.markSupported())
                                is.reset();
                            else {
                                LOGGER.warn("Stream has already been consumed and cannot be reset");
                                return;
                            }
                        }

                        channel.write(new ChunkedStream(is), channel.newProgressivePromise()).addListener(new ProgressListener(config, false, future.getAsyncHandler(), future) {
                            public void operationComplete(ChannelProgressiveFuture cf) {
                                try {
                                    is.close();
                                } catch (IOException e) {
                                    LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                                }
                                super.operationComplete(cf);
                            }
                        });
                        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

                    } else if (body != null) {

                        Object msg;
                        if (Channels.getSslHandler(channel) == null && body instanceof RandomAccessBody) {
                            msg = new BodyFileRegion((RandomAccessBody) body);
                        } else {
                            BodyGenerator bg = future.getRequest().getBodyGenerator();
                            msg = new BodyChunkedInput(body);
                            if (bg instanceof FeedableBodyGenerator) {
                                FeedableBodyGenerator.class.cast(bg).setListener(new FeedListener() {
                                    @Override
                                    public void onContentAdded() {
                                        channel.pipeline().get(ChunkedWriteHandler.class).resumeTransfer();
                                    }
                                });
                            }
                        }
                        ChannelFuture writeFuture = channel.write(msg, channel.newProgressivePromise());

                        final Body b = body;
                        writeFuture.addListener(new ProgressListener(config, false, future.getAsyncHandler(), future) {
                            public void operationComplete(ChannelProgressiveFuture cf) {
                                try {
                                    b.close();
                                } catch (IOException e) {
                                    LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                                }
                                super.operationComplete(cf);
                            }
                        });
                        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                    }
                }
            }
        } catch (Throwable ioe) {
            try {
                channel.close();
            } catch (RuntimeException ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }

        try {
            future.touch();
            int requestTimeout = AsyncHttpProviderUtils.requestTimeout(config, future.getRequest());
            int schedulePeriod = requestTimeout != -1 ? (config.getIdleConnectionTimeoutInMs() != -1 ? Math.min(requestTimeout, config.getIdleConnectionTimeoutInMs())
                    : requestTimeout) : config.getIdleConnectionTimeoutInMs();

            if (schedulePeriod != -1 && !future.isDone() && !future.isCancelled()) {
                ReaperFuture reaperFuture = new ReaperFuture(future, config, isClose, channels);
                Future<?> scheduledFuture = config.reaper().scheduleAtFixedRate(reaperFuture, 0, schedulePeriod, TimeUnit.MILLISECONDS);
                reaperFuture.setScheduledFuture(scheduledFuture);
                future.setReaperFuture(reaperFuture);
            }
        } catch (RejectedExecutionException ex) {
            channels.abort(future, ex);
        }
    }
    
    // FIXME Clean up Netty 3: replayRequest's response parameter is unused +
    // WTF return???
    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, ChannelHandlerContext ctx) throws IOException {
        Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        channels.drainChannel(ctx, future);
        execute(newRequest, future);
    }
}
