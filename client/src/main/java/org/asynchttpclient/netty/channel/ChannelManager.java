/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.resolver.NameResolver;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ClientStats;
import org.asynchttpclient.HostStats;
import org.asynchttpclient.Realm;
import org.asynchttpclient.SslEngineFactory;
import org.asynchttpclient.channel.ChannelPool;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.channel.NoopChannelPool;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.handler.AsyncHttpClientHandler;
import org.asynchttpclient.netty.handler.HttpHandler;
import org.asynchttpclient.netty.handler.WebSocketHandler;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.ssl.DefaultSslEngineFactory;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ChannelManager {

    public static final String HTTP_CLIENT_CODEC = "http";
    public static final String SSL_HANDLER = "ssl";
    public static final String SOCKS_HANDLER = "socks";
    public static final String INFLATER_HANDLER = "inflater";
    public static final String CHUNKED_WRITER_HANDLER = "chunked-writer";
    public static final String WS_DECODER_HANDLER = "ws-decoder";
    public static final String WS_FRAME_AGGREGATOR = "ws-aggregator";
    public static final String WS_COMPRESSOR_HANDLER = "ws-compressor";
    public static final String WS_ENCODER_HANDLER = "ws-encoder";
    public static final String AHC_HTTP_HANDLER = "ahc-http";
    public static final String AHC_WS_HANDLER = "ahc-ws";
    public static final String LOGGING_HANDLER = "logging";
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelManager.class);
    private final AsyncHttpClientConfig config;
    private final SslEngineFactory sslEngineFactory;
    private final EventLoopGroup eventLoopGroup;
    private final boolean allowReleaseEventLoopGroup;
    private final Bootstrap httpBootstrap;
    private final Bootstrap wsBootstrap;
    private final long handshakeTimeout;

    private final ChannelPool channelPool;
    private final ChannelGroup openChannels;

    private AsyncHttpClientHandler wsHandler;

    private boolean isInstanceof(Object object, String name) {
        final Class<?> clazz;
        try {
            clazz = Class.forName(name, false, this.getClass().getClassLoader());
        } catch (ClassNotFoundException ignored) {
            return false;
        }
        return clazz.isInstance(object);
    }

    public ChannelManager(final AsyncHttpClientConfig config, Timer nettyTimer) {
        this.config = config;

        sslEngineFactory = config.getSslEngineFactory() != null ? config.getSslEngineFactory() : new DefaultSslEngineFactory();
        try {
            sslEngineFactory.init(config);
        } catch (SSLException e) {
            throw new RuntimeException("Could not initialize SslEngineFactory", e);
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
        openChannels = new DefaultChannelGroup("asyncHttpClient", GlobalEventExecutor.INSTANCE);
        handshakeTimeout = config.getHandshakeTimeout();

        // check if external EventLoopGroup is defined
        ThreadFactory threadFactory = config.getThreadFactory() != null ? config.getThreadFactory() : new DefaultThreadFactory(config.getThreadPoolName());
        allowReleaseEventLoopGroup = config.getEventLoopGroup() == null;
        TransportFactory<? extends Channel, ? extends EventLoopGroup> transportFactory;

        if (allowReleaseEventLoopGroup) {
            if (config.isUseNativeTransport()) {
                transportFactory = getNativeTransportFactory(config);
            } else {
                transportFactory = NioTransportFactory.INSTANCE;
            }
            eventLoopGroup = transportFactory.newEventLoopGroup(config.getIoThreadsCount(), threadFactory);
        } else {
            eventLoopGroup = config.getEventLoopGroup();

            if (eventLoopGroup instanceof NioEventLoopGroup) {
                transportFactory = NioTransportFactory.INSTANCE;
            } else if (isInstanceof(eventLoopGroup, "io.netty.channel.epoll.EpollEventLoopGroup")) {
                transportFactory = new EpollTransportFactory();
            } else if (isInstanceof(eventLoopGroup, "io.netty.channel.kqueue.KQueueEventLoopGroup")) {
                transportFactory = new KQueueTransportFactory();
            } else if (isInstanceof(eventLoopGroup, "io.netty.incubator.channel.uring.IOUringEventLoopGroup")) {
                transportFactory = new IoUringIncubatorTransportFactory();
            } else {
                throw new IllegalArgumentException("Unknown event loop group " + eventLoopGroup.getClass().getSimpleName());
            }
        }

        httpBootstrap = newBootstrap(transportFactory, eventLoopGroup, config);
        wsBootstrap = newBootstrap(transportFactory, eventLoopGroup, config);
    }

    private static TransportFactory<? extends Channel, ? extends EventLoopGroup> getNativeTransportFactory(AsyncHttpClientConfig config) {
        // If we are running on macOS then use KQueue
        if (PlatformDependent.isOsx()) {
            if (KQueueTransportFactory.isAvailable()) {
                return new KQueueTransportFactory();
            }
        }

        // If we're not running on Windows then we're probably running on Linux.
        // We will check if Io_Uring is available or not. If available, return IoUringIncubatorTransportFactory.
        // Else
        // We will check if Epoll is available or not. If available, return EpollTransportFactory.
        // If none of the condition matches then no native transport is available, and we will throw an exception.
        if (!PlatformDependent.isWindows()) {
            if (IoUringIncubatorTransportFactory.isAvailable() && !config.isUseOnlyEpollNativeTransport()) {
                return new IoUringIncubatorTransportFactory();
            } else if (EpollTransportFactory.isAvailable()) {
                return new EpollTransportFactory();
            }
        }

        throw new IllegalArgumentException("No suitable native transport (Epoll, Io_Uring or KQueue) available");
    }

    public static boolean isSslHandlerConfigured(ChannelPipeline pipeline) {
        return pipeline.get(SSL_HANDLER) != null;
    }

    private static Bootstrap newBootstrap(ChannelFactory<? extends Channel> channelFactory, EventLoopGroup eventLoopGroup, AsyncHttpClientConfig config) {
        Bootstrap bootstrap = new Bootstrap().channelFactory(channelFactory).group(eventLoopGroup)
                .option(ChannelOption.ALLOCATOR, config.getAllocator() != null ? config.getAllocator() : ByteBufAllocator.DEFAULT)
                .option(ChannelOption.TCP_NODELAY, config.isTcpNoDelay())
                .option(ChannelOption.SO_REUSEADDR, config.isSoReuseAddress())
                .option(ChannelOption.SO_KEEPALIVE, config.isSoKeepAlive())
                .option(ChannelOption.AUTO_CLOSE, false);

        long connectTimeout = config.getConnectTimeout().toMillis();
        if (connectTimeout > 0) {
            connectTimeout = Math.min(connectTimeout, Integer.MAX_VALUE);
            bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout);
        }

        if (config.getSoLinger() >= 0) {
            bootstrap.option(ChannelOption.SO_LINGER, config.getSoLinger());
        }

        if (config.getSoSndBuf() >= 0) {
            bootstrap.option(ChannelOption.SO_SNDBUF, config.getSoSndBuf());
        }

        if (config.getSoRcvBuf() >= 0) {
            bootstrap.option(ChannelOption.SO_RCVBUF, config.getSoRcvBuf());
        }

        for (Entry<ChannelOption<Object>, Object> entry : config.getChannelOptions().entrySet()) {
            bootstrap.option(entry.getKey(), entry.getValue());
        }

        return bootstrap;
    }

    public void configureBootstraps(NettyRequestSender requestSender) {
        final AsyncHttpClientHandler httpHandler = new HttpHandler(config, this, requestSender);
        wsHandler = new WebSocketHandler(config, this, requestSender);

        httpBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ChannelPipeline pipeline = ch.pipeline()
                        .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec())
                        .addLast(INFLATER_HANDLER, newHttpContentDecompressor())
                        .addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())
                        .addLast(AHC_HTTP_HANDLER, httpHandler);

                if (LOGGER.isTraceEnabled()) {
                    pipeline.addFirst(LOGGING_HANDLER, new LoggingHandler(LogLevel.TRACE));
                }

                if (config.getHttpAdditionalChannelInitializer() != null) {
                    config.getHttpAdditionalChannelInitializer().accept(ch);
                }
            }
        });

        wsBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ChannelPipeline pipeline = ch.pipeline()
                        .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec())
                        .addLast(AHC_WS_HANDLER, wsHandler);

                if (config.isEnableWebSocketCompression()) {
                    pipeline.addBefore(AHC_WS_HANDLER, WS_COMPRESSOR_HANDLER, WebSocketClientCompressionHandler.INSTANCE);
                }

                if (LOGGER.isTraceEnabled()) {
                    pipeline.addFirst(LOGGING_HANDLER, new LoggingHandler(LogLevel.TRACE));
                }

                if (config.getWsAdditionalChannelInitializer() != null) {
                    config.getWsAdditionalChannelInitializer().accept(ch);
                }
            }
        });
    }

    private HttpContentDecompressor newHttpContentDecompressor() {
        if (config.isKeepEncodingHeader()) {
            return new HttpContentDecompressor() {
                @Override
                protected String getTargetContentEncoding(String contentEncoding) {
                    return contentEncoding;
                }
            };
        } else {
            return new HttpContentDecompressor();
        }
    }

    public final void tryToOfferChannelToPool(Channel channel, AsyncHandler<?> asyncHandler, boolean keepAlive, Object partitionKey) {
        if (channel.isActive() && keepAlive) {
            LOGGER.debug("Adding key: {} for channel {}", partitionKey, channel);
            Channels.setDiscard(channel);

            try {
                asyncHandler.onConnectionOffer(channel);
            } catch (Exception e) {
                LOGGER.error("onConnectionOffer crashed", e);
            }

            if (!channelPool.offer(channel, partitionKey)) {
                // rejected by pool
                closeChannel(channel);
            }
        } else {
            // not offered
            closeChannel(channel);
        }
    }

    public Channel poll(Uri uri, String virtualHost, ProxyServer proxy, ChannelPoolPartitioning connectionPoolPartitioning) {
        Object partitionKey = connectionPoolPartitioning.getPartitionKey(uri, virtualHost, proxy);
        return channelPool.poll(partitionKey);
    }

    public void removeAll(Channel connection) {
        channelPool.removeAll(connection);
    }

    private void doClose() {
        ChannelGroupFuture groupFuture = openChannels.close();
        channelPool.destroy();
        groupFuture.addListener(future -> sslEngineFactory.destroy());
    }

    public void close() {
        if (allowReleaseEventLoopGroup) {
            final long shutdownQuietPeriod = config.getShutdownQuietPeriod().toMillis();
            final long shutdownTimeout = config.getShutdownTimeout().toMillis();
            eventLoopGroup
                    .shutdownGracefully(shutdownQuietPeriod, shutdownTimeout, TimeUnit.MILLISECONDS)
                    .addListener(future -> doClose());
        } else {
            doClose();
        }
    }

    public void closeChannel(Channel channel) {
        LOGGER.debug("Closing Channel {} ", channel);
        Channels.setDiscard(channel);
        removeAll(channel);
        Channels.silentlyCloseChannel(channel);
    }

    public void registerOpenChannel(Channel channel) {
        openChannels.add(channel);
    }

    private HttpClientCodec newHttpClientCodec() {
        return new HttpClientCodec(//
                config.getHttpClientCodecMaxInitialLineLength(),
                config.getHttpClientCodecMaxHeaderSize(),
                config.getHttpClientCodecMaxChunkSize(),
                false,
                config.isValidateResponseHeaders(),
                config.getHttpClientCodecInitialBufferSize());
    }

    private SslHandler createSslHandler(String peerHost, int peerPort) {
        SSLEngine sslEngine = sslEngineFactory.newSslEngine(config, peerHost, peerPort);
        SslHandler sslHandler = new SslHandler(sslEngine);
        if (handshakeTimeout > 0) {
            sslHandler.setHandshakeTimeoutMillis(handshakeTimeout);
        }
        return sslHandler;
    }

    public Future<Channel> updatePipelineForHttpTunneling(ChannelPipeline pipeline, Uri requestUri) {
        Future<Channel> whenHandshaked = null;

        if (pipeline.get(HTTP_CLIENT_CODEC) != null) {
            pipeline.remove(HTTP_CLIENT_CODEC);
        }

        if (requestUri.isSecured()) {
            if (!isSslHandlerConfigured(pipeline)) {
                SslHandler sslHandler = createSslHandler(requestUri.getHost(), requestUri.getExplicitPort());
                whenHandshaked = sslHandler.handshakeFuture();
                pipeline.addBefore(INFLATER_HANDLER, SSL_HANDLER, sslHandler);
            }
            pipeline.addAfter(SSL_HANDLER, HTTP_CLIENT_CODEC, newHttpClientCodec());

        } else {
            pipeline.addBefore(AHC_HTTP_HANDLER, HTTP_CLIENT_CODEC, newHttpClientCodec());
        }

        if (requestUri.isWebSocket()) {
            pipeline.addAfter(AHC_HTTP_HANDLER, AHC_WS_HANDLER, wsHandler);

            if (config.isEnableWebSocketCompression()) {
                pipeline.addBefore(AHC_WS_HANDLER, WS_COMPRESSOR_HANDLER, WebSocketClientCompressionHandler.INSTANCE);
            }

            pipeline.remove(AHC_HTTP_HANDLER);
        }
        return whenHandshaked;
    }

    public SslHandler addSslHandler(ChannelPipeline pipeline, Uri uri, String virtualHost, boolean hasSocksProxyHandler) {
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
        if (hasSocksProxyHandler) {
            pipeline.addAfter(SOCKS_HANDLER, SSL_HANDLER, sslHandler);
        } else {
            pipeline.addFirst(SSL_HANDLER, sslHandler);
        }
        return sslHandler;
    }

    public Future<Bootstrap> getBootstrap(Uri uri, NameResolver<InetAddress> nameResolver, ProxyServer proxy) {
        final Promise<Bootstrap> promise = ImmediateEventExecutor.INSTANCE.newPromise();

        if (uri.isWebSocket() && proxy == null) {
            return promise.setSuccess(wsBootstrap);
        }

        if (proxy != null && proxy.getProxyType().isSocks()) {
            Bootstrap socksBootstrap = httpBootstrap.clone();
            ChannelHandler httpBootstrapHandler = socksBootstrap.config().handler();

            nameResolver.resolve(proxy.getHost()).addListener((Future<InetAddress> whenProxyAddress) -> {
                if (whenProxyAddress.isSuccess()) {
                    socksBootstrap.handler(new ChannelInitializer<Channel>() {
                        @Override
                        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                            httpBootstrapHandler.handlerAdded(ctx);
                            super.handlerAdded(ctx);
                        }

                        @Override
                        protected void initChannel(Channel channel) throws Exception {
                            InetSocketAddress proxyAddress = new InetSocketAddress(whenProxyAddress.get(), proxy.getPort());
                            Realm realm = proxy.getRealm();
                            String username = realm != null ? realm.getPrincipal() : null;
                            String password = realm != null ? realm.getPassword() : null;
                            ProxyHandler socksProxyHandler;
                            switch (proxy.getProxyType()) {
                                case SOCKS_V4:
                                    socksProxyHandler = new Socks4ProxyHandler(proxyAddress, username);
                                    break;

                                case SOCKS_V5:
                                    socksProxyHandler = new Socks5ProxyHandler(proxyAddress, username, password);
                                    break;

                                default:
                                    throw new IllegalArgumentException("Only SOCKS4 and SOCKS5 supported at the moment.");
                            }
                            channel.pipeline().addFirst(SOCKS_HANDLER, socksProxyHandler);
                        }
                    });
                    promise.setSuccess(socksBootstrap);

                } else {
                    promise.setFailure(whenProxyAddress.cause());
                }
            });

        } else {
            promise.setSuccess(httpBootstrap);
        }

        return promise;
    }

    public void upgradePipelineForWebSockets(ChannelPipeline pipeline) {
        pipeline.addAfter(HTTP_CLIENT_CODEC, WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
        pipeline.addAfter(WS_ENCODER_HANDLER, WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false,
                config.isEnableWebSocketCompression(), config.getWebSocketMaxFrameSize()));

        if (config.isAggregateWebSocketFrameFragments()) {
            pipeline.addAfter(WS_DECODER_HANDLER, WS_FRAME_AGGREGATOR, new WebSocketFrameAggregator(config.getWebSocketMaxBufferSize()));
        }

        pipeline.remove(HTTP_CLIENT_CODEC);
    }

    private OnLastHttpContentCallback newDrainCallback(final NettyResponseFuture<?> future, final Channel channel, final boolean keepAlive, final Object partitionKey) {
        return new OnLastHttpContentCallback(future) {
            @Override
            public void call() {
                tryToOfferChannelToPool(channel, future.getAsyncHandler(), keepAlive, partitionKey);
            }
        };
    }

    public void drainChannelAndOffer(Channel channel, NettyResponseFuture<?> future) {
        drainChannelAndOffer(channel, future, future.isKeepAlive(), future.getPartitionKey());
    }

    public void drainChannelAndOffer(Channel channel, NettyResponseFuture<?> future, boolean keepAlive, Object partitionKey) {
        Channels.setAttribute(channel, newDrainCallback(future, channel, keepAlive, partitionKey));
    }

    public ChannelPool getChannelPool() {
        return channelPool;
    }

    public EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    public ClientStats getClientStats() {
        Map<String, Long> totalConnectionsPerHost = openChannels.stream()
                .map(Channel::remoteAddress)
                .filter(a -> a instanceof InetSocketAddress)
                .map(a -> (InetSocketAddress) a)
                .map(InetSocketAddress::getHostString)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        Map<String, Long> idleConnectionsPerHost = channelPool.getIdleChannelCountPerHost();

        Map<String, HostStats> statsPerHost = totalConnectionsPerHost.entrySet()
                .stream()
                .collect(Collectors.toMap(Entry::getKey, entry -> {
                    final long totalConnectionCount = entry.getValue();
                    final long idleConnectionCount = idleConnectionsPerHost.getOrDefault(entry.getKey(), 0L);
                    final long activeConnectionCount = totalConnectionCount - idleConnectionCount;
                    return new HostStats(activeConnectionCount, idleConnectionCount);
                }));
        return new ClientStats(statsPerHost);
    }

    public boolean isOpen() {
        return channelPool.isOpen();
    }
}
