package org.asynchttpclient.providers.netty4;

import static org.asynchttpclient.providers.netty4.util.HttpUtil.HTTP;
import static org.asynchttpclient.providers.netty4.util.HttpUtil.WEBSOCKET;
import static org.asynchttpclient.providers.netty4.util.HttpUtil.isSecure;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;

import javax.net.ssl.SSLEngine;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.ConnectionsPool;
import org.asynchttpclient.providers.netty4.util.CleanupChannelGroup;
import org.asynchttpclient.util.SslUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Channels {

    private static final Logger LOGGER = LoggerFactory.getLogger(Channels.class);
    public static final String HTTP_HANDLER = "httpHandler";
    public static final String SSL_HANDLER = "sslHandler";
    public static final String AHC_HANDLER = "httpProcessor";
    public static final String DEFLATER_HANDLER = "deflater";
    public static final String INFLATER_HANDLER = "inflater";
    public static final String CHUNKED_WRITER_HANDLER = "chunkedWriter";
    public static final String HTTP_DECODER_HANDLER = "http-decoder";
    public static final String HTTP_ENCODER_HANDLER = "http-encoder";
    public static final String WS_DECODER_HANDLER = "ws-decoder";
    public static final String WS_ENCODER_HANDLER = "ws-encoder";

    private static final AttributeKey<Object> DEFAULT_ATTRIBUTE = new AttributeKey<Object>("default");

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig asyncHttpProviderConfig;

    private EventLoopGroup eventLoopGroup;
    private final boolean allowReleaseEventLoopGroup;

    private final Bootstrap plainBootstrap;
    private final Bootstrap secureBootstrap;
    private final Bootstrap webSocketBootstrap;
    private final Bootstrap secureWebSocketBootstrap;

    public final ConnectionsPool<String, Channel> connectionsPool;
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

    private NettyChannelHandler httpProcessor;

    public Channels(final AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig asyncHttpProviderConfig) {

        this.config = config;
        this.asyncHttpProviderConfig = asyncHttpProviderConfig;

        Class<? extends SocketChannel> socketChannelClass = null;
        if (asyncHttpProviderConfig.isUseBlockingIO()) {
            socketChannelClass = OioSocketChannel.class;
            eventLoopGroup = new OioEventLoopGroup();
            allowReleaseEventLoopGroup = true;

        } else {
            // check if external EventLoopGroup is defined
            eventLoopGroup = asyncHttpProviderConfig.getEventLoopGroup();
            if (eventLoopGroup instanceof OioEventLoopGroup) {
                socketChannelClass = OioSocketChannel.class;
                allowReleaseEventLoopGroup = false;

            } else if (eventLoopGroup instanceof NioEventLoopGroup) {
                socketChannelClass = NioSocketChannel.class;
                allowReleaseEventLoopGroup = false;

            } else {
                socketChannelClass = NioSocketChannel.class;
                eventLoopGroup = new NioEventLoopGroup();
                allowReleaseEventLoopGroup = true;
            }
        }

        plainBootstrap = new Bootstrap().channel(socketChannelClass).group(eventLoopGroup);
        secureBootstrap = new Bootstrap().channel(socketChannelClass).group(eventLoopGroup);
        webSocketBootstrap = new Bootstrap().channel(socketChannelClass).group(eventLoopGroup);
        secureWebSocketBootstrap = new Bootstrap().channel(socketChannelClass).group(eventLoopGroup);

        // This is dangerous as we can't catch a wrong typed ConnectionsPool
        ConnectionsPool<String, Channel> cp = (ConnectionsPool<String, Channel>) config.getConnectionsPool();
        if (cp == null) {
            if (config.getAllowPoolingConnection()) {
                cp = new NettyConnectionsPool(config);
            } else {
                cp = new NonConnectionsPool();
            }
        }
        this.connectionsPool = cp;
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

        if (asyncHttpProviderConfig != null) {
            for (Entry<String, Object> entry : asyncHttpProviderConfig.propertiesSet()) {
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

        // FIXME clean up
        plainBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectionTimeoutInMs());
        webSocketBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectionTimeoutInMs());
        secureBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectionTimeoutInMs());
        secureWebSocketBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectionTimeoutInMs());

        // FIXME What was the meaning of this and what is it still a matter with
        // Netty4
        // DefaultChannelFuture.setUseDeadLockChecker(false);
    }

    public void configure(final NettyChannelHandler httpProcessor) {
        this.httpProcessor = httpProcessor;

        ChannelInitializer<Channel> httpChannelInitializer = new ChannelInitializer<Channel>() {

            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline()//
                .addLast(HTTP_HANDLER, newHttpClientCodec());

                if (config.getRequestCompressionLevel() > 0) {
                    pipeline.addLast(DEFLATER_HANDLER, new HttpContentCompressor(config.getRequestCompressionLevel()));
                }

                if (config.isCompressionEnabled()) {
                    pipeline.addLast(INFLATER_HANDLER, new HttpContentDecompressor());
                }
                pipeline.addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                .addLast(AHC_HANDLER, httpProcessor);

                if (asyncHttpProviderConfig.getHttpAdditionalChannelInitializer() != null) {
                    asyncHttpProviderConfig.getHttpAdditionalChannelInitializer().initChannel(ch);
                }
            }
        };

        ChannelInitializer<Channel> webSocketChannelInitializer = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//
                .addLast(HTTP_DECODER_HANDLER, new HttpResponseDecoder())//
                .addLast(HTTP_ENCODER_HANDLER, new HttpRequestEncoder())//
                .addLast(AHC_HANDLER, httpProcessor);

                if (asyncHttpProviderConfig.getWsAdditionalChannelInitializer() != null) {
                    asyncHttpProviderConfig.getWsAdditionalChannelInitializer().initChannel(ch);
                }
            }
        };

        plainBootstrap.handler(httpChannelInitializer);
        webSocketBootstrap.handler(webSocketChannelInitializer);
    }

    public Bootstrap getBootstrap(String url, boolean useSSl) {
        Bootstrap bootstrap = url.startsWith(WEBSOCKET) ? (useSSl ? secureWebSocketBootstrap : webSocketBootstrap) : (useSSl ? secureBootstrap : plainBootstrap);

        return bootstrap;
    }

    public void close() {
        connectionsPool.destroy();
        for (Channel channel : openChannels) {
            Object attribute = getDefaultAttribute(channel);
            if (attribute instanceof NettyResponseFuture<?>) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
                future.setReaperFuture(null);
            }
        }
        openChannels.close();
        if (allowReleaseEventLoopGroup) {
            eventLoopGroup.shutdownGracefully();
        }
    }

    void constructSSLPipeline(final NettyResponseFuture<?> future) {

        secureBootstrap.handler(new ChannelInitializer<Channel>() {

            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline();

                try {
                    pipeline.addLast(SSL_HANDLER, new SslHandler(createSSLEngine()));
                } catch (Throwable ex) {
                    LOGGER.error("Channel {} could not add SslHandler {}", ch, ex);
                    abort(future, ex);
                }

                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast(INFLATER_HANDLER, new HttpContentDecompressor());
                }
                pipeline.addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                .addLast(AHC_HANDLER, httpProcessor);

                if (asyncHttpProviderConfig.getHttpsAdditionalChannelInitializer() != null) {
                    asyncHttpProviderConfig.getHttpsAdditionalChannelInitializer().initChannel(ch);
                }
            }
        });

        secureWebSocketBootstrap.handler(new ChannelInitializer<Channel>() {

            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline();

                try {
                    pipeline.addLast(SSL_HANDLER, new SslHandler(createSSLEngine()));
                } catch (Throwable ex) {
                    LOGGER.error("Channel {} could not add SslHandler {}", ch, ex);
                    abort(future, ex);
                }

                pipeline.addLast(HTTP_DECODER_HANDLER, new HttpResponseDecoder())//
                .addLast(HTTP_ENCODER_HANDLER, new HttpRequestEncoder())//
                .addLast(AHC_HANDLER, httpProcessor);

                if (asyncHttpProviderConfig.getWssAdditionalChannelInitializer() != null) {
                    asyncHttpProviderConfig.getWssAdditionalChannelInitializer().initChannel(ch);
                }
            }
        });
    }

    private SSLEngine createSSLEngine() throws IOException, GeneralSecurityException {
        SSLEngine sslEngine = config.getSSLEngineFactory().newSSLEngine();
        if (sslEngine == null) {
            sslEngine = SslUtils.getSSLEngine();
        }
        return sslEngine;
    }

    // FIXME what for?
    public Channel verifyChannelPipeline(Channel channel, String scheme) throws IOException, GeneralSecurityException {

        if (channel.pipeline().get(SSL_HANDLER) != null && HTTP.equalsIgnoreCase(scheme)) {
            channel.pipeline().remove(SSL_HANDLER);
        } else if (channel.pipeline().get(HTTP_HANDLER) != null && HTTP.equalsIgnoreCase(scheme)) {
            return channel;
        } else if (channel.pipeline().get(SSL_HANDLER) == null && isSecure(scheme)) {
            channel.pipeline().addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
        }
        return channel;
    }

    protected HttpClientCodec newHttpClientCodec() {
        if (asyncHttpProviderConfig != null) {
            return new HttpClientCodec(asyncHttpProviderConfig.getMaxInitialLineLength(), asyncHttpProviderConfig.getMaxHeaderSize(), asyncHttpProviderConfig.getMaxChunkSize(),
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
    }

    public static void upgradePipelineForWebSockets(ChannelHandlerContext ctx) {
        ctx.pipeline().replace(Channels.HTTP_ENCODER_HANDLER, Channels.WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
        // ctx.pipeline().get(HttpResponseDecoder.class).replace("ws-decoder",
        // new WebSocket08FrameDecoder(false, false));
        // FIXME Right way? Which maxFramePayloadLength? Configurable I
        // guess
        ctx.pipeline().replace(Channels.HTTP_DECODER_HANDLER, Channels.WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false, false, 10 * 1024));
    }

    public Channel lookupInCache(URI uri, ConnectionPoolKeyStrategy connectionPoolKeyStrategy) {
        final Channel channel = connectionsPool.poll(connectionPoolKeyStrategy.getKey(uri));

        if (channel != null) {
            LOGGER.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                // Always make sure the channel who got cached support the
                // proper protocol. It could
                // only occurs when a HttpMethod.CONNECT is used against a proxy
                // that require upgrading from http to
                // https.
                return verifyChannelPipeline(channel, uri.getScheme());
            } catch (Exception ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }
        return null;
    }

    public boolean acquireConnection(AsyncHandler<?> asyncHandler) throws IOException {

        if (!connectionsPool.canCacheConnection()) {
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
        return connectionsPool.offer(key, channel);
    }

    public void releaseFreeConnections() {
        freeConnections.release();
    }

    public void removeFromPool(ChannelHandlerContext ctx) {
        connectionsPool.removeAll(ctx.channel());
    }

    public void closeChannel(ChannelHandlerContext ctx) {
        removeFromPool(ctx);
        finishChannel(ctx);
    }

    public void finishChannel(ChannelHandlerContext ctx) {
        setDefaultAttribute(ctx, DiscardEvent.INSTANCE);

        // The channel may have already been removed if a timeout occurred, and
        // this method may be called just after.
        if (ctx.channel() == null) {
            return;
        }

        LOGGER.debug("Closing Channel {} ", ctx.channel());

        try {
            ctx.channel().close();
        } catch (Throwable t) {
            LOGGER.debug("Error closing a connection", t);
        }

        if (ctx.channel() != null) {
            openChannels.remove(ctx.channel());
        }
    }

    public void drainChannel(final ChannelHandlerContext ctx, final NettyResponseFuture<?> future) {
        setDefaultAttribute(ctx, new Callback(future) {
            public void call() throws Exception {
                if (!(future.isKeepAlive() && ctx.channel().isActive() && connectionsPool.offer(getPoolKey(future), ctx.channel()))) {
                    finishChannel(ctx);
                }
            }
        });
    }

    public String getPoolKey(NettyResponseFuture<?> future) {
        URI uri = future.getProxyServer() != null ? future.getProxyServer().getURI() : future.getURI();
        return future.getConnectionPoolKeyStrategy().getKey(uri);
    }

    public void removeAll(Channel channel) {
        connectionsPool.removeAll(channel);
    }

    public void abort(NettyResponseFuture<?> future, Throwable t) {
        Channel channel = future.channel();
        if (channel != null && openChannels.contains(channel)) {
            closeChannel(channel.pipeline().context(NettyChannelHandler.class));
            openChannels.remove(channel);
        }

        if (!future.isCancelled() && !future.isDone()) {
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
        }

        future.abort(t);
    }

    public static SslHandler getSslHandler(Channel channel) {
        return (SslHandler) channel.pipeline().get(Channels.SSL_HANDLER);
    }

    public static Object getDefaultAttribute(Channel channel) {
        return getDefaultAttribute(channel.pipeline().context(NettyChannelHandler.class));
    }

    public static Object getDefaultAttribute(ChannelHandlerContext ctx) {
        if (ctx == null) {
            // ctx might be null if the channel never reached the handler
            return null;
        }
        Attribute<Object> attr = ctx.attr(DEFAULT_ATTRIBUTE);
        return attr != null ? attr.get() : null;
    }

    public static void setDefaultAttribute(Channel channel, Object o) {
        setDefaultAttribute(channel.pipeline().context(NettyChannelHandler.class), o);
    }

    public static void setDefaultAttribute(ChannelHandlerContext ctx, Object o) {
        ctx.attr(DEFAULT_ATTRIBUTE).set(o);
    }

    private static class NonConnectionsPool implements ConnectionsPool<String, Channel> {

        public boolean offer(String uri, Channel connection) {
            return false;
        }

        public Channel poll(String uri) {
            return null;
        }

        public boolean removeAll(Channel connection) {
            return false;
        }

        public boolean canCacheConnection() {
            return true;
        }

        public void destroy() {
        }
    }
}
