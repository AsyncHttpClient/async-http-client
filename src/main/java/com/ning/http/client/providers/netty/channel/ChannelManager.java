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
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ConnectionPoolKeyStrategy;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.providers.netty.DiscardEvent;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.pool.ChannelPool;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.handler.Processor;
import com.ning.http.client.providers.netty.util.HttpUtil;
import com.ning.http.client.uri.UriComponents;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;
import com.ning.http.util.SslUtils;

import javax.net.ssl.SSLEngine;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class ChannelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelManager.class);

    public static final String HTTP_HANDLER = "httpHandler";
    public static final String SSL_HANDLER = "sslHandler";
    public static final String HTTP_PROCESSOR = "httpProcessor";
    public static final String WS_PROCESSOR = "wsProcessor";

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig nettyConfig;
    private final ChannelPool channelPool;
    private final boolean maxTotalConnectionsEnabled;
    private final Semaphore freeChannels;
    private final ChannelGroup openChannels;
    private final int maxConnectionsPerHost;
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

    public ChannelManager(AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, ChannelPool channelPool, Timer nettyTimer) {

        this.config = config;
        this.nettyConfig = nettyConfig;
        this.channelPool = channelPool;
        this.nettyTimer = nettyTimer;

        maxTotalConnectionsEnabled = config.getMaxConnections() > 0;

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

        maxConnectionsPerHost = config.getMaxConnectionsPerHost();
        maxConnectionsPerHostEnabled = config.getMaxConnectionsPerHost() > 0;

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

    public void configureBootstraps(final Processor httpProcessor, final Processor webSocketProcessor) {

        final boolean compressionEnabled = config.isCompressionEnabled();

        plainBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(HTTP_HANDLER, createHttpClientCodec());
                if (compressionEnabled)
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast(HTTP_PROCESSOR, httpProcessor);
                return pipeline;
            }
        });

        webSocketBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(HTTP_HANDLER, createHttpClientCodec());
                pipeline.addLast(WS_PROCESSOR, webSocketProcessor);
                return pipeline;
            }
        });

        secureBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(SSL_HANDLER, new SslInitializer(ChannelManager.this));
                pipeline.addLast(HTTP_HANDLER, createHttpsClientCodec());
                if (compressionEnabled)
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast(HTTP_PROCESSOR, httpProcessor);
                return pipeline;
            }
        });

        secureWebSocketBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(SSL_HANDLER, new SslInitializer(ChannelManager.this));
                pipeline.addLast(HTTP_HANDLER, createHttpsClientCodec());
                pipeline.addLast(WS_PROCESSOR, webSocketProcessor);
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
            Semaphore newFreeConnections = new Semaphore(maxConnectionsPerHost);
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

    private HttpClientCodec createHttpClientCodec() {
        return new HttpClientCodec(nettyConfig.getHttpClientCodecMaxInitialLineLength(),//
                nettyConfig.getHttpClientCodecMaxHeaderSize(),//
                nettyConfig.getHttpClientCodecMaxChunkSize());
    }

    private HttpClientCodec createHttpsClientCodec() {
        return new HttpClientCodec(nettyConfig.getHttpClientCodecMaxInitialLineLength(),//
                nettyConfig.getHttpClientCodecMaxHeaderSize(),//
                nettyConfig.getHttpClientCodecMaxChunkSize());
    }

    public SslHandler createSslHandler(String peerHost, int peerPort) throws GeneralSecurityException, IOException {
        SSLEngine sslEngine = SslUtils.getInstance().createClientSSLEngine(config, peerHost, peerPort);
        return handshakeTimeoutInMillis > 0 ? new SslHandler(sslEngine, getDefaultBufferPool(), false, nettyTimer, handshakeTimeoutInMillis)
                : new SslHandler(sslEngine);
    }

    public void upgradeProtocol(ChannelPipeline pipeline, String scheme, String host, int port, Processor webSocketProcessor)
            throws IOException, GeneralSecurityException {
        if (pipeline.get(HTTP_HANDLER) != null)
            pipeline.remove(HTTP_HANDLER);

        if (HttpUtil.isSecure(scheme)) {
            if (pipeline.get(SSL_HANDLER) == null) {
                pipeline.addFirst(HTTP_HANDLER, createHttpClientCodec());
                pipeline.addFirst(SSL_HANDLER, createSslHandler(host, port));
            } else {
                pipeline.addAfter(SSL_HANDLER, HTTP_HANDLER, createHttpClientCodec());
            }

        } else {
            pipeline.addFirst(HTTP_HANDLER, createHttpClientCodec());
        }

        if (HttpUtil.isWebSocket(scheme))
            pipeline.replace(HTTP_PROCESSOR, WS_PROCESSOR, webSocketProcessor);
    }

    public boolean validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        return request.getMethod().equals(HttpMethod.GET.getName()) && asyncHandler instanceof WebSocketUpgradeHandler;
    }

    public String getPoolKey(NettyResponseFuture<?> future) {
        return getPoolKey(future.getURI(), future.getProxyServer(), future.getConnectionPoolKeyStrategy());
    }

    public String getPoolKey(UriComponents uri, ProxyServer proxy, ConnectionPoolKeyStrategy strategy) {
        String serverPart = strategy.getKey(uri);
        return proxy != null ? proxy.getUrl() + serverPart : serverPart;
    }

    public Channel verifyChannelPipeline(Channel channel, String scheme) throws IOException, GeneralSecurityException {

        if (channel.getPipeline().get(SSL_HANDLER) != null && HttpUtil.HTTP.equalsIgnoreCase(scheme)) {
            channel.getPipeline().remove(SSL_HANDLER);
        } else if (channel.getPipeline().get(HTTP_HANDLER) != null && HttpUtil.HTTP.equalsIgnoreCase(scheme)) {
            return channel;
        } else if (channel.getPipeline().get(SSL_HANDLER) == null && HttpUtil.isSecure(scheme)) {
            channel.getPipeline().addFirst(SSL_HANDLER, new SslInitializer(this));
        }
        return channel;
    }

    public ClientBootstrap getBootstrap(String scheme, boolean useProxy, boolean useSSl) {
        return (scheme.startsWith(HttpUtil.WEBSOCKET) && !useProxy) ? (useSSl ? secureWebSocketBootstrap : webSocketBootstrap)
                : (useSSl ? secureBootstrap : plainBootstrap);
    }
}
