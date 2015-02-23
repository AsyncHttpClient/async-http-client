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
package org.asynchttpclient.providers.netty4.channel;

import static org.asynchttpclient.providers.netty.commons.util.HttpUtils.WEBSOCKET;
import static org.asynchttpclient.providers.netty.commons.util.HttpUtils.isSecure;
import static org.asynchttpclient.providers.netty.commons.util.HttpUtils.isWebSocket;
import static org.asynchttpclient.util.MiscUtils.buildStaticException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.Timer;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionPoolPartitioning;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.SSLEngineFactory;
import org.asynchttpclient.providers.netty.commons.channel.pool.ChannelPoolPartitionSelector;
import org.asynchttpclient.providers.netty4.Callback;
import org.asynchttpclient.providers.netty4.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty4.channel.Channels;
import org.asynchttpclient.providers.netty4.channel.pool.ChannelPool;
import org.asynchttpclient.providers.netty4.channel.pool.DefaultChannelPool;
import org.asynchttpclient.providers.netty4.channel.pool.NoopChannelPool;
import org.asynchttpclient.providers.netty4.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty4.handler.HttpProtocol;
import org.asynchttpclient.providers.netty4.handler.Processor;
import org.asynchttpclient.providers.netty4.handler.WebSocketProtocol;
import org.asynchttpclient.providers.netty4.request.NettyRequestSender;
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
    private final Bootstrap plainBootstrap;
    private final Bootstrap secureBootstrap;
    private final Bootstrap webSocketBootstrap;
    private final Bootstrap secureWebSocketBootstrap;
    private final long handshakeTimeout;
    private final IOException tooManyConnections;
    private final IOException tooManyConnectionsPerHost;
    private final IOException poolAlreadyClosed;

    private final ChannelPool channelPool;
    private final boolean maxTotalConnectionsEnabled;
    private final Semaphore freeChannels;
    private final ChannelGroup openChannels;
    private final boolean maxConnectionsPerHostEnabled;
    private final ConcurrentHashMap<String, Semaphore> freeChannelsPerHost;
    private final ConcurrentHashMap<Channel, String> channel2KeyPool;

    private Processor wsProcessor;

    public ChannelManager(AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, Timer nettyTimer) {

        this.config = config;
        this.nettyConfig = nettyConfig;
        this.sslEngineFactory = nettyConfig.getSslEngineFactory() != null? nettyConfig.getSslEngineFactory() : new SSLEngineFactory.DefaultSSLEngineFactory(config);

        ChannelPool channelPool = nettyConfig.getChannelPool();
        if (channelPool == null && config.isAllowPoolingConnections()) {
            channelPool = new DefaultChannelPool(config, nettyTimer);
        } else if (channelPool == null) {
            channelPool = new NoopChannelPool();
        }
        this.channelPool = channelPool;

        tooManyConnections = buildStaticException(String.format("Too many connections %s", config.getMaxConnections()));
        tooManyConnectionsPerHost = buildStaticException(String.format("Too many connections per host %s", config.getMaxConnectionsPerHost()));
        poolAlreadyClosed = buildStaticException("Pool is already closed");
        maxTotalConnectionsEnabled = config.getMaxConnections() > 0;
        maxConnectionsPerHostEnabled = config.getMaxConnectionsPerHost() > 0;

        if (maxTotalConnectionsEnabled) {
            openChannels = new CleanupChannelGroup("asyncHttpClient") {
                @Override
                public boolean remove(Object o) {
                    boolean removed = super.remove(o);
                    if (removed) {
                        freeChannels.release();
                        if (maxConnectionsPerHostEnabled) {
                            String poolKey = channel2KeyPool.remove(Channel.class.cast(o));
                            if (poolKey != null) {
                                Semaphore freeChannelsForHost = freeChannelsPerHost.get(poolKey);
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
            freeChannelsPerHost = new ConcurrentHashMap<>();
            channel2KeyPool = new ConcurrentHashMap<>();
        } else {
            freeChannelsPerHost = null;
            channel2KeyPool = null;
        }

        handshakeTimeout = nettyConfig.getHandshakeTimeout();

        // check if external EventLoopGroup is defined
        allowReleaseEventLoopGroup = nettyConfig.getEventLoopGroup() == null;
        eventLoopGroup = allowReleaseEventLoopGroup ? new NioEventLoopGroup() : nettyConfig.getEventLoopGroup();
        if (!(eventLoopGroup instanceof NioEventLoopGroup))
            throw new IllegalArgumentException("Only Nio is supported");

        plainBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(eventLoopGroup);
        secureBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(eventLoopGroup);
        webSocketBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(eventLoopGroup);
        secureWebSocketBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(eventLoopGroup);

        if (config.getConnectTimeout() > 0)
            nettyConfig.addChannelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout());
        for (Entry<ChannelOption<Object>, Object> entry : nettyConfig.propertiesSet()) {
            ChannelOption<Object> key = entry.getKey();
            Object value = entry.getValue();
            plainBootstrap.option(key, value);
            webSocketBootstrap.option(key, value);
            secureBootstrap.option(key, value);
            secureWebSocketBootstrap.option(key, value);
        }
    }

    public void configureBootstraps(NettyRequestSender requestSender, AtomicBoolean closed) {

        HttpProtocol httpProtocol = new HttpProtocol(this, config, nettyConfig, requestSender);
        final Processor httpProcessor = new Processor(config, this, requestSender, httpProtocol);

        WebSocketProtocol wsProtocol = new WebSocketProtocol(this, config, nettyConfig, requestSender);
        wsProcessor = new Processor(config, this, requestSender, wsProtocol);

        plainBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(HTTP_HANDLER, newHttpClientCodec())//
                        .addLast(INFLATER_HANDLER, newHttpContentDecompressor())//
                        .addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                        .addLast(HTTP_PROCESSOR, httpProcessor);

                if (nettyConfig.getHttpAdditionalPipelineInitializer() != null)
                    nettyConfig.getHttpAdditionalPipelineInitializer().initPipeline(ch.pipeline());
            }
        });

        webSocketBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(HTTP_HANDLER, newHttpClientCodec())//
                        .addLast(WS_PROCESSOR, wsProcessor);

                if (nettyConfig.getWsAdditionalPipelineInitializer() != null)
                    nettyConfig.getWsAdditionalPipelineInitializer().initPipeline(ch.pipeline());
            }
        });

        secureBootstrap.handler(new ChannelInitializer<Channel>() {

            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(SSL_HANDLER, new SslInitializer(ChannelManager.this))//
                        .addLast(HTTP_HANDLER, newHttpClientCodec())//
                        .addLast(INFLATER_HANDLER, newHttpContentDecompressor())//
                        .addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                        .addLast(HTTP_PROCESSOR, httpProcessor);

                if (nettyConfig.getHttpsAdditionalPipelineInitializer() != null)
                    nettyConfig.getHttpsAdditionalPipelineInitializer().initPipeline(ch.pipeline());
            }
        });

        secureWebSocketBootstrap.handler(new ChannelInitializer<Channel>() {

            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(SSL_HANDLER, new SslInitializer(ChannelManager.this))//
                        .addLast(HTTP_HANDLER, newHttpClientCodec())//
                        .addLast(WS_PROCESSOR, wsProcessor);

                if (nettyConfig.getWssAdditionalPipelineInitializer() != null)
                    nettyConfig.getWssAdditionalPipelineInitializer().initPipeline(ch.pipeline());
            }
        });
    }

    private HttpContentDecompressor newHttpContentDecompressor() {
        if (nettyConfig.isKeepEncodingHeader())
            return new HttpContentDecompressor() {
                @Override
                protected String getTargetContentEncoding(String contentEncoding) throws Exception {
                    return contentEncoding;
                }
            };
        else
            return new HttpContentDecompressor();
    }

    public final void tryToOfferChannelToPool(Channel channel, boolean keepAlive, String partitionId) {
        if (channel.isActive() && keepAlive && channel.isActive()) {
            LOGGER.debug("Adding key: {} for channel {}", partitionId, channel);
            channelPool.offer(channel, partitionId);
            if (maxConnectionsPerHostEnabled)
                channel2KeyPool.putIfAbsent(channel, partitionId);
            Channels.setDiscard(channel);
        } else {
            // not offered
            closeChannel(channel);
        }
    }

    public Channel poll(Uri uri, ProxyServer proxy, ConnectionPoolPartitioning connectionPoolPartitioning) {
        String partitionId = connectionPoolPartitioning.getPartitionId(uri, proxy);
        return channelPool.poll(partitionId);
    }

    public boolean removeAll(Channel connection) {
        return channelPool.removeAll(connection);
    }

    private boolean tryAcquireGlobal() {
        return !maxTotalConnectionsEnabled || freeChannels.tryAcquire();
    }

    private Semaphore getFreeConnectionsForHost(String poolKey) {
        Semaphore freeConnections = freeChannelsPerHost.get(poolKey);
        if (freeConnections == null) {
            // lazy create the semaphore
            Semaphore newFreeConnections = new Semaphore(config.getMaxConnectionsPerHost());
            freeConnections = freeChannelsPerHost.putIfAbsent(poolKey, newFreeConnections);
            if (freeConnections == null)
                freeConnections = newFreeConnections;
        }
        return freeConnections;
    }

    private boolean tryAcquirePerHost(String poolKey) {
        return !maxConnectionsPerHostEnabled || getFreeConnectionsForHost(poolKey).tryAcquire();
    }

    public void preemptChannel(String poolKey) throws IOException {
        if (!channelPool.isOpen())
            throw poolAlreadyClosed;
        if (!tryAcquireGlobal())
            throw tooManyConnections;
        if (!tryAcquirePerHost(poolKey)) {
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

    public void abortChannelPreemption(String poolKey) {
        if (maxTotalConnectionsEnabled)
            freeChannels.release();
        if (maxConnectionsPerHostEnabled)
            getFreeConnectionsForHost(poolKey).release();
    }

    public void registerOpenChannel(Channel channel) {
        openChannels.add(channel);
    }

    private HttpClientCodec newHttpClientCodec() {
        return new HttpClientCodec(//
                nettyConfig.getHttpClientCodecMaxInitialLineLength(),//
                nettyConfig.getHttpClientCodecMaxHeaderSize(),//
                nettyConfig.getHttpClientCodecMaxChunkSize(),//
                false);
    }

    public SslHandler createSslHandler(String peerHost, int peerPort) throws IOException, GeneralSecurityException {
        SSLEngine sslEngine = sslEngineFactory.newSSLEngine(peerHost, peerPort);
        SslHandler sslHandler = new SslHandler(sslEngine);
        if (handshakeTimeout > 0)
            sslHandler.setHandshakeTimeoutMillis(handshakeTimeout);
        return sslHandler;
    }

    public static SslHandler getSslHandler(ChannelPipeline pipeline) {
        return (SslHandler) pipeline.get(SSL_HANDLER);
    }

    public static boolean isSslHandlerConfigured(ChannelPipeline pipeline) {
        return pipeline.get(SSL_HANDLER) != null;
    }

    public void upgradeProtocol(ChannelPipeline pipeline, String scheme, String host, int port) throws IOException, GeneralSecurityException {
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

    /**
     * Always make sure the channel who got cached support the proper protocol.
     * It could only occurs when a HttpMethod. CONNECT is used against a proxy
     * that requires upgrading from http to https.
     */
    public void verifyChannelPipeline(ChannelPipeline pipeline, String scheme) throws IOException, GeneralSecurityException {

        boolean sslHandlerConfigured = isSslHandlerConfigured(pipeline);

        if (isSecure(scheme)) {
            if (!sslHandlerConfigured)
                pipeline.addFirst(SSL_HANDLER, new SslInitializer(this));

        } else if (sslHandlerConfigured)
            pipeline.remove(SSL_HANDLER);
    }

    public Bootstrap getBootstrap(Uri uri, boolean useProxy, boolean useSSl) {
        return uri.getScheme().startsWith(WEBSOCKET) && !useProxy ? (useSSl ? secureWebSocketBootstrap : webSocketBootstrap) : //
                (useSSl ? secureBootstrap : plainBootstrap);
    }

    public void upgradePipelineForWebSockets(ChannelPipeline pipeline) {
        pipeline.addAfter(HTTP_HANDLER, WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
        pipeline.remove(HTTP_HANDLER);
        pipeline.addBefore(WS_PROCESSOR, WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false, false, nettyConfig.getWebSocketMaxFrameSize()));
        pipeline.addAfter(WS_DECODER_HANDLER, WS_FRAME_AGGREGATOR, new WebSocketFrameAggregator(nettyConfig.getWebSocketMaxBufferSize()));
    }

    public final Callback newDrainCallback(final NettyResponseFuture<?> future, final Channel channel, final boolean keepAlive, final String poolKey) {

        return new Callback(future) {
            public void call() {
                tryToOfferChannelToPool(channel, keepAlive, poolKey);
            }
        };
    }

    public void drainChannelAndOffer(final Channel channel, final NettyResponseFuture<?> future) {
        drainChannelAndOffer(channel, future, future.isKeepAlive(), future.getPartitionId());
    }

    public void drainChannelAndOffer(final Channel channel, final NettyResponseFuture<?> future, boolean keepAlive, String poolKey) {
        Channels.setAttribute(channel, newDrainCallback(future, channel, keepAlive, poolKey));
    }

    public void flushPartition(String partitionId) {
        channelPool.flushPartition(partitionId);
    } 

    public void flushPartitions(ChannelPoolPartitionSelector selector) {
        channelPool.flushPartitions(selector);
    }
}
