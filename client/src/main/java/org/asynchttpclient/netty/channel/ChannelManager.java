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
package org.asynchttpclient.netty.channel;

import static org.asynchttpclient.util.MiscUtils.buildStaticIOException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.chmv8.ConcurrentHashMapV8;

import java.io.IOException;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.SslEngineFactory;
import org.asynchttpclient.channel.pool.ConnectionPoolPartitioning;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.netty.Callback;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.pool.ChannelPool;
import org.asynchttpclient.netty.channel.pool.DefaultChannelPool;
import org.asynchttpclient.netty.channel.pool.NoopChannelPool;
import org.asynchttpclient.netty.handler.AsyncHttpClientHandler;
import org.asynchttpclient.netty.handler.HttpProtocol;
import org.asynchttpclient.netty.handler.WebSocketProtocol;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.ssl.DefaultSslEngineFactory;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChannelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelManager.class);
    public static final String PINNED_ENTRY = "entry";
    public static final String HTTP_CLIENT_CODEC = "http";
    public static final String SSL_HANDLER = "ssl";
    public static final String DEFLATER_HANDLER = "deflater";
    public static final String INFLATER_HANDLER = "inflater";
    public static final String CHUNKED_WRITER_HANDLER = "chunked-writer";
    public static final String WS_DECODER_HANDLER = "ws-decoder";
    public static final String WS_FRAME_AGGREGATOR = "ws-aggregator";
    public static final String WS_ENCODER_HANDLER = "ws-encoder";
    public static final String AHC_HTTP_HANDLER = "ahc-http";
    public static final String AHC_WS_HANDLER = "ahc-ws";

    private final AsyncHttpClientConfig config;
    private final SslEngineFactory sslEngineFactory;
    private final EventLoopGroup eventLoopGroup;
    private final boolean allowReleaseEventLoopGroup;
    private final Class<? extends Channel> socketChannelClass;
    private final Bootstrap httpBootstrap;
    private final Bootstrap wsBootstrap;
    private final long handshakeTimeout;
    private final IOException tooManyConnections;
    private final IOException tooManyConnectionsPerHost;
    private final IOException poolAlreadyClosed;

    private final ChannelPool channelPool;
    private final boolean maxTotalConnectionsEnabled;
    private final Semaphore freeChannels;
    private final ChannelGroup openChannels;
    private final boolean maxConnectionsPerHostEnabled;
    private final ConcurrentHashMapV8<Object, Semaphore> freeChannelsPerHost;
    private final ConcurrentHashMapV8<Channel, Object> channelId2PartitionKey;
    private final ConcurrentHashMapV8.Fun<Object, Semaphore> semaphoreComputer;

    private AsyncHttpClientHandler wsHandler;

    public ChannelManager(final AsyncHttpClientConfig config, Timer nettyTimer) {

        this.config = config;
        try {
            this.sslEngineFactory = config.getSslEngineFactory() != null ? config.getSslEngineFactory() : new DefaultSslEngineFactory(config);
        } catch (SSLException e) {
            throw new ExceptionInInitializerError(e);
        }

        ChannelPool channelPool = config.getChannelPool();
        if (channelPool == null) {
            if (config.isKeepAlive()) {
                channelPool = new DefaultChannelPool(config, nettyTimer);
            } else {
                channelPool = NoopChannelPool.INSTANCE;
            }
        }
        this.channelPool = channelPool;

        tooManyConnections = buildStaticIOException("Too many connections " + config.getMaxConnections());
        tooManyConnectionsPerHost = buildStaticIOException("Too many connections per host " + config.getMaxConnectionsPerHost());
        poolAlreadyClosed = buildStaticIOException("Pool is already closed");
        maxTotalConnectionsEnabled = config.getMaxConnections() > 0;
        maxConnectionsPerHostEnabled = config.getMaxConnectionsPerHost() > 0;

        if (maxTotalConnectionsEnabled || maxConnectionsPerHostEnabled) {
            openChannels = new CleanupChannelGroup("asyncHttpClient") {
                @Override
                public boolean remove(Object o) {
                    boolean removed = super.remove(o);
                    if (removed) {
                        if (maxTotalConnectionsEnabled)
                            freeChannels.release();
                        if (maxConnectionsPerHostEnabled) {
                            Object partitionKey = channelId2PartitionKey.remove(Channel.class.cast(o));
                            if (partitionKey != null) {
                                Semaphore hostFreeChannels = freeChannelsPerHost.get(partitionKey);
                                if (hostFreeChannels != null)
                                    hostFreeChannels.release();
                            }
                        }
                    }
                    return removed;
                }
            };
            freeChannels = new Semaphore(config.getMaxConnections());
        } else {
            openChannels = new CleanupChannelGroup("asyncHttpClient");
            freeChannels = null;
        }

        if (maxConnectionsPerHostEnabled) {
            freeChannelsPerHost = new ConcurrentHashMapV8<>();
            channelId2PartitionKey = new ConcurrentHashMapV8<>();
            semaphoreComputer = new ConcurrentHashMapV8.Fun<Object, Semaphore>() {
                @Override
                public Semaphore apply(Object partitionKey) {
                    return new Semaphore(config.getMaxConnectionsPerHost());
                }
            };
        } else {
            freeChannelsPerHost = null;
            channelId2PartitionKey = null;
            semaphoreComputer = null;
        }

        handshakeTimeout = config.getHandshakeTimeout();

        // check if external EventLoopGroup is defined
        ThreadFactory threadFactory = config.getThreadFactory() != null ? config.getThreadFactory() : new DefaultThreadFactory(config.getThreadPoolName());
        allowReleaseEventLoopGroup = config.getEventLoopGroup() == null;
        if (allowReleaseEventLoopGroup) {
            if (config.isUseNativeTransport()) {
                eventLoopGroup = newEpollEventLoopGroup(threadFactory);
                socketChannelClass = getEpollSocketChannelClass();

            } else {
                eventLoopGroup = new NioEventLoopGroup(0, threadFactory);
                socketChannelClass = NioSocketChannel.class;
            }

        } else {
            eventLoopGroup = config.getEventLoopGroup();
            if (eventLoopGroup instanceof OioEventLoopGroup)
                throw new IllegalArgumentException("Oio is not supported");

            if (eventLoopGroup instanceof NioEventLoopGroup) {
                socketChannelClass = NioSocketChannel.class;
            } else {
                socketChannelClass = getEpollSocketChannelClass();
            }
        }

        httpBootstrap = new Bootstrap().channel(socketChannelClass).group(eventLoopGroup);
        wsBootstrap = new Bootstrap().channel(socketChannelClass).group(eventLoopGroup);

        // default to PooledByteBufAllocator
        httpBootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        wsBootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

        if (config.getConnectTimeout() > 0) {
            httpBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout());
            wsBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout());
        }
        for (Entry<ChannelOption<Object>, Object> entry : config.getChannelOptions().entrySet()) {
            ChannelOption<Object> key = entry.getKey();
            Object value = entry.getValue();
            httpBootstrap.option(key, value);
            wsBootstrap.option(key, value);
        }
    }

    private EventLoopGroup newEpollEventLoopGroup(ThreadFactory threadFactory) {
        try {
            Class<?> epollEventLoopGroupClass = Class.forName("io.netty.channel.epoll.EpollEventLoopGroup");
            return (EventLoopGroup) epollEventLoopGroupClass.getConstructor(int.class, ThreadFactory.class).newInstance(0, threadFactory);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Channel> getEpollSocketChannelClass() {
        try {
            return (Class<? extends Channel>) Class.forName("io.netty.channel.epoll.EpollSocketChannel");
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public void configureBootstraps(NettyRequestSender requestSender) {

        HttpProtocol httpProtocol = new HttpProtocol(this, config, requestSender);
        final AsyncHttpClientHandler httpHandler = new AsyncHttpClientHandler(config, this, requestSender, httpProtocol);

        WebSocketProtocol wsProtocol = new WebSocketProtocol(this, config, requestSender);
        wsHandler = new AsyncHttpClientHandler(config, this, requestSender, wsProtocol);

        final NoopHandler pinnedEntry = new NoopHandler();

        httpBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(PINNED_ENTRY, pinnedEntry)//
                        .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec())//
                        .addLast(INFLATER_HANDLER, newHttpContentDecompressor())//
                        .addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                        .addLast(AHC_HTTP_HANDLER, httpHandler);

                ch.config().setOption(ChannelOption.AUTO_READ, false);

                if (config.getHttpAdditionalChannelInitializer() != null)
                    config.getHttpAdditionalChannelInitializer().initChannel(ch);
            }
        });

        wsBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(PINNED_ENTRY, pinnedEntry)//
                        .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec())//
                        .addLast(AHC_WS_HANDLER, wsHandler);

                if (config.getWsAdditionalChannelInitializer() != null)
                    config.getWsAdditionalChannelInitializer().initChannel(ch);
            }
        });
    }

    private HttpContentDecompressor newHttpContentDecompressor() {
        if (config.isKeepEncodingHeader())
            return new HttpContentDecompressor() {
                @Override
                protected String getTargetContentEncoding(String contentEncoding) throws Exception {
                    return contentEncoding;
                }
            };
        else
            return new HttpContentDecompressor();
    }

    public final void tryToOfferChannelToPool(Channel channel, AsyncHandler<?> asyncHandler, boolean keepAlive, Object partitionKey) {
        if (channel.isActive() && keepAlive) {
            LOGGER.debug("Adding key: {} for channel {}", partitionKey, channel);
            Channels.setDiscard(channel);
            if (asyncHandler instanceof AsyncHandlerExtensions)
                AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionOffer(channel);
            channelPool.offer(channel, partitionKey);
            if (maxConnectionsPerHostEnabled)
                channelId2PartitionKey.putIfAbsent(channel, partitionKey);
        } else {
            // not offered
            closeChannel(channel);
        }
    }

    public Channel poll(Uri uri, String virtualHost, ProxyServer proxy, ConnectionPoolPartitioning connectionPoolPartitioning) {
        Object partitionKey = connectionPoolPartitioning.getPartitionKey(uri, virtualHost, proxy);
        return channelPool.poll(partitionKey);
    }

    public boolean removeAll(Channel connection) {
        return channelPool.removeAll(connection);
    }

    private boolean tryAcquireGlobal() {
        return !maxTotalConnectionsEnabled || freeChannels.tryAcquire();
    }

    private Semaphore getFreeConnectionsForHost(Object partitionKey) {
        return freeChannelsPerHost.computeIfAbsent(partitionKey, semaphoreComputer);
    }

    private boolean tryAcquirePerHost(Object partitionKey) {
        return !maxConnectionsPerHostEnabled || getFreeConnectionsForHost(partitionKey).tryAcquire();
    }

    public void preemptChannel(Object partitionKey) throws IOException {
        if (!channelPool.isOpen())
            throw poolAlreadyClosed;
        if (!tryAcquireGlobal())
            throw tooManyConnections;
        if (!tryAcquirePerHost(partitionKey)) {
            if (maxTotalConnectionsEnabled)
                freeChannels.release();

            throw tooManyConnectionsPerHost;
        }
    }

    private void doClose() {
        channelPool.destroy();
        openChannels.close();

        for (Channel channel : openChannels) {
            Object attribute = Channels.getAttribute(channel);
            if (attribute instanceof NettyResponseFuture<?>) {
                NettyResponseFuture<?> nettyFuture = (NettyResponseFuture<?>) attribute;
                nettyFuture.cancelTimeouts();
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void close() {
        if (allowReleaseEventLoopGroup) {
            io.netty.util.concurrent.Future whenEventLoopGroupClosed = eventLoopGroup.shutdownGracefully(config.getShutdownQuietPeriod(), config.getShutdownTimeout(),
                    TimeUnit.MILLISECONDS);

            whenEventLoopGroupClosed.addListener((GenericFutureListener<?>) new GenericFutureListener<io.netty.util.concurrent.Future<?>>() {
                public void operationComplete(io.netty.util.concurrent.Future<?> future) throws Exception {
                    doClose();
                };
            });
        } else
            doClose();
    }

    public void closeChannel(Channel channel) {

        LOGGER.debug("Closing Channel {} ", channel);
        Channels.setDiscard(channel);
        removeAll(channel);
        Channels.silentlyCloseChannel(channel);
        openChannels.remove(channel);
    }

    public void abortChannelPreemption(Object partitionKey) {
        if (maxTotalConnectionsEnabled)
            freeChannels.release();
        if (maxConnectionsPerHostEnabled)
            getFreeConnectionsForHost(partitionKey).release();
    }

    public void registerOpenChannel(Channel channel, Object partitionKey) {
        openChannels.add(channel);
        if (maxConnectionsPerHostEnabled) {
            channelId2PartitionKey.put(channel, partitionKey);
        }
    }

    private HttpClientCodec newHttpClientCodec() {
        return new HttpClientCodec(//
                config.getHttpClientCodecMaxInitialLineLength(),//
                config.getHttpClientCodecMaxHeaderSize(),//
                config.getHttpClientCodecMaxChunkSize(),//
                false);
    }

    private SslHandler createSslHandler(String peerHost, int peerPort) {
        SSLEngine sslEngine = sslEngineFactory.newSslEngine(config, peerHost, peerPort);
        SslHandler sslHandler = new SslHandler(sslEngine);
        if (handshakeTimeout > 0)
            sslHandler.setHandshakeTimeoutMillis(handshakeTimeout);
        return sslHandler;
    }

    public static boolean isSslHandlerConfigured(ChannelPipeline pipeline) {
        return pipeline.get(SSL_HANDLER) != null;
    }

    public void upgradeProtocol(ChannelPipeline pipeline, Uri requestUri) throws SSLException {
        if (pipeline.get(HTTP_CLIENT_CODEC) != null)
            pipeline.remove(HTTP_CLIENT_CODEC);

        if (requestUri.isSecured())
            if (isSslHandlerConfigured(pipeline)) {
                pipeline.addAfter(SSL_HANDLER, HTTP_CLIENT_CODEC, newHttpClientCodec());
            } else {
                pipeline.addAfter(PINNED_ENTRY, HTTP_CLIENT_CODEC, newHttpClientCodec());
                pipeline.addAfter(PINNED_ENTRY, SSL_HANDLER, createSslHandler(requestUri.getHost(), requestUri.getExplicitPort()));
            }

        else
            pipeline.addAfter(PINNED_ENTRY, HTTP_CLIENT_CODEC, newHttpClientCodec());

        if (requestUri.isWebSocket()) {
            pipeline.addAfter(AHC_HTTP_HANDLER, AHC_WS_HANDLER, wsHandler);
            pipeline.remove(AHC_HTTP_HANDLER);
        }
    }

    public SslHandler addSslHandler(ChannelPipeline pipeline, Uri uri, String virtualHost) {
        String peerHost;
        int peerPort;

        if (virtualHost != null) {
            int i = virtualHost.indexOf(':');
            if (i == -1) {
                peerHost = virtualHost;
                peerPort = uri.getSchemeDefaultPort();
            } else {
                peerHost = virtualHost.substring(0, i);
                peerPort = Integer.valueOf(virtualHost.substring(i + 1));
            }

        } else {
            peerHost = uri.getHost();
            peerPort = uri.getExplicitPort();
        }

        SslHandler sslHandler = createSslHandler(peerHost, peerPort);
        pipeline.addFirst(ChannelManager.SSL_HANDLER, sslHandler);
        return sslHandler;
    }

    public Bootstrap getBootstrap(Uri uri, ProxyServer proxy) {
        return uri.isWebSocket() && proxy == null ? wsBootstrap : httpBootstrap;
    }

    public void upgradePipelineForWebSockets(ChannelPipeline pipeline) {
        pipeline.addAfter(HTTP_CLIENT_CODEC, WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
        pipeline.remove(HTTP_CLIENT_CODEC);
        pipeline.addBefore(AHC_WS_HANDLER, WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false, false, config.getWebSocketMaxFrameSize()));
        pipeline.addAfter(WS_DECODER_HANDLER, WS_FRAME_AGGREGATOR, new WebSocketFrameAggregator(config.getWebSocketMaxBufferSize()));
    }

    public final Callback newDrainCallback(final NettyResponseFuture<?> future, final Channel channel, final boolean keepAlive, final Object partitionKey) {

        return new Callback(future) {
            public void call() {
                tryToOfferChannelToPool(channel, future.getAsyncHandler(), keepAlive, partitionKey);
            }
        };
    }

    public void drainChannelAndOffer(final Channel channel, final NettyResponseFuture<?> future) {
        drainChannelAndOffer(channel, future, future.isKeepAlive(), future.getPartitionKey());
    }

    public void drainChannelAndOffer(final Channel channel, final NettyResponseFuture<?> future, boolean keepAlive, Object partitionKey) {
        Channels.setAttribute(channel, newDrainCallback(future, channel, keepAlive, partitionKey));
    }

    public ChannelPool getChannelPool() {
        return channelPool;
    }
}
