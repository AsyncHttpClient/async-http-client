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
package com.ning.http.client.providers.netty.channel;

import static com.ning.http.client.providers.netty.util.HttpUtils.WEBSOCKET;
import static com.ning.http.client.providers.netty.util.HttpUtils.isSecure;
import static com.ning.http.client.providers.netty.util.HttpUtils.isWebSocket;
import static org.jboss.netty.channel.Channels.pipeline;
import static org.jboss.netty.handler.ssl.SslHandler.getDefaultBufferPool;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.DefaultChannelFuture;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpClientCodec;
import org.jboss.netty.handler.codec.http.HttpContentDecompressor;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.Callback;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.pool.ChannelPool;
import com.ning.http.client.providers.netty.channel.pool.DefaultChannelPool;
import com.ning.http.client.providers.netty.channel.pool.NoopChannelPool;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.handler.HttpProtocol;
import com.ning.http.client.providers.netty.handler.Processor;
import com.ning.http.client.providers.netty.handler.Protocol;
import com.ning.http.client.providers.netty.handler.WebSocketProtocol;
import com.ning.http.client.providers.netty.request.NettyRequestSender;
import com.ning.http.util.SslUtils;

import javax.net.ssl.SSLEngine;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public static final String WS_ENCODER_HANDLER = "ws-encoder";

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig nettyConfig;
    private final ChannelPool channelPool;
    private final boolean maxTotalConnectionsEnabled;
    private final Semaphore freeChannels;
    private final ChannelGroup openChannels;
    private final boolean maxConnectionsPerHostEnabled;
    private final ConcurrentHashMap<String, Semaphore> freeChannelsPerHost;
    private final ConcurrentHashMap<Integer, String> channelId2KeyPool;
    private final long handshakeTimeoutInMillis;
    private final Timer nettyTimer;

    private final ClientSocketChannelFactory socketChannelFactory;
    private final boolean allowReleaseSocketChannelFactory;
    private final ClientBootstrap plainBootstrap;
    private final ClientBootstrap secureBootstrap;
    private final ClientBootstrap webSocketBootstrap;
    private final ClientBootstrap secureWebSocketBootstrap;

    private Processor wsProcessor;

    public ChannelManager(AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, Timer nettyTimer) {

        this.config = config;
        this.nettyConfig = nettyConfig;
        this.nettyTimer = nettyTimer;

        ChannelPool channelPool = nettyConfig.getChannelPool();
        if (channelPool == null && config.isAllowPoolingConnections()) {
            channelPool = new DefaultChannelPool(config, nettyTimer);
        } else if (channelPool == null) {
            channelPool = new NoopChannelPool();
        }
        this.channelPool = channelPool;

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
                            String poolKey = channelId2KeyPool.remove(Channel.class.cast(o).getId());
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
            freeChannelsPerHost = new ConcurrentHashMap<String, Semaphore>();
            channelId2KeyPool = new ConcurrentHashMap<Integer, String>();
        } else {
            freeChannelsPerHost = null;
            channelId2KeyPool = null;
        }

        handshakeTimeoutInMillis = nettyConfig.getHandshakeTimeoutInMillis();

        if (nettyConfig.getSocketChannelFactory() != null) {
            socketChannelFactory = nettyConfig.getSocketChannelFactory();
            // cannot allow releasing shared channel factory
            allowReleaseSocketChannelFactory = false;

        } else {
            ExecutorService e = nettyConfig.getBossExecutorService();
            if (e == null)
                e = Executors.newCachedThreadPool();
            int numWorkers = config.getIoThreadMultiplier() * Runtime.getRuntime().availableProcessors();
            LOGGER.trace("Number of application's worker threads is {}", numWorkers);
            socketChannelFactory = new NioClientSocketChannelFactory(e, config.executorService(), numWorkers);
            allowReleaseSocketChannelFactory = true;
        }

        plainBootstrap = new ClientBootstrap(socketChannelFactory);
        secureBootstrap = new ClientBootstrap(socketChannelFactory);
        webSocketBootstrap = new ClientBootstrap(socketChannelFactory);
        secureWebSocketBootstrap = new ClientBootstrap(socketChannelFactory);

        DefaultChannelFuture.setUseDeadLockChecker(nettyConfig.isUseDeadLockChecker());

        // FIXME isn't there a constant for this name???
        if (config.getConnectionTimeout() > 0)
            nettyConfig.addProperty("connectTimeoutMillis", config.getConnectionTimeout());
        for (Entry<String, Object> entry : nettyConfig.propertiesSet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            plainBootstrap.setOption(key, value);
            webSocketBootstrap.setOption(key, value);
            secureBootstrap.setOption(key, value);
            secureWebSocketBootstrap.setOption(key, value);
        }
    }

    public void configureBootstraps(NettyRequestSender requestSender, AtomicBoolean closed) {

        Protocol httpProtocol = new HttpProtocol(this, config, nettyConfig, requestSender);
        final Processor httpProcessor = new Processor(config, this, requestSender, httpProtocol);

        Protocol wsProtocol = new WebSocketProtocol(this, config, nettyConfig, requestSender);
        wsProcessor = new Processor(config, this, requestSender, wsProtocol);

        final boolean compressionEnabled = config.isCompressionEnabled();

        plainBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());
                if (compressionEnabled)
                    pipeline.addLast(INFLATER_HANDLER, new HttpContentDecompressor());
                pipeline.addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler());
                pipeline.addLast(HTTP_PROCESSOR, httpProcessor);
                return pipeline;
            }
        });

        webSocketBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addLast(WS_PROCESSOR, wsProcessor);
                return pipeline;
            }
        });

        secureBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(SSL_HANDLER, new SslInitializer(ChannelManager.this));
                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());
                if (compressionEnabled)
                    pipeline.addLast(INFLATER_HANDLER, new HttpContentDecompressor());
                pipeline.addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler());
                pipeline.addLast(HTTP_PROCESSOR, httpProcessor);
                return pipeline;
            }
        });

        secureWebSocketBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(SSL_HANDLER, new SslInitializer(ChannelManager.this));
                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addLast(WS_PROCESSOR, wsProcessor);
                return pipeline;
            }
        });
    }

    public final void tryToOfferChannelToPool(Channel channel, boolean keepAlive, String poolKey) {
        if (keepAlive && channel.isReadable()) {
            LOGGER.debug("Adding key: {} for channel {}", poolKey, channel);
            channelPool.offer(channel, poolKey);
            if (maxConnectionsPerHostEnabled)
                channelId2KeyPool.putIfAbsent(channel.getId(), poolKey);
            Channels.setDiscard(channel);
        } else {
            // not offered
            closeChannel(channel);
        }
    }

    public Channel poll(String uri) {
        return channelPool.poll(uri);
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

    public boolean preemptChannel(String poolKey) {
        return channelPool.isOpen() && tryAcquireGlobal() && tryAcquirePerHost(poolKey);
    }

    public void close() {
        channelPool.destroy();
        openChannels.close();

        for (Channel channel : openChannels) {
            Object attachment = Channels.getAttachment(channel);
            if (attachment instanceof NettyResponseFuture<?>) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;
                future.cancelTimeouts();
            }
        }

        // FIXME also shutdown in provider
        config.executorService().shutdown();
        if (allowReleaseSocketChannelFactory) {
            socketChannelFactory.releaseExternalResources();
            plainBootstrap.releaseExternalResources();
            secureBootstrap.releaseExternalResources();
            webSocketBootstrap.releaseExternalResources();
            secureWebSocketBootstrap.releaseExternalResources();
        }
    }

    public void closeChannel(Channel channel) {
        removeAll(channel);
        Channels.setDiscard(channel);

        // The channel may have already been removed if a timeout occurred, and this method may be called just after.
        if (channel != null) {
            LOGGER.debug("Closing Channel {} ", channel);
            try {
                channel.close();
            } catch (Throwable t) {
                LOGGER.debug("Error closing a connection", t);
            }
            openChannels.remove(channel);
        }
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
                nettyConfig.getHttpClientCodecMaxChunkSize());
    }

    public SslHandler createSslHandler(String peerHost, int peerPort) throws GeneralSecurityException, IOException {
        SSLEngine sslEngine = SslUtils.getInstance().getSSLContext(config.isAcceptAnyCertificate()).createSSLEngine();
        return handshakeTimeoutInMillis > 0 ? new SslHandler(sslEngine, getDefaultBufferPool(), false, nettyTimer, handshakeTimeoutInMillis)
                : new SslHandler(sslEngine);
    }

    public void upgradeProtocol(ChannelPipeline pipeline, String scheme, String host, int port) throws IOException,
            GeneralSecurityException {
        if (pipeline.get(HTTP_HANDLER) != null)
            pipeline.remove(HTTP_HANDLER);

        if (isSecure(scheme))
            if (pipeline.get(SSL_HANDLER) == null) {
                pipeline.addFirst(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addFirst(SSL_HANDLER, createSslHandler(host, port));
            } else {
                pipeline.addAfter(SSL_HANDLER, HTTP_HANDLER, newHttpClientCodec());
            }

        else
            pipeline.addFirst(HTTP_HANDLER, newHttpClientCodec());

        if (isWebSocket(scheme))
            pipeline.replace(HTTP_PROCESSOR, WS_PROCESSOR, wsProcessor);
    }

    public String getPoolKey(NettyResponseFuture<?> future) {
        return future.getConnectionPoolKeyStrategy().getKey(future.getURI(), future.getProxyServer());
    }

    public void verifyChannelPipeline(ChannelPipeline pipeline, String scheme) throws IOException, GeneralSecurityException {

        boolean isSecure = isSecure(scheme);
        if (pipeline.get(SSL_HANDLER) != null) {
            if (!isSecure)
                pipeline.remove(SSL_HANDLER);

        } else if (isSecure)
            pipeline.addFirst(SSL_HANDLER, new SslInitializer(this));
    }

    public ClientBootstrap getBootstrap(String scheme, boolean useProxy, boolean useSSl) {
        return scheme.startsWith(WEBSOCKET) && !useProxy ? (useSSl ? secureWebSocketBootstrap : webSocketBootstrap) : //
                (useSSl ? secureBootstrap : plainBootstrap);
    }

    public void upgradePipelineForWebSockets(ChannelPipeline pipeline) {
        pipeline.replace(HTTP_HANDLER, WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
        pipeline.addBefore(WS_PROCESSOR, WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false, false, 10 * 1024));
    }

    public final Callback newDrainCallback(final NettyResponseFuture<?> future, final Channel channel, final boolean keepAlive,
            final String poolKey) {

        return new Callback(future) {
            @Override
            public void call() throws Exception {
                tryToOfferChannelToPool(channel, keepAlive, poolKey);
            }
        };
    }

    public void drainChannel(final Channel channel, final NettyResponseFuture<?> future) {
        Channels.setAttachment(channel, newDrainCallback(future, channel, future.isKeepAlive(), getPoolKey(future)));
    }
}
