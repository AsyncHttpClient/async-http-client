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

import static org.asynchttpclient.providers.netty.handler.Processor.newHttpProcessor;
import static org.asynchttpclient.providers.netty.handler.Processor.newWsProcessor;
import static org.asynchttpclient.providers.netty.util.HttpUtil.WEBSOCKET;
import static org.asynchttpclient.providers.netty.util.HttpUtil.isSecure;
import static org.asynchttpclient.providers.netty.util.HttpUtil.isWebSocket;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.providers.netty.DiscardEvent;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.pool.ChannelPool;
import org.asynchttpclient.providers.netty.channel.pool.DefaultChannelPool;
import org.asynchttpclient.providers.netty.channel.pool.NoopChannelPool;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.handler.Processor;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.uri.UriComponents;
import org.asynchttpclient.util.SslUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
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
import java.security.GeneralSecurityException;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
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

    public final ChannelManager channelManager;
    private final boolean allowStopNettyTimer;
    private final Timer nettyTimer;
    private final long handshakeTimeoutInMillis;

    private Processor wsProcessor;

    public Channels(final AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyProviderConfig) {

        this.config = config;
        this.nettyProviderConfig = nettyProviderConfig;

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
            if (config.isAllowPoolingConnections()) {
                cp = new DefaultChannelPool(config, nettyTimer);
            } else {
                cp = new NoopChannelPool();
            }
        }
        this.channelManager = new ChannelManager(config, cp);

        for (Entry<ChannelOption<Object>, Object> entry : nettyProviderConfig.propertiesSet()) {
            ChannelOption<Object> key = entry.getKey();
            Object value = entry.getValue();
            plainBootstrap.option(key, value);
            webSocketBootstrap.option(key, value);
            secureBootstrap.option(key, value);
            secureWebSocketBootstrap.option(key, value);
        }

        int timeOut = config.getConnectionTimeout() > 0 ? config.getConnectionTimeout() : Integer.MAX_VALUE;
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

    public SslHandler createSslHandler(String peerHost, int peerPort) throws IOException, GeneralSecurityException {

        SSLEngine sslEngine = null;
        if (nettyProviderConfig.getSslEngineFactory() != null) {
            sslEngine = nettyProviderConfig.getSslEngineFactory().newSSLEngine();

        } else {
            SSLContext sslContext = config.getSSLContext();
            if (sslContext == null)
                sslContext = SslUtils.getInstance().getSSLContext(config.isAcceptAnyCertificate());

            sslEngine = sslContext.createSSLEngine(peerHost, peerPort);
            sslEngine.setUseClientMode(true);
        }

        SslHandler sslHandler = new SslHandler(sslEngine);
        if (handshakeTimeoutInMillis > 0)
            sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutInMillis);

        return sslHandler;
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

                ChannelPipeline pipeline = ch.pipeline()//
                        .addLast(SSL_HANDLER, new SslInitializer(Channels.this)).addLast(HTTP_HANDLER, newHttpClientCodec());

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
                        .addLast(SSL_HANDLER, new SslInitializer(Channels.this))//
                        .addLast(HTTP_HANDLER, newHttpClientCodec())//
                        .addLast(WS_PROCESSOR, wsProcessor);

                if (nettyProviderConfig.getWssAdditionalChannelInitializer() != null) {
                    nettyProviderConfig.getWssAdditionalChannelInitializer().initChannel(ch);
                }
            }
        });
    }

    public Bootstrap getBootstrap(UriComponents uri, boolean useSSl, boolean useProxy) {
        return (uri.getScheme().startsWith(WEBSOCKET) && !useProxy) ? (useSSl ? secureWebSocketBootstrap : webSocketBootstrap)
                : (useSSl ? secureBootstrap : plainBootstrap);
    }

    public void close() {
        channelManager.destroy();

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
            pipeline.addFirst(SSL_HANDLER, new SslInitializer(Channels.this));
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

    public void upgradeProtocol(ChannelPipeline p, String scheme, String host, int port) throws IOException, GeneralSecurityException {
        if (p.get(HTTP_HANDLER) != null) {
            p.remove(HTTP_HANDLER);
        }

        if (isSecure(scheme)) {
            if (p.get(SSL_HANDLER) == null) {
                p.addFirst(HTTP_HANDLER, newHttpClientCodec());
                p.addFirst(SSL_HANDLER, createSslHandler(host, port));
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

    public Channel pollAndVerifyCachedChannel(UriComponents uri, ProxyServer proxy, ConnectionPoolKeyStrategy connectionPoolKeyStrategy) {
        final Channel channel = channelManager.poll(connectionPoolKeyStrategy.getKey(uri, proxy));

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

    public boolean preemptChannel(AsyncHandler<?> asyncHandler, String poolKey) throws IOException {

        boolean channelPreempted = false;
        if (channelManager.preemptChannel(poolKey)) {
            channelPreempted = true;
        } else {
            IOException ex = new IOException(String.format("Too many connections %s", config.getMaxConnections()));
            try {
                asyncHandler.onThrowable(ex);
            } catch (Exception e) {
                LOGGER.warn("asyncHandler.onThrowable crashed", e);
            }
            throw ex;
        }
        return channelPreempted;
    }

    public void tryToOfferChannelToPool(Channel channel, boolean keepAlive, String poolKey) {
        channelManager.tryToOfferChannelToPool(channel, keepAlive, poolKey);
    }

    public void abortChannelPreemption(String poolKey) {
        channelManager.abortChannelPreemption(poolKey);
    }

    public void closeChannel(Channel channel) {
        channelManager.closeChannel(channel);
    }

    public final Callable<NettyResponseFuture<?>> newDrainCallable(final NettyResponseFuture<?> future, final Channel channel,
            final boolean keepAlive, final String poolKey) {

        return new Callable<NettyResponseFuture<?>>() {
            public NettyResponseFuture<?> call() throws Exception {
                channelManager.tryToOfferChannelToPool(channel, keepAlive, poolKey);
                return null;
            }
        };
    }

    public void drainChannel(final Channel channel, final NettyResponseFuture<?> future) {
        setDefaultAttribute(channel, newDrainCallable(future, channel, future.isKeepAlive(), getPoolKey(future)));
    }

    public String getPoolKey(NettyResponseFuture<?> future) {
        return future.getConnectionPoolKeyStrategy().getKey(future.getURI(), future.getProxyServer());
    }

    public void removeAll(Channel channel) {
        channelManager.removeAll(channel);
    }

    public void abort(NettyResponseFuture<?> future, Throwable t) {
        
        Channel channel = future.channel();
        if (channel != null)
            channelManager.closeChannel(channel);

        if (!future.isDone()) {
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

    public static void setDiscard(Channel channel) {
        setDefaultAttribute(channel, DiscardEvent.INSTANCE);
    }
    
    public void registerOpenChannel(Channel channel) {
        channelManager.registerOpenChannel(channel);
    }
}
