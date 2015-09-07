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

import static org.asynchttpclient.util.AsyncHttpProviderUtils.getExplicitPort;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getSchemeDefaultPort;
import static org.asynchttpclient.util.HttpUtils.WS;
import static org.asynchttpclient.util.HttpUtils.isSecure;
import static org.asynchttpclient.util.HttpUtils.isWebSocket;
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
import io.netty.util.internal.chmv8.ConcurrentHashMapV8;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.channel.SSLEngineFactory;
import org.asynchttpclient.channel.pool.ConnectionPoolPartitioning;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.netty.Callback;
import org.asynchttpclient.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.pool.ChannelPool;
import org.asynchttpclient.netty.channel.pool.ChannelPoolPartitionSelector;
import org.asynchttpclient.netty.channel.pool.DefaultChannelPool;
import org.asynchttpclient.netty.channel.pool.NoopChannelPool;
import org.asynchttpclient.netty.handler.HttpProtocol;
import org.asynchttpclient.netty.handler.Processor;
import org.asynchttpclient.netty.handler.WebSocketProtocol;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChannelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelManager.class);
    public static final String HTTP_HANDLER = "httpHandler";
    public static final String SSL_HANDLER = "sslHandler";
    public static final String HTTP_PROCESSOR = "httpProcessor";
    public static final String WS_PROCESSOR = "wsProcessor";
    public static final String DEFLATER_HANDLER = "deflater";
    public static final String INFLATER_HANDLER = "inflater";
    public static final String CHUNKED_WRITER_HANDLER = "chunkedWriter";
    public static final String WS_DECODER_HANDLER = "ws-decoder";
    public static final String WS_FRAME_AGGREGATOR = "ws-aggregator";
    public static final String WS_ENCODER_HANDLER = "ws-encoder";

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig nettyConfig;
    private final SSLEngineFactory sslEngineFactory;
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

    private Processor wsProcessor;

    public ChannelManager(final AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, Timer nettyTimer) {

        this.config = config;
        this.nettyConfig = nettyConfig;
        this.sslEngineFactory = config.getSslEngineFactory() != null? config.getSslEngineFactory() : new SSLEngineFactory.DefaultSSLEngineFactory(config);

        ChannelPool channelPool = nettyConfig.getChannelPool();
        if (channelPool == null && config.isAllowPoolingConnections()) {
            channelPool = new DefaultChannelPool(config, nettyTimer);
        } else if (channelPool == null) {
            channelPool = new NoopChannelPool();
        }
        this.channelPool = channelPool;

        tooManyConnections = buildStaticIOException(String.format("Too many connections %s", config.getMaxConnections()));
        tooManyConnectionsPerHost = buildStaticIOException(String.format("Too many connections per host %s", config.getMaxConnectionsPerHost()));
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
                                Semaphore freeChannelsForHost = freeChannelsPerHost.get(partitionKey);
                                if (freeChannelsForHost != null)
                                    freeChannelsForHost.release();
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
        allowReleaseEventLoopGroup = nettyConfig.getEventLoopGroup() == null;
        if (allowReleaseEventLoopGroup) {
            DefaultThreadFactory threadFactory = new DefaultThreadFactory(config.getNameOrDefault());
            eventLoopGroup = new NioEventLoopGroup(0, threadFactory);
        } else {
            eventLoopGroup = nettyConfig.getEventLoopGroup();
        }
        if (eventLoopGroup instanceof OioEventLoopGroup)
            throw new IllegalArgumentException("Oio is not supported");

        // allow users to specify SocketChannel class and default to NioSocketChannel
        socketChannelClass = nettyConfig.getSocketChannelClass() == null ? NioSocketChannel.class : nettyConfig.getSocketChannelClass();

        httpBootstrap = new Bootstrap().channel(socketChannelClass).group(eventLoopGroup);
        wsBootstrap = new Bootstrap().channel(socketChannelClass).group(eventLoopGroup);

        // default to PooledByteBufAllocator
        httpBootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        wsBootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

        if (config.getConnectTimeout() > 0)
            nettyConfig.addChannelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout());
        for (Entry<ChannelOption<Object>, Object> entry : nettyConfig.propertiesSet()) {
            ChannelOption<Object> key = entry.getKey();
            Object value = entry.getValue();
            httpBootstrap.option(key, value);
            wsBootstrap.option(key, value);
        }
    }

    public void configureBootstraps(NettyRequestSender requestSender, AtomicBoolean closed) {

        HttpProtocol httpProtocol = new HttpProtocol(this, config, nettyConfig, requestSender);
        final Processor httpProcessor = new Processor(config, nettyConfig, this, requestSender, httpProtocol);

        WebSocketProtocol wsProtocol = new WebSocketProtocol(this, config, nettyConfig, requestSender);
        wsProcessor = new Processor(config, nettyConfig, this, requestSender, wsProtocol);

        httpBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(HTTP_HANDLER, newHttpClientCodec())//
                        .addLast(INFLATER_HANDLER, newHttpContentDecompressor())//
                        .addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                        .addLast(HTTP_PROCESSOR, httpProcessor);

                ch.config().setOption(ChannelOption.AUTO_READ, false);

                if (nettyConfig.getHttpAdditionalPipelineInitializer() != null)
                    nettyConfig.getHttpAdditionalPipelineInitializer().initPipeline(ch.pipeline());
            }
        });

        wsBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(HTTP_HANDLER, newHttpClientCodec())//
                        .addLast(WS_PROCESSOR, wsProcessor);

                if (nettyConfig.getWsAdditionalPipelineInitializer() != null)
                    nettyConfig.getWsAdditionalPipelineInitializer().initPipeline(ch.pipeline());
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

    public final void tryToOfferChannelToPool(Channel channel, AsyncHandler<?> handler, boolean keepAlive, Object partitionKey) {
        if (channel.isActive() && keepAlive && channel.isActive()) {
            LOGGER.debug("Adding key: {} for channel {}", partitionKey, channel);
            Channels.setDiscard(channel);
            if (handler instanceof AsyncHandlerExtensions) {
                AsyncHandlerExtensions.class.cast(handler).onConnectionOffer(channel);
            }
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

    public void close() {
        channelPool.destroy();
        openChannels.close();

        for (Channel channel : openChannels) {
            Object attribute = Channels.getAttribute(channel);
            if (attribute instanceof NettyResponseFuture<?>) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
                future.cancelTimeouts();
            }
        }

        if (allowReleaseEventLoopGroup)
            eventLoopGroup.shutdownGracefully();
    }

    public void closeChannel(Channel channel) {

        LOGGER.debug("Closing Channel {} ", channel);
        removeAll(channel);
        Channels.setDiscard(channel);
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

    private SslHandler createSslHandler(String peerHost, int peerPort) throws GeneralSecurityException {
        SSLEngine sslEngine = sslEngineFactory.newSSLEngine(peerHost, peerPort);
        SslHandler sslHandler = new SslHandler(sslEngine);
        if (handshakeTimeout > 0)
            sslHandler.setHandshakeTimeoutMillis(handshakeTimeout);
        return sslHandler;
    }

    public static boolean isSslHandlerConfigured(ChannelPipeline pipeline) {
        return pipeline.get(SSL_HANDLER) != null;
    }

    public void upgradeProtocol(ChannelPipeline pipeline, String scheme, String host, int port) throws GeneralSecurityException {
        if (pipeline.get(HTTP_HANDLER) != null)
            pipeline.remove(HTTP_HANDLER);

        if (isSecure(scheme))
            if (isSslHandlerConfigured(pipeline)) {
                pipeline.addAfter(SSL_HANDLER, HTTP_HANDLER, newHttpClientCodec());
            } else {
                pipeline.addFirst(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addFirst(SSL_HANDLER, createSslHandler(host, port));
            }

        else
            pipeline.addFirst(HTTP_HANDLER, newHttpClientCodec());

        if (isWebSocket(scheme)) {
            pipeline.addAfter(HTTP_PROCESSOR, WS_PROCESSOR, wsProcessor);
            pipeline.remove(HTTP_PROCESSOR);
        }
    }

    public SslHandler addSslHandler(ChannelPipeline pipeline, Uri uri, String virtualHost) throws GeneralSecurityException {
        String peerHost;
        int peerPort;
        
        if (virtualHost != null) {
            int i = virtualHost.indexOf(':');
            if (i == -1) {
                peerHost = virtualHost;
                peerPort = getSchemeDefaultPort(uri.getScheme());
            } else {
                peerHost = virtualHost.substring(0, i);
                peerPort = Integer.valueOf(virtualHost.substring(i + 1));
            }
            
        } else {
            peerHost = uri.getHost();
            peerPort = getExplicitPort(uri);
        }

        SslHandler sslHandler = createSslHandler(peerHost, peerPort);
        pipeline.addFirst(ChannelManager.SSL_HANDLER, sslHandler);
        return sslHandler;
    }

    /**
     * Always make sure the channel who got cached support the proper protocol.
     * It could only occurs when a HttpMethod. CONNECT is used against a proxy
     * that requires upgrading from http to https.
     */
    public void verifyChannelPipeline(ChannelPipeline pipeline, Uri uri, String virtualHost) throws GeneralSecurityException {

        boolean sslHandlerConfigured = isSslHandlerConfigured(pipeline);

        if (isSecure(uri)) {
            if (!sslHandlerConfigured)
                addSslHandler(pipeline, uri, virtualHost);

        } else if (sslHandlerConfigured)
            pipeline.remove(SSL_HANDLER);
    }

    public Bootstrap getBootstrap(Uri uri, boolean useProxy) {
        return uri.getScheme().startsWith(WS) && !useProxy ? wsBootstrap : httpBootstrap;
    }

    public void upgradePipelineForWebSockets(ChannelPipeline pipeline) {
        pipeline.addAfter(HTTP_HANDLER, WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
        pipeline.remove(HTTP_HANDLER);
        pipeline.addBefore(WS_PROCESSOR, WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false, false, config.getWebSocketMaxFrameSize()));
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

    public void flushPartition(String partitionId) {
        channelPool.flushPartition(partitionId);
    } 

    public void flushPartitions(ChannelPoolPartitionSelector selector) {
        channelPool.flushPartitions(selector);
    }
}
