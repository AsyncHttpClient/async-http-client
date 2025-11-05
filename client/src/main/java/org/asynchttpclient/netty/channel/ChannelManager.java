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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
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
import io.netty.util.concurrent.*;
import io.netty.util.internal.PlatformDependent;
import org.asynchttpclient.*;
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
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central manager for Netty channel lifecycle and pipeline configuration.
 * <p>
 * This class is responsible for:
 * <ul>
 *   <li>Creating and configuring Netty bootstraps for HTTP and WebSocket connections</li>
 *   <li>Managing channel pools for connection reuse</li>
 *   <li>Configuring SSL/TLS handlers</li>
 *   <li>Setting up protocol-specific pipeline handlers</li>
 *   <li>Tracking open channels and connection statistics</li>
 * </ul>
 * </p>
 * <p>
 * The ChannelManager supports multiple transport implementations (NIO, Epoll, KQueue)
 * and handles both HTTP and WebSocket protocols with automatic pipeline reconfiguration.
 * </p>
 */
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

  /**
   * Constructs a new ChannelManager with the specified configuration.
   * <p>
   * This constructor initializes the event loop group, channel pool, SSL engine factory,
   * and bootstraps for HTTP and WebSocket connections. It automatically selects the
   * appropriate transport implementation (NIO, Epoll, or KQueue) based on the platform
   * and configuration.
   * </p>
   *
   * @param config the async HTTP client configuration
   * @param nettyTimer the timer for scheduling timeouts and periodic tasks
   * @throws RuntimeException if SSL engine factory initialization fails
   */
  public ChannelManager(final AsyncHttpClientConfig config, Timer nettyTimer) {

    this.config = config;

    this.sslEngineFactory = config.getSslEngineFactory() != null ? config.getSslEngineFactory() : new DefaultSslEngineFactory();
    try {
      this.sslEngineFactory.init(config);
    } catch (SSLException e) {
      throw new RuntimeException("Could not initialize sslEngineFactory", e);
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
        transportFactory = getNativeTransportFactory();
      } else {
        transportFactory = NioTransportFactory.INSTANCE;
      }
      eventLoopGroup = transportFactory.newEventLoopGroup(config.getIoThreadsCount(), threadFactory);

    } else {
      eventLoopGroup = config.getEventLoopGroup();

      if (eventLoopGroup instanceof NioEventLoopGroup) {
        transportFactory = NioTransportFactory.INSTANCE;
      } else if (eventLoopGroup instanceof EpollEventLoopGroup) {
        transportFactory = new EpollTransportFactory();
      } else if (eventLoopGroup instanceof KQueueEventLoopGroup) {
        transportFactory = new KQueueTransportFactory();
      } else {
        throw new IllegalArgumentException("Unknown event loop group " + eventLoopGroup.getClass().getSimpleName());
      }
    }

    httpBootstrap = newBootstrap(transportFactory, eventLoopGroup, config);
    wsBootstrap = newBootstrap(transportFactory, eventLoopGroup, config);

    // for reactive streams
    httpBootstrap.option(ChannelOption.AUTO_READ, false);
  }

  /**
   * Checks if an SSL handler is configured in the pipeline.
   *
   * @param pipeline the channel pipeline to check
   * @return true if an SSL handler is present in the pipeline
   */
  public static boolean isSslHandlerConfigured(ChannelPipeline pipeline) {
    return pipeline.get(SSL_HANDLER) != null;
  }

  private Bootstrap newBootstrap(ChannelFactory<? extends Channel> channelFactory, EventLoopGroup eventLoopGroup, AsyncHttpClientConfig config) {
    @SuppressWarnings("deprecation")
    Bootstrap bootstrap = new Bootstrap().channelFactory(channelFactory).group(eventLoopGroup)
            .option(ChannelOption.ALLOCATOR, config.getAllocator() != null ? config.getAllocator() : ByteBufAllocator.DEFAULT)
            .option(ChannelOption.TCP_NODELAY, config.isTcpNoDelay())
            .option(ChannelOption.SO_REUSEADDR, config.isSoReuseAddress())
            .option(ChannelOption.SO_KEEPALIVE, config.isSoKeepAlive())
            .option(ChannelOption.AUTO_CLOSE, false);

    if (config.getConnectTimeout() > 0) {
      bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout());
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

  @SuppressWarnings("unchecked")
  private TransportFactory<? extends Channel, ? extends EventLoopGroup> getNativeTransportFactory() {
    String nativeTransportFactoryClassName = null;
    if (PlatformDependent.isOsx()) {
      nativeTransportFactoryClassName = "org.asynchttpclient.netty.channel.KQueueTransportFactory";
    } else if (!PlatformDependent.isWindows()) {
      nativeTransportFactoryClassName = "org.asynchttpclient.netty.channel.EpollTransportFactory";
    }

    try {
      if (nativeTransportFactoryClassName != null) {
        return (TransportFactory<? extends Channel, ? extends EventLoopGroup>) Class.forName(nativeTransportFactoryClassName).newInstance();
      }
    } catch (Exception e) {
    }
    throw new IllegalArgumentException("No suitable native transport (epoll or kqueue) available");
  }

  /**
   * Configures the HTTP and WebSocket bootstraps with protocol handlers.
   * <p>
   * This method sets up the channel initializers that install the appropriate
   * handlers in the pipeline for HTTP and WebSocket connections. It must be
   * called after construction and before any connections are made.
   * </p>
   *
   * @param requestSender the request sender for handling outgoing requests
   */
  public void configureBootstraps(NettyRequestSender requestSender) {

    final AsyncHttpClientHandler httpHandler = new HttpHandler(config, this, requestSender);
    wsHandler = new WebSocketHandler(config, this, requestSender);

    final LoggingHandler loggingHandler = new LoggingHandler(LogLevel.TRACE);

    httpBootstrap.handler(new ChannelInitializer<Channel>() {
      @Override
      protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline()
                .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec())
                .addLast(INFLATER_HANDLER, newHttpContentDecompressor())
                .addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())
                .addLast(AHC_HTTP_HANDLER, httpHandler);

        if (LOGGER.isTraceEnabled()) {
          pipeline.addFirst(LOGGING_HANDLER, loggingHandler);
        }

        if (config.getHttpAdditionalChannelInitializer() != null)
          config.getHttpAdditionalChannelInitializer().accept(ch);
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

        if (LOGGER.isDebugEnabled()) {
          pipeline.addFirst(LOGGING_HANDLER, loggingHandler);
        }

        if (config.getWsAdditionalChannelInitializer() != null)
          config.getWsAdditionalChannelInitializer().accept(ch);
      }
    });
  }

  private HttpContentDecompressor newHttpContentDecompressor() {
    if (config.isKeepEncodingHeader())
      return new HttpContentDecompressor() {
        @Override
        protected String getTargetContentEncoding(String contentEncoding) {
          return contentEncoding;
        }
      };
    else
      return new HttpContentDecompressor();
  }

  /**
   * Attempts to return a channel to the connection pool for reuse.
   * <p>
   * This method first notifies the async handler via {@link AsyncHandler#onConnectionOffer(Channel)},
   * then offers the channel to the pool if it's active and keepAlive is true. If the pool
   * rejects the channel or keepAlive is false, the channel is closed.
   * </p>
   *
   * @param channel the channel to offer to the pool
   * @param asyncHandler the async handler to notify of the connection offer
   * @param keepAlive whether to keep the connection alive
   * @param partitionKey the pool partition key (typically based on host/port)
   */
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

  /**
   * Polls an idle channel from the connection pool.
   * <p>
   * This method attempts to retrieve an existing connection from the pool based on
   * the partition key determined by the URI, virtual host, and proxy settings.
   * </p>
   *
   * @param uri the target URI
   * @param virtualHost the virtual host header value, or null
   * @param proxy the proxy server configuration, or null
   * @param connectionPoolPartitioning the partitioning strategy for the pool
   * @return a channel from the pool, or null if none available
   */
  public Channel poll(Uri uri, String virtualHost, ProxyServer proxy, ChannelPoolPartitioning connectionPoolPartitioning) {
    Object partitionKey = connectionPoolPartitioning.getPartitionKey(uri, virtualHost, proxy);
    return channelPool.poll(partitionKey);
  }

  /**
   * Removes all instances of a channel from the connection pool.
   * <p>
   * This method is called when a channel becomes unusable and should be
   * removed from all pool partitions where it might be stored.
   * </p>
   *
   * @param connection the channel to remove from the pool
   */
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
      eventLoopGroup
              .shutdownGracefully(config.getShutdownQuietPeriod(), config.getShutdownTimeout(), TimeUnit.MILLISECONDS)
              .addListener(future -> doClose());
    } else {
      doClose();
    }
  }

  /**
   * Closes a channel and removes it from the pool and open channels group.
   * <p>
   * This method marks the channel for discard, removes it from the pool,
   * and performs a silent close operation.
   * </p>
   *
   * @param channel the channel to close
   */
  public void closeChannel(Channel channel) {
    LOGGER.debug("Closing Channel {} ", channel);
    Channels.setDiscard(channel);
    removeAll(channel);
    Channels.silentlyCloseChannel(channel);
  }

  /**
   * Registers a newly opened channel with the manager.
   * <p>
   * This method adds the channel to the tracked open channels group,
   * allowing it to be managed and closed during shutdown.
   * </p>
   *
   * @param channel the channel to register
   */
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
    if (handshakeTimeout > 0)
      sslHandler.setHandshakeTimeoutMillis(handshakeTimeout);
    return sslHandler;
  }

  /**
   * Updates the pipeline for HTTP tunneling through a proxy (HTTP CONNECT).
   * <p>
   * This method reconfigures the pipeline after a successful CONNECT request,
   * adding SSL if needed and reinstalling the HTTP codec. For WebSocket upgrades
   * through tunnels, it also switches to the WebSocket handler.
   * </p>
   *
   * @param pipeline the channel pipeline to update
   * @param requestUri the target URI after tunneling
   * @return a Future that completes when SSL handshake finishes (if SSL), or null
   */
  public Future<Channel> updatePipelineForHttpTunneling(ChannelPipeline pipeline, Uri requestUri) {

    Future<Channel> whenHandshaked = null;

    if (pipeline.get(HTTP_CLIENT_CODEC) != null)
      pipeline.remove(HTTP_CLIENT_CODEC);

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

  /**
   * Adds an SSL handler to the pipeline for secure connections.
   * <p>
   * This method creates and installs an SSL handler configured for the target
   * host and port. The virtualHost parameter is used for SNI (Server Name Indication).
   * </p>
   *
   * @param pipeline the channel pipeline to modify
   * @param uri the target URI
   * @param virtualHost the virtual host for SNI, or null to use the URI host
   * @param hasSocksProxyHandler whether a SOCKS proxy handler is already in the pipeline
   * @return the created SslHandler
   */
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

  /**
   * Retrieves or creates an appropriate bootstrap for the connection.
   * <p>
   * This method selects the correct bootstrap (HTTP or WebSocket) and configures
   * it for SOCKS proxy if needed. For SOCKS proxies, it clones the HTTP bootstrap
   * and adds the SOCKS handler after resolving the proxy address.
   * </p>
   *
   * @param uri the target URI
   * @param nameResolver the name resolver for resolving proxy addresses
   * @param proxy the proxy server configuration, or null for direct connections
   * @return a Future containing the configured Bootstrap
   */
  public Future<Bootstrap> getBootstrap(Uri uri, NameResolver<InetAddress> nameResolver, ProxyServer proxy) {

    final Promise<Bootstrap> promise = ImmediateEventExecutor.INSTANCE.newPromise();

    if (uri.isWebSocket() && proxy == null) {
      return promise.setSuccess(wsBootstrap);

    } else if (proxy != null && proxy.getProxyType().isSocks()) {
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

  /**
   * Upgrades the pipeline from HTTP to WebSocket protocol.
   * <p>
   * This method replaces the HTTP codec with WebSocket frame encoder/decoder
   * and optionally adds a frame aggregator for fragmented messages. It is called
   * after a successful WebSocket handshake upgrade.
   * </p>
   *
   * @param pipeline the channel pipeline to upgrade
   */
  public void upgradePipelineForWebSockets(ChannelPipeline pipeline) {
    pipeline.addAfter(HTTP_CLIENT_CODEC, WS_ENCODER_HANDLER, new WebSocket08FrameEncoder(true));
    pipeline.addAfter(WS_ENCODER_HANDLER, WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false, config.isEnableWebSocketCompression(), config.getWebSocketMaxFrameSize()));

    if (config.isAggregateWebSocketFrameFragments()) {
      pipeline.addAfter(WS_DECODER_HANDLER, WS_FRAME_AGGREGATOR, new WebSocketFrameAggregator(config.getWebSocketMaxBufferSize()));
    }
    pipeline.remove(HTTP_CLIENT_CODEC);
  }

  private OnLastHttpContentCallback newDrainCallback(final NettyResponseFuture<?> future, final Channel channel, final boolean keepAlive, final Object partitionKey) {

    return new OnLastHttpContentCallback(future) {
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

  /**
   * Returns the channel pool used by this manager.
   *
   * @return the ChannelPool instance
   */
  public ChannelPool getChannelPool() {
    return channelPool;
  }

  /**
   * Returns the event loop group used by this manager.
   *
   * @return the EventLoopGroup instance
   */
  public EventLoopGroup getEventLoopGroup() {
    return eventLoopGroup;
  }

  /**
   * Retrieves statistics about open and idle connections.
   * <p>
   * This method aggregates connection statistics per host, including total
   * connections, idle connections in the pool, and active connections.
   * </p>
   *
   * @return ClientStats containing per-host connection statistics
   */
  public ClientStats getClientStats() {
    Map<String, Long> totalConnectionsPerHost = openChannels.stream().map(Channel::remoteAddress).filter(a -> a instanceof InetSocketAddress)
            .map(a -> (InetSocketAddress) a).map(InetSocketAddress::getHostString).collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    Map<String, Long> idleConnectionsPerHost = channelPool.getIdleChannelCountPerHost();
    Map<String, HostStats> statsPerHost = totalConnectionsPerHost.entrySet().stream().collect(Collectors.toMap(Entry::getKey, entry -> {
      final long totalConnectionCount = entry.getValue();
      final long idleConnectionCount = idleConnectionsPerHost.getOrDefault(entry.getKey(), 0L);
      final long activeConnectionCount = totalConnectionCount - idleConnectionCount;
      return new HostStats(activeConnectionCount, idleConnectionCount);
    }));
    return new ClientStats(statsPerHost);
  }

  /**
   * Checks if the channel manager is open and accepting connections.
   *
   * @return true if the manager and its channel pool are open
   */
  public boolean isOpen() {
    return channelPool.isOpen();
  }
}
