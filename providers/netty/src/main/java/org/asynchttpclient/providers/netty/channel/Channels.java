/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.channel;

import static org.asynchttpclient.providers.netty.util.HttpUtil.WEBSOCKET;
import static org.asynchttpclient.providers.netty.util.HttpUtil.isSecure;
import static org.asynchttpclient.providers.netty.util.HttpUtil.isWebSocket;
import static org.asynchttpclient.providers.netty.handler.Processor.newHttpProcessor;
import static org.asynchttpclient.providers.netty.handler.Processor.newWsProcessor;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.providers.netty.Callback;
import org.asynchttpclient.providers.netty.DiscardEvent;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.handler.Processor;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.providers.netty.util.CleanupChannelGroup;
import org.asynchttpclient.util.SslUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Channels {

    private static final Logger LOGGER = LoggerFactory.getLogger(Channels.class);
    public static final String HTTP_HANDLER = "httpHandler";
    public static final String SSL_HANDLER = "sslHandler";
    public static final String HTTP_PROCESSOR = "httpProcessor";
    public static final String WS_PROCESSOR = "wsProcessor";
    public static final String DEFLATER_HANDLER = "deflater";
    public static final String INFLATER_HANDLER = "inflater";
    public static final String CHUNKED_WRITER_HANDLER = "chunkedWriter";
    public static final String WS_DECODER_HANDLER = "ws-decoder";
    public static final String WS_ENCODER_HANDLER = "ws-encoder";

    private static final AttributeKey<Object> DEFAULT_ATTRIBUTE = AttributeKey.valueOf("default");

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig nettyProviderConfig;

    private final EventLoopGroup eventLoopGroup;
    private final boolean allowReleaseEventLoopGroup;

    private final Bootstrap plainBootstrap;
    private final Bootstrap secureBootstrap;
    private final Bootstrap webSocketBootstrap;
    private final Bootstrap secureWebSocketBootstrap;

    public final ChannelPool channelPool;
    public final Semaphore freeConnections;
    public final boolean trackConnections;
    public final ChannelGroup openChannels = new CleanupChannelGroup("asyncHttpClient") {
        @Override
        public boolean remove(Object o) {
            boolean removed = super.remove(o);
            if (removed && trackConnections) {
                freeConnections.release();
            }
            return removed;
        }
    };

    private final boolean allowStopNettyTimer;
    private final Timer nettyTimer;
    private final long handshakeTimeoutInMillis;

    private Processor wsProcessor;

    public Channels(final AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyProviderConfig) {

        this.config = config;
        this.nettyProviderConfig = nettyProviderConfig;

        // FIXME https://github.com/netty/netty/issues/2132
        if (config.getRequestCompressionLevel() > 0) {
            LOGGER.warn("Request was enabled but Netty actually doesn't support this feature, see https://github.com/netty/netty/issues/2132");
        }

        // check if external EventLoopGroup is defined
        allowReleaseEventLoopGroup = nettyProviderConfig.getEventLoopGroup() == null;
        eventLoopGroup = allowReleaseEventLoopGroup ? new NioEventLoopGroup() : nettyProviderConfig.getEventLoopGroup();

        // check if external HashedWheelTimer is defined
        allowStopNettyTimer = nettyProviderConfig.getNettyTimer() == null;
        nettyTimer = allowStopNettyTimer ? newNettyTimer() : nettyProviderConfig.getNettyTimer();
        handshakeTimeoutInMillis = nettyProviderConfig.getHandshakeTimeoutInMillis();

        if (!(eventLoopGroup instanceof NioEventLoopGroup))
            throw new IllegalArgumentException("Only Nio is supported");

        plainBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(eventLoopGroup);
        secureBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(eventLoopGroup);
        webSocketBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(eventLoopGroup);
        secureWebSocketBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(eventLoopGroup);

        ChannelPool cp = nettyProviderConfig.getChannelPool();
        if (cp == null) {
            if (config.getAllowPoolingConnection()) {
                cp = new DefaultChannelPool(config, nettyTimer);
            } else {
                cp = new NonChannelPool();
            }
        }
        this.channelPool = cp;
        if (config.getMaxTotalConnections() != -1) {
            trackConnections = true;
            freeConnections = new Semaphore(config.getMaxTotalConnections());
        } else {
            trackConnections = false;
            freeConnections = null;
        }

        Map<String, ChannelOption<Object>> optionMap = new HashMap<String, ChannelOption<Object>>();
        for (Field field : ChannelOption.class.getDeclaredFields()) {
            if (field.getType().isAssignableFrom(ChannelOption.class)) {
                field.setAccessible(true);
                try {
                    optionMap.put(field.getName(), (ChannelOption<Object>) field.get(null));
                } catch (IllegalAccessException ex) {
                    throw new Error(ex);
                }
            }
        }

        if (nettyProviderConfig != null) {
            for (Entry<String, Object> entry : nettyProviderConfig.propertiesSet()) {
                ChannelOption<Object> key = optionMap.get(entry.getKey());
                if (key != null) {
                    Object value = entry.getValue();
                    plainBootstrap.option(key, value);
                    webSocketBootstrap.option(key, value);
                    secureBootstrap.option(key, value);
                    secureWebSocketBootstrap.option(key, value);
                } else {
                    throw new IllegalArgumentException("Unknown config property " + entry.getKey());
                }
            }
        }

        int timeOut = config.getConnectionTimeoutInMs() > 0 ? config.getConnectionTimeoutInMs() : Integer.MAX_VALUE;
        plainBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeOut);
        webSocketBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeOut);
        secureBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeOut);
        secureWebSocketBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeOut);
    }

    private Timer newNettyTimer() {
        HashedWheelTimer nettyTimer = new HashedWheelTimer();
        nettyTimer.start();
        return nettyTimer;
    }

    private SSLEngine createSSLEngine() throws IOException, GeneralSecurityException {
        
        if (nettyProviderConfig.getSslEngineFactory() != null) {
            return nettyProviderConfig.getSslEngineFactory().newSSLEngine();
        
        } else {
            SSLContext sslContext = config.getSSLContext();
            if (sslContext == null)
                sslContext = SslUtils.getInstance().getSSLContext(config.isAcceptAnyCertificate());

            SSLEngine sslEngine = sslContext.createSSLEngine();
            sslEngine.setUseClientMode(true);
            return sslEngine;
        }
    }

    public void configureProcessor(NettyRequestSender requestSender, AtomicBoolean closed) {

        final Processor httpProcessor = newHttpProcessor(config, nettyProviderConfig, requestSender, this, closed);
        wsProcessor = newWsProcessor(config, nettyProviderConfig, requestSender, this, closed);

        plainBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline().addLast(HTTP_HANDLER, newHttpClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast(INFLATER_HANDLER, new HttpContentDecompressor());
                }
                pipeline.addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                        .addLast(HTTP_PROCESSOR, httpProcessor);

                if (nettyProviderConfig.getHttpAdditionalChannelInitializer() != null) {
                    nettyProviderConfig.getHttpAdditionalChannelInitializer().initChannel(ch);
                }
            }
        });

        webSocketBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(HTTP_HANDLER, newHttpClientCodec())//
                        .addLast(WS_PROCESSOR, wsProcessor);

                if (nettyProviderConfig.getWsAdditionalChannelInitializer() != null) {
                    nettyProviderConfig.getWsAdditionalChannelInitializer().initChannel(ch);
                }
            }
        });

        secureBootstrap.handler(new ChannelInitializer<Channel>() {

            @Override
            protected void initChannel(Channel ch) throws Exception {

                SSLEngine sslEngine = createSSLEngine();
                SslHandler sslHandler = new SslHandler(sslEngine);
                if (handshakeTimeoutInMillis > 0)
                    sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutInMillis);

                ChannelPipeline pipeline = ch.pipeline()//
                        .addLast(SSL_HANDLER, sslHandler)//
                        .addLast(HTTP_HANDLER, newHttpClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast(INFLATER_HANDLER, new HttpContentDecompressor());
                }
                pipeline.addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                        .addLast(HTTP_PROCESSOR, httpProcessor);

                if (nettyProviderConfig.getHttpsAdditionalChannelInitializer() != null) {
                    nettyProviderConfig.getHttpsAdditionalChannelInitializer().initChannel(ch);
                }
            }
        });

        secureWebSocketBootstrap.handler(new ChannelInitializer<Channel>() {

            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                        .addLast(SSL_HANDLER, new SslHandler(createSSLEngine()))//
                        .addLast(HTTP_HANDLER, newHttpClientCodec())//
                        .addLast(WS_PROCESSOR, wsProcessor);

                if (nettyProviderConfig.getWssAdditionalChannelInitializer() != null) {
                    nettyProviderConfig.getWssAdditionalChannelInitializer().initChannel(ch);
                }
            }
        });
    }

    public Bootstrap getBootstrap(String url, boolean useSSl, boolean useProxy) {
        return (url.startsWith(WEBSOCKET) && !useProxy) ? (useSSl ? secureWebSocketBootstrap : webSocketBootstrap)
                : (useSSl ? secureBootstrap : plainBootstrap);
    }

    public void close() {
        channelPool.destroy();
        for (Channel channel : openChannels) {
            Object attribute = getDefaultAttribute(channel);
            if (attribute instanceof NettyResponseFuture<?>) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
                future.cancelTimeouts();
            }
        }
        openChannels.close();

        if (allowReleaseEventLoopGroup)
            eventLoopGroup.shutdownGracefully();

        if (allowStopNettyTimer)
            nettyTimer.stop();
    }

    /**
     * Always make sure the channel who got cached support the proper protocol. It could only occurs when a HttpMethod.
     * CONNECT is used against a proxy that requires upgrading from http to https.
     */
    public void verifyChannelPipeline(ChannelPipeline pipeline, String scheme) throws IOException, GeneralSecurityException {

        boolean isSecure = isSecure(scheme);
        if (pipeline.get(SSL_HANDLER) != null) {
            if (!isSecure)
                pipeline.remove(SSL_HANDLER);

        } else if (isSecure)
            pipeline.addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
    }

    protected HttpClientCodec newHttpClientCodec() {
        if (nettyProviderConfig != null) {
            return new HttpClientCodec(//
                    nettyProviderConfig.getMaxInitialLineLength(),//
                    nettyProviderConfig.getMaxHeaderSize(),//
                    nettyProviderConfig.getMaxChunkSize(),//
                    false);

        } else {
            return new HttpClientCodec();
        }
    }

    public void upgradeProtocol(ChannelPipeline p, String scheme) throws IOException, GeneralSecurityException {
        if (p.get(HTTP_HANDLER) != null) {
            p.remove(HTTP_HANDLER);
        }

        if (isSecure(scheme)) {
            if (p.get(SSL_HANDLER) == null) {
                p.addFirst(HTTP_HANDLER, newHttpClientCodec());
                p.addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
            } else {
                p.addAfter(SSL_HANDLER, HTTP_HANDLER, newHttpClientCodec());
            }

        } else {
            p.addFirst(HTTP_HANDLER, newHttpClientCodec());
        }

        if (isWebSocket(scheme)) {
            p.replace(HTTP_PROCESSOR, WS_PROCESSOR, wsProcessor);
        }
    }

    public static void upgradePipelineForWebSockets(Channel channel) {
        channel.pipeline().replace(HTTP_HANDLER, WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
        channel.pipeline().addBefore(WS_PROCESSOR, WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false, false, 10 * 1024));
    }

    public Channel pollAndVerifyCachedChannel(URI uri, ProxyServer proxy, ConnectionPoolKeyStrategy connectionPoolKeyStrategy) {
        final Channel channel = channelPool.poll(connectionPoolKeyStrategy.getKey(uri, proxy));

        if (channel != null) {
            LOGGER.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                verifyChannelPipeline(channel.pipeline(), uri.getScheme());
            } catch (Exception ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }
        return channel;
    }

    public boolean acquireConnection(AsyncHandler<?> asyncHandler) throws IOException {

        if (!channelPool.canCacheConnection()) {
            IOException ex = new IOException("Too many connections " + config.getMaxTotalConnections());
            try {
                asyncHandler.onThrowable(ex);
            } catch (Throwable t) {
                LOGGER.warn("!connectionsPool.canCacheConnection()", t);
            }
            throw ex;
        }

        if (trackConnections) {
            if (freeConnections.tryAcquire()) {
                return true;
            } else {
                IOException ex = new IOException("Too many connections " + config.getMaxTotalConnections());
                try {
                    asyncHandler.onThrowable(ex);
                } catch (Throwable t) {
                    LOGGER.warn("!connectionsPool.canCacheConnection()", t);
                }
                throw ex;
            }
        }

        return false;
    }

    public void registerChannel(Channel channel) {
        openChannels.add(channel);
    }

    public boolean offerToPool(String key, Channel channel) {
        return channelPool.offer(key, channel);
    }

    public void releaseFreeConnections() {
        freeConnections.release();
    }

    public void removeFromPool(Channel channel) {
        channelPool.removeAll(channel);
    }

    public void closeChannel(Channel channel) {
        removeFromPool(channel);
        finishChannel(channel);
    }

    public void finishChannel(Channel channel) {
        setDefaultAttribute(channel, DiscardEvent.INSTANCE);

        // The channel may have already been removed if a timeout occurred, and
        // this method may be called just after.
        if (channel == null)
            return;

        LOGGER.debug("Closing Channel {} ", channel);
        try {
            channel.close();
        } catch (Throwable t) {
            LOGGER.debug("Error closing a connection", t);
        }

        openChannels.remove(channel);
    }

    public void drainChannel(final Channel channel, final NettyResponseFuture<?> future) {
        setDefaultAttribute(channel, new Callback(future) {
            public void call() throws Exception {
                if (!(future.isKeepAlive() && channel.isActive() && channelPool.offer(getPoolKey(future), channel))) {
                    finishChannel(channel);
                }
            }
        });
    }

    public String getPoolKey(NettyResponseFuture<?> future) {
        return future.getConnectionPoolKeyStrategy().getKey(future.getURI(), future.getProxyServer());
    }

    public void removeAll(Channel channel) {
        channelPool.removeAll(channel);
    }

    public void abort(NettyResponseFuture<?> future, Throwable t) {
        Channel channel = future.channel();
        if (channel != null && openChannels.contains(channel)) {
            closeChannel(channel);
            openChannels.remove(channel);
        }

        if (!future.isCancelled() && !future.isDone()) {
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
        }

        future.abort(t);
    }

    public Timeout newTimeoutInMs(TimerTask task, long delayInMs) {
        return nettyTimer.newTimeout(task, delayInMs, TimeUnit.MILLISECONDS);
    }

    public static SslHandler getSslHandler(Channel channel) {
        return channel.pipeline().get(SslHandler.class);
    }

    public static Object getDefaultAttribute(Channel channel) {
        Attribute<Object> attr = channel.attr(DEFAULT_ATTRIBUTE);
        return attr != null ? attr.get() : null;
    }

    public static void setDefaultAttribute(Channel channel, Object o) {
        channel.attr(DEFAULT_ATTRIBUTE).set(o);
    }
}
