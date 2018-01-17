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
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.resolver.NameResolver;
import io.netty.util.Timer;
import io.netty.util.concurrent.*;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ChannelManager {

  public static final String PINNED_ENTRY = "entry";
  public static final String HTTP_CLIENT_CODEC = "http";
  public static final String SSL_HANDLER = "ssl";
  public static final String SOCKS_HANDLER = "socks";
  public static final String DEFLATER_HANDLER = "deflater";
  public static final String INFLATER_HANDLER = "inflater";
  public static final String CHUNKED_WRITER_HANDLER = "chunked-writer";
  public static final String WS_DECODER_HANDLER = "ws-decoder";
  public static final String WS_FRAME_AGGREGATOR = "ws-aggregator";
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
    ChannelFactory<? extends Channel> channelFactory;
    if (allowReleaseEventLoopGroup) {
      if (config.isUseNativeTransport()) {
        eventLoopGroup = newEpollEventLoopGroup(config.getIoThreadsCount(), threadFactory);
        channelFactory = getEpollSocketChannelFactory();

      } else {
        eventLoopGroup = new NioEventLoopGroup(config.getIoThreadsCount(), threadFactory);
        channelFactory = NioSocketChannelFactory.INSTANCE;
      }

    } else {
      eventLoopGroup = config.getEventLoopGroup();
      if (eventLoopGroup instanceof OioEventLoopGroup)
        throw new IllegalArgumentException("Oio is not supported");

      if (eventLoopGroup instanceof NioEventLoopGroup) {
        channelFactory = NioSocketChannelFactory.INSTANCE;
      } else {
        channelFactory = getEpollSocketChannelFactory();
      }
    }

    httpBootstrap = newBootstrap(channelFactory, eventLoopGroup, config);
    wsBootstrap = newBootstrap(channelFactory, eventLoopGroup, config);

    // for reactive streams
    httpBootstrap.option(ChannelOption.AUTO_READ, false);
  }

  public static boolean isSslHandlerConfigured(ChannelPipeline pipeline) {
    return pipeline.get(SSL_HANDLER) != null;
  }

  private Bootstrap newBootstrap(ChannelFactory<? extends Channel> channelFactory, EventLoopGroup eventLoopGroup, AsyncHttpClientConfig config) {
    @SuppressWarnings("deprecation")
    Bootstrap bootstrap = new Bootstrap().channelFactory(channelFactory).group(eventLoopGroup)//
            .option(ChannelOption.ALLOCATOR, config.getAllocator() != null ? config.getAllocator() : ByteBufAllocator.DEFAULT)//
            .option(ChannelOption.TCP_NODELAY, config.isTcpNoDelay())//
            .option(ChannelOption.SO_REUSEADDR, config.isSoReuseAddress())//
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

  private EventLoopGroup newEpollEventLoopGroup(int ioThreadsCount, ThreadFactory threadFactory) {
    try {
      Class<?> epollEventLoopGroupClass = Class.forName("io.netty.channel.epoll.EpollEventLoopGroup");
      return (EventLoopGroup) epollEventLoopGroupClass.getConstructor(int.class, ThreadFactory.class).newInstance(ioThreadsCount, threadFactory);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private ChannelFactory<? extends Channel> getEpollSocketChannelFactory() {
    try {
      return (ChannelFactory<? extends Channel>) Class.forName("org.asynchttpclient.netty.channel.EpollSocketChannelFactory").newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public void configureBootstraps(NettyRequestSender requestSender) {

    final AsyncHttpClientHandler httpHandler = new HttpHandler(config, this, requestSender);
    wsHandler = new WebSocketHandler(config, this, requestSender);

    final NoopHandler pinnedEntry = new NoopHandler();

    final LoggingHandler loggingHandler = new LoggingHandler(LogLevel.TRACE);

    httpBootstrap.handler(new ChannelInitializer<Channel>() {
      @Override
      protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline()//
                .addLast(PINNED_ENTRY, pinnedEntry)//
                .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec())//
                .addLast(INFLATER_HANDLER, newHttpContentDecompressor())//
                .addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                .addLast(AHC_HTTP_HANDLER, httpHandler);

        if (LOGGER.isTraceEnabled()) {
          pipeline.addAfter(PINNED_ENTRY, LOGGING_HANDLER, loggingHandler);
        }

        if (config.getHttpAdditionalChannelInitializer() != null)
          config.getHttpAdditionalChannelInitializer().accept(ch);
      }
    });

    wsBootstrap.handler(new ChannelInitializer<Channel>() {
      @Override
      protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline()//
                .addLast(PINNED_ENTRY, pinnedEntry)//
                .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec())//
                .addLast(AHC_WS_HANDLER, wsHandler);

        if (LOGGER.isDebugEnabled()) {
          pipeline.addAfter(PINNED_ENTRY, LOGGING_HANDLER, loggingHandler);
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
    openChannels.close();
    channelPool.destroy();
  }

  public void close() {
    if (allowReleaseEventLoopGroup) {
      eventLoopGroup.shutdownGracefully(config.getShutdownQuietPeriod(), config.getShutdownTimeout(), TimeUnit.MILLISECONDS)//
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
            config.getHttpClientCodecMaxInitialLineLength(),//
            config.getHttpClientCodecMaxHeaderSize(),//
            config.getHttpClientCodecMaxChunkSize(),//
            false,//
            config.isValidateResponseHeaders(),//
            config.getHttpClientCodecInitialBufferSize());
  }

  private SslHandler createSslHandler(String peerHost, int peerPort) {
    SSLEngine sslEngine = sslEngineFactory.newSslEngine(config, peerHost, peerPort);
    SslHandler sslHandler = new SslHandler(sslEngine);
    if (handshakeTimeout > 0)
      sslHandler.setHandshakeTimeoutMillis(handshakeTimeout);
    return sslHandler;
  }

  public void upgradeProtocol(ChannelPipeline pipeline, Uri requestUri) {
    if (pipeline.get(HTTP_CLIENT_CODEC) != null)
      pipeline.remove(HTTP_CLIENT_CODEC);

    if (requestUri.isSecured())
      if (isSslHandlerConfigured(pipeline)) {
        pipeline.addAfter(SSL_HANDLER, HTTP_CLIENT_CODEC, newHttpClientCodec());
      } else {
        pipeline.addAfter(PINNED_ENTRY, HTTP_CLIENT_CODEC, newHttpClientCodec());
        pipeline.addAfter(PINNED_ENTRY, SSL_HANDLER, createSslHandler(requestUri.getHost(), requestUri.getExplicitPort()));
      }

    else
      pipeline.addAfter(PINNED_ENTRY, HTTP_CLIENT_CODEC, newHttpClientCodec());

    if (requestUri.isWebSocket()) {
      pipeline.addAfter(AHC_HTTP_HANDLER, AHC_WS_HANDLER, wsHandler);
      pipeline.remove(AHC_HTTP_HANDLER);
    }
  }

  public SslHandler addSslHandler(ChannelPipeline pipeline, Uri uri, String virtualHost) {
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
    pipeline.addFirst(ChannelManager.SSL_HANDLER, sslHandler);
    return sslHandler;
  }

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
              switch (proxy.getProxyType()) {
                case SOCKS_V4:
                  channel.pipeline().addFirst(SOCKS_HANDLER, new Socks4ProxyHandler(proxyAddress));
                  break;

                case SOCKS_V5:
                  channel.pipeline().addFirst(SOCKS_HANDLER, new Socks5ProxyHandler(proxyAddress));
                  break;

                default:
                  throw new IllegalArgumentException("Only SOCKS4 and SOCKS5 supported at the moment.");
              }
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
    pipeline.addBefore(AHC_WS_HANDLER, WS_DECODER_HANDLER, new WebSocket08FrameDecoder(false, false, config.getWebSocketMaxFrameSize()));
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

  public ChannelPool getChannelPool() {
    return channelPool;
  }

  public EventLoopGroup getEventLoopGroup() {
    return eventLoopGroup;
  }

  public ClientStats getClientStats() {
    Map<String, Long> totalConnectionsPerHost = openChannels.stream().map(Channel::remoteAddress).filter(a -> a.getClass() == InetSocketAddress.class)
            .map(a -> (InetSocketAddress) a).map(InetSocketAddress::getHostName).collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    Map<String, Long> idleConnectionsPerHost = channelPool.getIdleChannelCountPerHost();
    Map<String, HostStats> statsPerHost = totalConnectionsPerHost.entrySet().stream().collect(Collectors.toMap(Entry::getKey, entry -> {
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
