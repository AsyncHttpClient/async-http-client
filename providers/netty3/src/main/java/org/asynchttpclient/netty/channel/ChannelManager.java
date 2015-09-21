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
import static org.jboss.netty.channel.Channels.pipeline;
import static org.jboss.netty.handler.ssl.SslHandler.getDefaultBufferPool;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.SSLEngine;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.channel.SSLEngineFactory;
import org.asynchttpclient.channel.pool.ConnectionPoolPartitioning;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.internal.jsr166.ConcurrentHashMapV8;
import org.asynchttpclient.netty.Callback;
import org.asynchttpclient.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.pool.ChannelPool;
import org.asynchttpclient.netty.channel.pool.ChannelPoolPartitionSelector;
import org.asynchttpclient.netty.channel.pool.DefaultChannelPool;
import org.asynchttpclient.netty.channel.pool.NoopChannelPool;
import org.asynchttpclient.netty.handler.HttpProtocol;
import org.asynchttpclient.netty.handler.Processor;
import org.asynchttpclient.netty.handler.Protocol;
import org.asynchttpclient.netty.handler.WebSocketProtocol;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.PrefixIncrementThreadFactory;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.DefaultChannelFuture;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientBossPool;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.handler.codec.http.HttpClientCodec;
import org.jboss.netty.handler.codec.http.HttpContentDecompressor;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.Timer;
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
    private final ChannelPool channelPool;
    private final boolean maxTotalConnectionsEnabled;
    private final Semaphore freeChannels;
    private final ChannelGroup openChannels;
    private final boolean maxConnectionsPerHostEnabled;
    private final ConcurrentHashMapV8<Object, Semaphore> freeChannelsPerHost;
    private final ConcurrentHashMapV8<Integer, Object> channelId2PartitionKey;
    private final long handshakeTimeout;
    private final Timer nettyTimer;
    private final IOException tooManyConnections;
    private final IOException tooManyConnectionsPerHost;
    private final IOException poolAlreadyClosed;

    private final ClientSocketChannelFactory socketChannelFactory;
    private final boolean allowReleaseSocketChannelFactory;
    private final ClientBootstrap httpBootstrap;
    private final ClientBootstrap wsBootstrap;
    private final ConcurrentHashMapV8.Fun<Object, Semaphore> semaphoreComputer;

    private Processor wsProcessor;

    public ChannelManager(final AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, Timer nettyTimer) {

        this.config = config;
        this.nettyConfig = nettyConfig;
        this.nettyTimer = nettyTimer;
        this.sslEngineFactory = config.getSslEngineFactory() != null ? config.getSslEngineFactory() : new SSLEngineFactory.DefaultSSLEngineFactory(config);

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
                            Object partitionKey = channelId2PartitionKey.remove(Channel.class.cast(o).getId());
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

        if (nettyConfig.getSocketChannelFactory() != null) {
            socketChannelFactory = nettyConfig.getSocketChannelFactory();
            // cannot allow releasing shared channel factory
            allowReleaseSocketChannelFactory = false;

        } else {
            ExecutorService e = nettyConfig.getBossExecutorService();
            if (e == null) {
                ThreadFactory threadFactory = new PrefixIncrementThreadFactory(
                        config.getNameOrDefault() + "-boss-");
                e = Executors.newCachedThreadPool(threadFactory);
            }
            int numWorkers = config.getIoThreadMultiplier() * Runtime.getRuntime().availableProcessors();
            LOGGER.trace("Number of application's worker threads is {}", numWorkers);
            NioClientBossPool nioClientBossPool = new NioClientBossPool(e, 1, new HashedWheelTimer(), ThreadNameDeterminer.CURRENT);
            NioWorkerPool nioWorkerPool = new NioWorkerPool(config.getExecutorService(), numWorkers, ThreadNameDeterminer.CURRENT);
            socketChannelFactory = new NioClientSocketChannelFactory(nioClientBossPool, nioWorkerPool);
            allowReleaseSocketChannelFactory = true;
        }

        httpBootstrap = new ClientBootstrap(socketChannelFactory);
        wsBootstrap = new ClientBootstrap(socketChannelFactory);

        DefaultChannelFuture.setUseDeadLockChecker(nettyConfig.isUseDeadLockChecker());

        // FIXME isn't there a constant for this name???
        if (config.getConnectTimeout() > 0)
            nettyConfig.addProperty("connectTimeoutMillis", config.getConnectTimeout());
        for (Entry<String, Object> entry : nettyConfig.propertiesSet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            httpBootstrap.setOption(key, value);
            wsBootstrap.setOption(key, value);
        }
    }

    public void configureBootstraps(NettyRequestSender requestSender) {

        Protocol httpProtocol = new HttpProtocol(this, config, nettyConfig, requestSender);
        final Processor httpProcessor = new Processor(config, this, requestSender, httpProtocol);

        Protocol wsProtocol = new WebSocketProtocol(this, config, nettyConfig, requestSender);
        wsProcessor = new Processor(config, this, requestSender, wsProtocol);

        httpBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

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

        wsBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addLast(WS_PROCESSOR, wsProcessor);

                if (nettyConfig.getWsAdditionalPipelineInitializer() != null)
                    nettyConfig.getWsAdditionalPipelineInitializer().initPipeline(pipeline);

                return pipeline;
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
        if (channel.isConnected() && keepAlive && channel.isReadable()) {
            LOGGER.debug("Adding key: {} for channel {}", partitionKey, channel);
            Channels.setDiscard(channel);
            if (handler instanceof AsyncHandlerExtensions) {
                AsyncHandlerExtensions.class.cast(handler).onConnectionOffer(channel);
            }
            channelPool.offer(channel, partitionKey);
            if (maxConnectionsPerHostEnabled)
                channelId2PartitionKey.putIfAbsent(channel.getId(), partitionKey);
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

        // FIXME also shutdown in provider
        config.getExecutorService().shutdown();
        if (allowReleaseSocketChannelFactory) {
            socketChannelFactory.releaseExternalResources();
            httpBootstrap.releaseExternalResources();
            wsBootstrap.releaseExternalResources();
        }
    }

    public void closeChannel(Channel channel) {

        // The channel may have already been removed from the future if a
        // timeout occurred, and this method may be called just after.
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

    public void abortChannelPreemption(Object partitionKey) {
        if (maxTotalConnectionsEnabled)
            freeChannels.release();
        if (maxConnectionsPerHostEnabled)
            getFreeConnectionsForHost(partitionKey).release();
    }

    public void registerOpenChannel(Channel channel, Object partitionKey) {
        openChannels.add(channel);
        if (maxConnectionsPerHostEnabled) {
            channelId2PartitionKey.put(channel.getId(), partitionKey);
        }
    }

    private HttpClientCodec newHttpClientCodec() {
        return new HttpClientCodec(//
                config.getHttpClientCodecMaxInitialLineLength(),//
                config.getHttpClientCodecMaxHeaderSize(),//
                config.getHttpClientCodecMaxChunkSize());
    }

    private SslHandler createSslHandler(String peerHost, int peerPort) throws GeneralSecurityException {
        SSLEngine sslEngine = sslEngineFactory.newSSLEngine(peerHost, peerPort);
        SslHandler sslHandler = handshakeTimeout > 0 ? new SslHandler(sslEngine, getDefaultBufferPool(), false, nettyTimer, handshakeTimeout) : new SslHandler(sslEngine);
        sslHandler.setCloseOnSSLException(true);
        return sslHandler;
    }

    public static boolean isSslHandlerConfigured(ChannelPipeline pipeline) {
        return pipeline.get(SSL_HANDLER) != null;
    }

    public void upgradeProtocol(ChannelPipeline pipeline, Uri requestUri) throws GeneralSecurityException {
        if (pipeline.get(HTTP_HANDLER) != null)
            pipeline.remove(HTTP_HANDLER);

        if (requestUri.isSecured())
            if (isSslHandlerConfigured(pipeline)) {
                pipeline.addAfter(SSL_HANDLER, HTTP_HANDLER, newHttpClientCodec());
            } else {
                pipeline.addFirst(HTTP_HANDLER, newHttpClientCodec());
                pipeline.addFirst(SSL_HANDLER, createSslHandler(requestUri.getHost(), requestUri.getExplicitPort()));
            }
        else
            pipeline.addFirst(HTTP_HANDLER, newHttpClientCodec());

        if (requestUri.isWebSocket()) {
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
        pipeline.addFirst(SSL_HANDLER, sslHandler);
        return sslHandler;
    }

    public void verifyChannelPipeline(ChannelPipeline pipeline, Uri uri, String virtualHost) throws GeneralSecurityException {

        boolean sslHandlerConfigured = isSslHandlerConfigured(pipeline);

        if (uri.isSecured()) {
            if (!sslHandlerConfigured) {
                addSslHandler(pipeline, uri, virtualHost);
            }

        } else if (sslHandlerConfigured)
            pipeline.remove(SSL_HANDLER);
    }

    public ClientBootstrap getBootstrap(Uri uri, boolean useProxy) {
        return uri.isWebSocket() && !useProxy ? wsBootstrap : httpBootstrap;
    }

    public void upgradePipelineForWebSockets(ChannelPipeline pipeline) {
        pipeline.addAfter(HTTP_HANDLER, WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
        pipeline.remove(HTTP_HANDLER);
        pipeline.addBefore(WS_PROCESSOR, WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false, false, config.getWebSocketMaxFrameSize()));
        pipeline.addAfter(WS_DECODER_HANDLER, WS_FRAME_AGGREGATOR, new WebSocketFrameAggregator(config.getWebSocketMaxBufferSize()));
    }

    public final Callback newDrainCallback(final NettyResponseFuture<?> future, final Channel channel, final boolean keepAlive, final Object partitionKey) {

        return new Callback(future) {
            @Override
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

    public void flushPartition(Object partitionKey) {
        channelPool.flushPartition(partitionKey);
    }

    public void flushPartitions(ChannelPoolPartitionSelector selector) {
        channelPool.flushPartitions(selector);
    }
}
