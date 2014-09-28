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
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ConnectionPoolPartitioning;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.providers.netty.Callback;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.pool.ChannelPool;
import com.ning.http.client.providers.netty.channel.pool.ChannelPoolPartitionSelector;
import com.ning.http.client.providers.netty.channel.pool.DefaultChannelPool;
import com.ning.http.client.providers.netty.channel.pool.NoopChannelPool;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.handler.HttpProtocol;
import com.ning.http.client.providers.netty.handler.Processor;
import com.ning.http.client.providers.netty.handler.Protocol;
import com.ning.http.client.providers.netty.handler.WebSocketProtocol;
import com.ning.http.client.providers.netty.request.NettyRequestSender;
import com.ning.http.client.uri.Uri;
import com.ning.http.util.SslUtils;

import javax.net.ssl.SSLContext;
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
    public static final String WS_FRAME_AGGREGATOR = "ws-aggregator";
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
    private final long handshakeTimeout;
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

        handshakeTimeout = nettyConfig.getHandshakeTimeout();

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
        if (config.getConnectTimeout() > 0)
            nettyConfig.addProperty("connectTimeoutMillis", config.getConnectTimeout());
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

        plainBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addLast(INFLATER_HANDLER, newHttpContentDecompressor());
                pipeline.addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler());
                pipeline.addLast(HTTP_PROCESSOR, httpProcessor);

                if (nettyConfig.getHttpAdditionalPipelineInitializer() != null)
                    nettyConfig.getHttpAdditionalPipelineInitializer().initPipeline(pipeline);

                return pipeline;
            }
        });

        webSocketBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addLast(WS_PROCESSOR, wsProcessor);

                if (nettyConfig.getWsAdditionalPipelineInitializer() != null)
                    nettyConfig.getWsAdditionalPipelineInitializer().initPipeline(pipeline);

                return pipeline;
            }
        });

        secureBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(SSL_HANDLER, new SslInitializer(ChannelManager.this));
                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addLast(INFLATER_HANDLER, newHttpContentDecompressor());
                pipeline.addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler());
                pipeline.addLast(HTTP_PROCESSOR, httpProcessor);

                if (nettyConfig.getHttpsAdditionalPipelineInitializer() != null)
                    nettyConfig.getHttpsAdditionalPipelineInitializer().initPipeline(pipeline);

                return pipeline;
            }
        });

        secureWebSocketBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(SSL_HANDLER, new SslInitializer(ChannelManager.this));
                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addLast(WS_PROCESSOR, wsProcessor);

                if (nettyConfig.getWssAdditionalPipelineInitializer() != null)
                    nettyConfig.getWssAdditionalPipelineInitializer().initPipeline(pipeline);

                return pipeline;
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

    public final void tryToOfferChannelToPool(Channel channel, boolean keepAlive, String partition) {
        if (channel.isConnected() && keepAlive && channel.isReadable()) {
            LOGGER.debug("Adding key: {} for channel {}", partition, channel);
            channelPool.offer(channel, partition);
            if (maxConnectionsPerHostEnabled)
                channelId2KeyPool.putIfAbsent(channel.getId(), partition);
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

    public boolean preemptChannel(String poolKey) {
        return channelPool.isOpen() && tryAcquireGlobal() && tryAcquirePerHost(poolKey);
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

        // The channel may have already been removed from the future if a timeout occurred, and this method may be called just after.
        LOGGER.debug("Closing Channel {} ", channel);
        try {
            removeAll(channel);
            Channels.setDiscard(channel);
            Channels.silentlyCloseChannel(channel);
        } catch (Throwable t) {
            LOGGER.debug("Error closing a connection", t);
        }
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
                nettyConfig.getHttpClientCodecMaxChunkSize());
    }

    public SslHandler createSslHandler(String peerHost, int peerPort) throws GeneralSecurityException, IOException {
        SSLEngine sslEngine = null;
        if (nettyConfig.getSslEngineFactory() != null) {
            sslEngine = nettyConfig.getSslEngineFactory().newSSLEngine();

        } else {
            SSLContext sslContext = config.getSSLContext();
            if (sslContext == null)
                sslContext = SslUtils.getInstance().getSSLContext(config.isAcceptAnyCertificate());

            sslEngine = sslContext.createSSLEngine(peerHost, peerPort);
            sslEngine.setUseClientMode(true);
        }

        return handshakeTimeout > 0 ? new SslHandler(sslEngine, getDefaultBufferPool(), false, nettyTimer, handshakeTimeout)
                : new SslHandler(sslEngine);
    }

    public static SslHandler getSslHandler(ChannelPipeline pipeline) {
        return (SslHandler) pipeline.get(SSL_HANDLER);
    }

    public static boolean isSslHandlerConfigured(ChannelPipeline pipeline) {
        return pipeline.get(SSL_HANDLER) != null;
    }

    public void upgradeProtocol(ChannelPipeline pipeline, String scheme, String host, int port) throws IOException,
            GeneralSecurityException {
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

    public String getPartitionId(NettyResponseFuture<?> future) {
        return future.getConnectionPoolPartitioning().getPartitionId(future.getUri(), future.getProxyServer());
    }

    public void verifyChannelPipeline(ChannelPipeline pipeline, String scheme) throws IOException, GeneralSecurityException {

        boolean sslHandlerConfigured = isSslHandlerConfigured(pipeline);

        if (isSecure(scheme)) {
            if (!sslHandlerConfigured)
                pipeline.addFirst(SSL_HANDLER, new SslInitializer(this));

        } else if (sslHandlerConfigured)
            pipeline.remove(SSL_HANDLER);
    }

    public ClientBootstrap getBootstrap(String scheme, boolean useProxy, boolean useSSl) {
        return scheme.startsWith(WEBSOCKET) && !useProxy ? (useSSl ? secureWebSocketBootstrap : webSocketBootstrap) : //
                (useSSl ? secureBootstrap : plainBootstrap);
    }

    public void upgradePipelineForWebSockets(ChannelPipeline pipeline) {
        pipeline.addAfter(HTTP_HANDLER, WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
        pipeline.remove(HTTP_HANDLER);
        pipeline.addBefore(WS_PROCESSOR, WS_DECODER_HANDLER,
                new WebSocket08FrameDecoder(false, false, nettyConfig.getWebSocketMaxFrameSize()));
        pipeline.addAfter(WS_DECODER_HANDLER, WS_FRAME_AGGREGATOR, new WebSocketFrameAggregator(nettyConfig.getWebSocketMaxBufferSize()));
    }

    public final Callback newDrainCallback(final NettyResponseFuture<?> future, final Channel channel, final boolean keepAlive,
            final String poolKey) {

        return new Callback(future) {
            @Override
            public void call() {
                tryToOfferChannelToPool(channel, keepAlive, poolKey);
            }
        };
    }

    public void drainChannel(final Channel channel, final NettyResponseFuture<?> future) {
        Channels.setAttribute(channel, newDrainCallback(future, channel, future.isKeepAlive(), getPartitionId(future)));
    }

    public void flushPartition(String partitionId) {
        channelPool.flushPartition(partitionId);
    }

    public void flushPartitions(ChannelPoolPartitionSelector selector) {
        channelPool.flushPartitions(selector);
    }
}
