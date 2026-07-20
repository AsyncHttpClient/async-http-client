/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
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
import org.asynchttpclient.netty.handler.Http2Handler;
import org.asynchttpclient.netty.handler.Http2PingHandler;
import org.asynchttpclient.netty.handler.HttpHandler;
import org.asynchttpclient.netty.handler.WebSocketHandler;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.ssl.DefaultSslEngineFactory;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.uri.Uri;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    public static final String HTTP2_FRAME_CODEC = "http2-frame-codec";
    public static final String HTTP2_MULTIPLEX = "http2-multiplex";
    public static final String AHC_HTTP2_HANDLER = "ahc-http2";
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelManager.class);
    // Guards the one-time WARN emitted when a native transport was requested but is unavailable and we
    // fall back to NIO. Logged once per JVM to avoid spamming logs when many clients are created.
    private static final AtomicBoolean NATIVE_FALLBACK_WARNED = new AtomicBoolean();
    private final AsyncHttpClientConfig config;
    private final SslEngineFactory sslEngineFactory;
    private final EventLoopGroup eventLoopGroup;
    private final boolean allowReleaseEventLoopGroup;
    private final Bootstrap httpBootstrap;
    private final Bootstrap wsBootstrap;
    // Channel options, resolved from config once at construction, applied to each channel from the channel
    // initializer instead of via Bootstrap#option to avoid Netty's synchronized per-connect options map (issue #2218).
    private final Map.Entry<ChannelOption<?>, Object>[] channelOptions;
    private final long handshakeTimeout;
    private final @Nullable AddressResolverGroup<InetSocketAddress> addressResolverGroup;

    private final ChannelPool channelPool;
    private final ChannelGroup openChannels;
    // HTTP/2 registry, grouped by per-host base key so a permit-starved round-robin request can find a
    // sibling-IP connection (issue #2214). Outer key: the per-host base partition key. Inner key: the full
    // partition key actually registered — the per-IP RoundRobinPartitionKey in LoadBalance.ROUND_ROBIN
    // mode, or the plain base key otherwise (then the inner map holds a single entry under that key).
    private final ConcurrentHashMap<Object, ConcurrentHashMap<Object, Channel>> http2Connections = new ConcurrentHashMap<>();
    // Requests that could not acquire a connection permit and are waiting — off the event loop, WITHOUT
    // blocking their caller thread — for a sibling HTTP/2 connection to the same origin to be registered so
    // they can multiplex onto it. Keyed by the per-host base key ({@link #baseKeyOf} of the registration
    // partition key), so a request pinned to one IP is woken by a connection registered for ANY IP of the
    // host and can multiplex onto that sibling (issue #2214). Each waiter is invoked with the registered
    // channel when one appears, or with {@code null} when the client is closing so it can fail its request
    // rather than hang (its request-timeout is not scheduled yet at this point). See NettyRequestSender's
    // HTTP/2 deferral.
    private final ConcurrentHashMap<Object, Set<Consumer<Channel>>> http2ConnectionWaiters = new ConcurrentHashMap<>();
    // Set once, permanently, when the client closes and sweeps its waiters (failHttp2ConnectionWaiters). Read
    // by addHttp2ConnectionWaiter to fail-closed against a request that arms a waiter in the window between the
    // sweep and nettyTimer.stop() — such a waiter would otherwise be neither woken nor timed out, hanging its
    // request forever. See addHttp2ConnectionWaiter and NettyRequestSender's Http2ConnectionWaiter.arm().
    private volatile boolean waitersClosed;

    private AsyncHttpClientHandler wsHandler;
    private Http2Handler http2Handler;

    private boolean isInstanceof(Object object, String name) {
        final Class<?> clazz;
        try {
            clazz = Class.forName(name, false, getClass().getClassLoader());
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
            } else if (isInstanceof(eventLoopGroup, "io.netty.channel.uring.IOUringEventLoopGroup")) {
                transportFactory = new IoUringTransportFactory();
            } else {
                throw new IllegalArgumentException("Unknown event loop group " + eventLoopGroup.getClass().getSimpleName());
            }
        }

        channelOptions = buildChannelOptions(config);
        httpBootstrap = newBootstrap(transportFactory, eventLoopGroup);
        wsBootstrap = newBootstrap(transportFactory, eventLoopGroup);

        // Use the address resolver group from config if provided; otherwise null (legacy per-request resolution)
        addressResolverGroup = config.getAddressResolverGroup();
    }

    private static TransportFactory<? extends Channel, ? extends EventLoopGroup> getNativeTransportFactory(AsyncHttpClientConfig config) {
        // If we are running on macOS then use KQueue
        if (PlatformDependent.isOsx()) {
            if (KQueueTransportFactory.isAvailable()) {
                return new KQueueTransportFactory();
            }
        }

        // If we're not running on Windows then we're probably running on Linux.
        // We will check if Io_Uring is available or not. If available, return IoUringTransportFactory.
        // Else
        // We will check if Epoll is available or not. If available, return EpollTransportFactory.
        // If none of these match then no native transport is available; instead of failing client
        // construction we degrade gracefully to NIO (which is always available) and warn once.
        if (!PlatformDependent.isWindows()) {
            if (IoUringTransportFactory.isAvailable() && !config.isUseOnlyEpollNativeTransport()) {
                return new IoUringTransportFactory();
            } else if (EpollTransportFactory.isAvailable()) {
                return new EpollTransportFactory();
            }
        }

        // No suitable native transport (Epoll, Io_Uring or KQueue) on this platform. Native transport was
        // requested but cannot be honored (e.g. Windows, a minimal image without the native libs, or the
        // native library failed to load), so fall back to the portable NIO transport rather than throwing.
        if (NATIVE_FALLBACK_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("Native transport requested (useNativeTransport=true) but no native transport "
                    + "(Epoll, Io_Uring or KQueue) is available on this platform; falling back to NIO.");
        }
        return NioTransportFactory.INSTANCE;
    }

    public static boolean isSslHandlerConfigured(ChannelPipeline pipeline) {
        return pipeline.get(SSL_HANDLER) != null;
    }

    private static Bootstrap newBootstrap(ChannelFactory<? extends Channel> channelFactory, EventLoopGroup eventLoopGroup) {
        // Channel options are intentionally NOT set on the Bootstrap. Netty's AbstractBootstrap applies them
        // per-connect by copying the shared options map under "synchronized (options)" in newOptionsArray(),
        // which serializes every outbound connection on a single monitor (issue #2218). Instead, we apply the
        // pre-resolved options to each Channel from the channel initializer via Channel.config(), keeping the
        // Bootstrap options map empty and removing that global lock from the connect path.
        return new Bootstrap().channelFactory(channelFactory).group(eventLoopGroup);
    }

    /**
     * Resolves the configured {@link ChannelOption}s from the client config exactly once. Values and conditional
     * options (connect timeout, SO_LINGER, buffer sizes) are computed here so the per-connection path only iterates
     * a fixed array and never re-reads the config.
     */
    @SuppressWarnings("unchecked")
    private static Map.Entry<ChannelOption<?>, Object>[] buildChannelOptions(AsyncHttpClientConfig config) {
        Map<ChannelOption<?>, Object> options = new LinkedHashMap<>();
        options.put(ChannelOption.ALLOCATOR, config.getAllocator() != null ? config.getAllocator() : ByteBufAllocator.DEFAULT);
        options.put(ChannelOption.TCP_NODELAY, config.isTcpNoDelay());
        options.put(ChannelOption.SO_REUSEADDR, config.isSoReuseAddress());
        options.put(ChannelOption.SO_KEEPALIVE, config.isSoKeepAlive());
        options.put(ChannelOption.AUTO_CLOSE, false);

        long connectTimeout = config.getConnectTimeout().toMillis();
        if (connectTimeout > 0) {
            connectTimeout = Math.min(connectTimeout, Integer.MAX_VALUE);
            options.put(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout);
        }

        if (config.getSoLinger() >= 0) {
            options.put(ChannelOption.SO_LINGER, config.getSoLinger());
        }

        if (config.getSoSndBuf() >= 0) {
            options.put(ChannelOption.SO_SNDBUF, config.getSoSndBuf());
        }

        if (config.getSoRcvBuf() >= 0) {
            options.put(ChannelOption.SO_RCVBUF, config.getSoRcvBuf());
        }

        // User-supplied options last so they can override the defaults above, matching the previous Bootstrap order.
        options.putAll(config.getChannelOptions());

        return options.entrySet().toArray(new Map.Entry[0]);
    }

    /**
     * Applies the pre-resolved channel options to a freshly created channel. Invoked from the channel initializer
     * (once per connection, on the channel's event loop, before the channel is connected), mirroring what
     * {@link Bootstrap#option} would otherwise do but without the shared, synchronized options map.
     * <p>
     * The per-option handling mirrors Netty's {@code AbstractBootstrap#setChannelOption}: an unknown option is
     * warned about and skipped, and a failure to set an option is warned about and rethrown so the channel is
     * closed rather than connecting with a half-applied configuration.
     */
    @SuppressWarnings("unchecked")
    private void applyChannelOptions(Channel channel) {
        ChannelConfig channelConfig = channel.config();
        for (Map.Entry<ChannelOption<?>, Object> option : channelOptions) {
            ChannelOption<Object> key = (ChannelOption<Object>) option.getKey();
            try {
                if (!channelConfig.setOption(key, option.getValue())) {
                    LOGGER.warn("Unknown channel option '{}' for channel '{}'", key, channel);
                }
            } catch (Throwable t) {
                LOGGER.warn("Failed to set channel option '{}' with value '{}' for channel '{}'", key, option.getValue(), channel, t);
                throw t;
            }
        }
    }

    public void configureBootstraps(NettyRequestSender requestSender) {
        final AsyncHttpClientHandler httpHandler = new HttpHandler(config, this, requestSender);
        wsHandler = new WebSocketHandler(config, this, requestSender);
        http2Handler = new Http2Handler(config, this, requestSender);

        httpBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                applyChannelOptions(ch);

                ChannelPipeline pipeline = ch.pipeline()
                        .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec());

                if (config.isEnableAutomaticDecompression()) {
                    // Add automatic decompression if desired
                    pipeline = pipeline.addLast(INFLATER_HANDLER, newHttpContentDecompressor());
                }

                pipeline = pipeline
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
                applyChannelOptions(ch);

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

    /**
     * The per-host base partition key under which an HTTP/2 connection registered with {@code partitionKey}
     * is grouped. For a {@link RoundRobinPartitionKey} that is its base (without the pinned IP); for any
     * other key the key is already the base.
     */
    private static Object baseKeyOf(Object partitionKey) {
        return partitionKey instanceof RoundRobinPartitionKey
                ? ((RoundRobinPartitionKey) partitionKey).getBaseKey()
                : partitionKey;
    }

    /**
     * Registers an HTTP/2 connection in the registry for the given partition key.
     * The connection stays in the registry (not the regular pool) to allow multiplexing —
     * multiple requests can share the same connection concurrently.
     */
    public void registerHttp2Connection(Object partitionKey, Channel channel) {
        Http2ConnectionState state = channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
        if (state != null) {
            state.setPartitionKey(partitionKey);
        }
        // Coalesce connections opened concurrently for the same partition (thundering herd): keep the
        // first live one canonical in the registry. A connection that lost the race is marked redundant,
        // so it serves only its own opening request and then closes (its stream's closeFuture listener
        // closes the parent once activeStreams hits 0) instead of lingering open and unregistered. A dead
        // registered entry is replaced.
        //
        // The whole coalescing mutation runs inside compute() on the outer base-key bucket so it is atomic
        // against removeHttp2Connection() pruning an emptied inner map — both hold the same bucket lock,
        // which closes the race where a prune could orphan a connection inserted into a detached inner map.
        http2Connections.compute(baseKeyOf(partitionKey), (k, byKey) -> {
            if (byKey == null) {
                byKey = new ConcurrentHashMap<>();
            }
            Channel existing = byKey.putIfAbsent(partitionKey, channel);
            if (existing != null && existing != channel) {
                boolean replacedDead = !existing.isActive() && byKey.replace(partitionKey, existing, channel);
                if (!replacedDead && state != null) {
                    state.markRedundant();
                }
            }
            return byKey;
        });
        // When the connection closes, remove it from the registry AND fail any requests still queued
        // for a stream slot. Without the latter, requests sitting in pendingOpeners when the parent
        // connection drops have no stream channel (so no channelInactive is ever delivered for them)
        // and would survive only until the request timeout fires — the silent-timeout bug of #2160.
        //
        // This listener MUST stay outside the compute() lambda above: a channel already closed at
        // registration time fires it synchronously on this thread, and re-entering computeIfPresent() on
        // the same outer bucket from within compute() would self-deadlock. Here compute() has returned.
        channel.closeFuture().addListener(future -> {
            removeHttp2Connection(partitionKey, channel);
            if (state != null) {
                state.failPendingOpeners(orphan -> failOrphanedH2Opener(orphan,
                        "HTTP/2 connection closed before a stream could be opened"));
            }
        });

        // Wake any requests parked waiting for an HTTP/2 connection to this origin (they failed to acquire a
        // connection permit and can multiplex onto this one without one). Wake with the currently-registered
        // canonical connection — which may be an already-registered one this call lost the race to — so a
        // "redundant" duplicate still lets the waiters resume onto the live connection.
        Channel registered = pollHttp2Connection(partitionKey);
        if (registered != null) {
            wakeHttp2ConnectionWaiters(partitionKey, registered);
        }
    }

    /**
     * Registers a one-shot waiter to be invoked when an HTTP/2 connection is registered for the same host
     * (or with {@code null} on client close). See the {@link #http2ConnectionWaiters} field and
     * NettyRequestSender's HTTP/2 deferral. Waiters are grouped by the per-host base key ({@link #baseKeyOf}),
     * NOT the full per-IP partition key: in round-robin mode a permit-starved request pinned to one IP must
     * be woken by a connection that registers for ANY IP of the host so it can multiplex onto that sibling
     * (issue #2214) — the per-IP registration key on its own would never wake it. The waiter must be
     * idempotent — it may be invoked by a registration, by the client-close sweep, or removed and invoked by
     * its own timeout concurrently.
     *
     * @return {@code true} if the waiter was registered; {@code false} if the client is already closing, in
     *         which case it is NOT registered and the caller must fail its request immediately rather than
     *         arm a timeout (no connection will register and the timer that would fire the deadline is being
     *         stopped). This closes the window where a request arms a waiter between the
     *         {@link #failHttp2ConnectionWaiters()} sweep and {@code nettyTimer.stop()} and then hangs forever.
     */
    public boolean addHttp2ConnectionWaiter(Object partitionKey, Consumer<Channel> onConnection) {
        http2ConnectionWaiters.computeIfAbsent(baseKeyOf(partitionKey), k -> ConcurrentHashMap.newKeySet()).add(onConnection);
        // Recheck after adding: failHttp2ConnectionWaiters sets waitersClosed BEFORE sweeping, so a waiter that
        // is not caught by the sweep (added into a bucket the sweep already passed, or a bucket it recreated)
        // observes the flag here and unregisters itself. Every waiter is therefore either swept or fails-closed.
        if (waitersClosed) {
            removeHttp2ConnectionWaiter(partitionKey, onConnection);
            return false;
        }
        return true;
    }

    public void removeHttp2ConnectionWaiter(Object partitionKey, Consumer<Channel> onConnection) {
        // Prune an emptied set under the bucket lock so an origin whose deferred requests only ever time out
        // (a saturated or unreachable host that never registers an H2 connection) does not retain one empty
        // Set per host for the client's lifetime. computeIfPresent serializes with a concurrent
        // addHttp2ConnectionWaiter on the same key; a racing add that recreates the set is re-covered by the
        // waiter's own re-poll in arm(), so no wakeup is lost.
        http2ConnectionWaiters.computeIfPresent(baseKeyOf(partitionKey), (k, waiters) -> {
            waiters.remove(onConnection);
            return waiters.isEmpty() ? null : waiters;
        });
    }

    private void wakeHttp2ConnectionWaiters(Object partitionKey, Channel channel) {
        Set<Consumer<Channel>> waiters = http2ConnectionWaiters.remove(baseKeyOf(partitionKey));
        if (waiters != null) {
            for (Consumer<Channel> waiter : waiters) {
                notifyHttp2ConnectionWaiter(waiter, channel);
            }
        }
    }

    private void failHttp2ConnectionWaiters() {
        // Mark closed before the sweep so a waiter arming concurrently is either swept below or fails-closed in
        // addHttp2ConnectionWaiter — it can never remain armed-but-never-notified.
        waitersClosed = true;
        for (Object key : http2ConnectionWaiters.keySet()) {
            Set<Consumer<Channel>> waiters = http2ConnectionWaiters.remove(key);
            if (waiters != null) {
                for (Consumer<Channel> waiter : waiters) {
                    notifyHttp2ConnectionWaiter(waiter, null);
                }
            }
        }
    }

    // Invoking a waiter drives a full request send (writeRequest). Isolate failures: one waiter throwing must
    // not starve the remaining waiters, and — since wakeHttp2ConnectionWaiters runs inside
    // registerHttp2Connection, on the establishing connection's onSuccess path — must not propagate out and
    // abort that request's own semaphore release and writeRequest (which would silently leak a connection
    // permit and hang the establishing request).
    private static void notifyHttp2ConnectionWaiter(Consumer<Channel> waiter, Channel channel) {
        try {
            waiter.accept(channel);
        } catch (Throwable t) {
            LOGGER.warn("HTTP/2 connection waiter failed", t);
        }
    }

    /**
     * Fails a request that was queued in {@link Http2ConnectionState} waiting for a stream slot but can
     * never get one (the connection dropped or started draining). Releases its request body first —
     * {@code sendHttp2Frames} never ran for it, so nothing else will — to avoid leaking the body ByteBuf.
     * {@code NettyRequest.release()} is idempotent, so this is safe even if another path also releases.
     */
    private static void failOrphanedH2Opener(NettyResponseFuture<?> orphan, String message) {
        if (orphan.getNettyRequest() != null) {
            orphan.getNettyRequest().release();
        }
        orphan.abort(new IOException(message));
    }

    /**
     * Removes an HTTP/2 connection from the registry, but only if it's the currently registered
     * connection for that partition key (avoids removing a replacement connection). The emptied per-host
     * inner map is pruned atomically — computeIfPresent serializes with registerHttp2Connection's
     * compute() on the same outer bucket, so a concurrent insert cannot be lost to the prune.
     */
    public void removeHttp2Connection(Object partitionKey, Channel channel) {
        http2Connections.computeIfPresent(baseKeyOf(partitionKey), (k, byKey) -> {
            byKey.remove(partitionKey, channel);
            return byKey.isEmpty() ? null : byKey;
        });
    }

    /**
     * Returns an active, non-draining HTTP/2 connection for the given partition key, or {@code null}.
     * Unlike the regular pool, this does NOT remove the connection — it remains available for
     * concurrent multiplexed requests.
     */
    public Channel pollHttp2Connection(Object partitionKey) {
        ConcurrentHashMap<Object, Channel> byKey = http2Connections.get(baseKeyOf(partitionKey));
        if (byKey == null) {
            return null;
        }
        Channel channel = byKey.get(partitionKey);
        if (channel == null) {
            return null;
        }
        if (!channel.isActive()) {
            removeHttp2Connection(partitionKey, channel);
            return null;
        }
        Http2ConnectionState state = channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
        if (state != null && state.isDraining()) {
            return null;
        }
        return channel;
    }

    /**
     * Round-robin permit-starved fallback (issue #2214): returns an active, non-draining HTTP/2 connection
     * open to ANY IP of the host identified by {@code baseKey}, or {@code null} if none qualifies. Used only
     * by {@link org.asynchttpclient.netty.request.NettyRequestSender} when a request pinned to one IP cannot
     * acquire a per-host connection permit ({@code maxConnectionsPerHost}) and its own per-IP connection does
     * not exist — it may then multiplex onto a sibling-IP connection instead of failing. NOT used on the
     * happy path, which polls the exact per-IP key so load keeps spreading across IPs.
     *
     * <p>Iterates only the inner map for this host (bounded by the host's resolved-IP count). Per-key
     * validation and dead-entry eviction are delegated to {@link #pollHttp2Connection}; redundant
     * coalescing-losers are never stored, so they are never returned.
     */
    public Channel pollHttp2SiblingConnection(Object baseKey) {
        ConcurrentHashMap<Object, Channel> byKey = http2Connections.get(baseKey);
        if (byKey == null) {
            return null;
        }
        for (Object key : byKey.keySet()) {
            Channel channel = pollHttp2Connection(key);
            if (channel != null) {
                return channel;
            }
        }
        return null;
    }

    /**
     * Polls for an HTTP/2 connection by URI/virtualHost/proxy, using the same partition key logic
     * as the regular pool. Returns the connection without removing it from the registry.
     *
     * @deprecated no longer used internally. Compute the partition key at the call site — from the request
     * being dispatched, so it stays correct on the filter-replay path — and call
     * {@link #pollHttp2Connection(Object)}. Kept for binary compatibility; slated for removal in the next
     * major release.
     */
    @Deprecated
    public Channel pollHttp2(Uri uri, String virtualHost, ProxyServer proxy, ChannelPoolPartitioning connectionPoolPartitioning) {
        Object partitionKey = connectionPoolPartitioning.getPartitionKey(uri, virtualHost, proxy);
        return pollHttp2Connection(partitionKey);
    }

    /**
     * @deprecated no longer used internally. Compute the partition key at the call site — from the request
     * being dispatched, so it stays correct on the filter-replay path — and call {@link #poll(Object)}.
     * Kept for binary compatibility; slated for removal in the next major release.
     */
    @Deprecated
    public Channel poll(Uri uri, String virtualHost, ProxyServer proxy, ChannelPoolPartitioning connectionPoolPartitioning) {
        Object partitionKey = connectionPoolPartitioning.getPartitionKey(uri, virtualHost, proxy);
        return channelPool.poll(partitionKey);
    }

    public Channel poll(Object partitionKey) {
        return channelPool.poll(partitionKey);
    }

    public void removeAll(Channel connection) {
        channelPool.removeAll(connection);
    }

    private void doClose() {
        http2Connections.clear();
        ChannelGroupFuture groupFuture = openChannels.close();
        channelPool.destroy();
        groupFuture.addListener(future -> sslEngineFactory.destroy());
    }

    public void close() {
        // Fail any requests parked waiting for a sibling HTTP/2 connection to register (see the
        // http2ConnectionWaiters field): the client is closing, so no connection will arrive and their
        // request-timeout backstop is not scheduled yet. Do this synchronously up front — doClose() only
        // runs after the (possibly long) graceful EventLoopGroup shutdown, and the nettyTimer that would
        // otherwise fire their deadline is being stopped in parallel.
        failHttp2ConnectionWaiters();
        // Close the resolver group first while the EventLoopGroup is still active,
        // since Netty DNS resolvers may need a live EventLoop for clean shutdown.
        if (addressResolverGroup != null) {
            addressResolverGroup.close();
        }
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

    private SslHandler createSslHandler(String peerHost, int peerPort, boolean http2Allowed) {
        SSLEngine sslEngine = sslEngineFactory.newSslEngine(config, peerHost, peerPort, http2Allowed);
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
            // For HTTPS targets, we always need to add/replace the SSL handler for the target connection
            // even if there's already an SSL handler in the pipeline (which would be for an HTTPS proxy)
            if (isSslHandlerConfigured(pipeline)) {
                // Remove existing SSL handler (for proxy) and replace with SSL handler for target
                pipeline.remove(SSL_HANDLER);
            }
            SslHandler sslHandler = createSslHandler(requestUri.getHost(), requestUri.getExplicitPort(), !requestUri.isWebSocket());
            whenHandshaked = sslHandler.handshakeFuture();
            pipeline.addBefore(INFLATER_HANDLER, SSL_HANDLER, sslHandler);
            pipeline.addAfter(SSL_HANDLER, HTTP_CLIENT_CODEC, newHttpClientCodec());

        } else {
            // For HTTP targets, remove any existing SSL handler (from HTTPS proxy) since target is not secured
            if (isSslHandlerConfigured(pipeline)) {
                pipeline.remove(SSL_HANDLER);
            }
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

    public Future<Channel> updatePipelineForHttpsTunneling(ChannelPipeline pipeline, Uri requestUri, ProxyServer proxyServer) {
        Future<Channel> whenHandshaked = null;

        // Remove HTTP codec as tunnel is established
        if (pipeline.get(HTTP_CLIENT_CODEC) != null) {
            pipeline.remove(HTTP_CLIENT_CODEC);
        }

        if (requestUri.isSecured()) {
            // For HTTPS proxy to HTTPS target, we need to establish target SSL over the proxy SSL tunnel
            // The proxy SSL handler should remain as it provides the tunnel transport
            // We need to add target SSL handler that will negotiate with the target through the tunnel
            
            SslHandler sslHandler = createSslHandler(requestUri.getHost(), requestUri.getExplicitPort(), !requestUri.isWebSocket());
            whenHandshaked = sslHandler.handshakeFuture();

            // For HTTPS proxy tunnel, add target SSL handler after the existing proxy SSL handler
            // This creates a nested SSL setup: Target SSL -> Proxy SSL -> Network
            if (isSslHandlerConfigured(pipeline)) {
                // Insert target SSL handler after the proxy SSL handler
                pipeline.addAfter(SSL_HANDLER, "target-ssl", sslHandler);
            } else {
                // This shouldn't happen for HTTPS proxy, but fallback
                pipeline.addBefore(INFLATER_HANDLER, SSL_HANDLER, sslHandler);
            }
            
            pipeline.addAfter("target-ssl", HTTP_CLIENT_CODEC, newHttpClientCodec());

        } else {
            // For HTTPS proxy to HTTP target, just add HTTP codec
            // The proxy SSL handler provides the tunnel and remains
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

        // A WebSocket connection must not negotiate h2 (no RFC 8441 support), so advertise only http/1.1 in ALPN.
        SslHandler sslHandler = createSslHandler(peerHost, peerPort, !uri.isWebSocket());
        // Check if SOCKS handler actually exists in the pipeline before trying to add after it
        if (hasSocksProxyHandler && pipeline.get(SOCKS_HANDLER) != null) {
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

            if (addressResolverGroup != null) {
                // Use the address resolver group for async, non-blocking proxy host resolution
                InetSocketAddress unresolvedProxyAddress = InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort());
                AddressResolver<InetSocketAddress> resolver = addressResolverGroup.getResolver(eventLoopGroup.next());
                resolver.resolve(unresolvedProxyAddress).addListener((Future<InetSocketAddress> whenProxyAddress) -> {
                    if (whenProxyAddress.isSuccess()) {
                        configureSocksBootstrap(socksBootstrap, httpBootstrapHandler, whenProxyAddress.get(), proxy, promise);
                    } else {
                        promise.setFailure(whenProxyAddress.cause());
                    }
                });
            } else {
                nameResolver.resolve(proxy.getHost()).addListener((Future<InetAddress> whenProxyAddress) -> {
                    if (whenProxyAddress.isSuccess()) {
                        InetSocketAddress proxyAddress = new InetSocketAddress(whenProxyAddress.get(), proxy.getPort());
                        configureSocksBootstrap(socksBootstrap, httpBootstrapHandler, proxyAddress, proxy, promise);
                    } else {
                        promise.setFailure(whenProxyAddress.cause());
                    }
                });
            }

        } else if (proxy != null && ProxyType.HTTPS.equals(proxy.getProxyType())) {
            // For HTTPS proxies, use HTTP bootstrap but ensure SSL connection to proxy
            // The SSL handler for connecting to the proxy will be added in the connect phase
            promise.setSuccess(httpBootstrap);
        } else {
            promise.setSuccess(httpBootstrap);
        }

        return promise;
    }

    private void configureSocksBootstrap(Bootstrap socksBootstrap, ChannelHandler httpBootstrapHandler,
                                          InetSocketAddress proxyAddress, ProxyServer proxy, Promise<Bootstrap> promise) {
        socksBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {
                channel.pipeline().addLast(httpBootstrapHandler);

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
    }

    /**
     * Checks whether the given channel is an HTTP/2 connection (i.e. has the HTTP/2 multiplex handler installed).
     */
    public static boolean isHttp2(Channel channel) {
        return channel.pipeline().get(HTTP2_MULTIPLEX) != null;
    }

    /**
     * Checks whether the given channel is an HTTP/2 stream child channel.
     * Stream channels are single-use and don't support HTTP/1.1 operations like draining or pipeline modification.
     */
    public static boolean isHttp2StreamChannel(Channel channel) {
        return channel instanceof Http2StreamChannel;
    }

    /**
     * Returns the shared {@link Http2Handler} instance for use with stream child channels.
     */
    public Http2Handler getHttp2Handler() {
        return http2Handler;
    }

    /**
     * Upgrades the pipeline from HTTP/1.1 to HTTP/2 after ALPN negotiates "h2".
     * Removes HTTP/1.1 handlers and adds {@link Http2FrameCodec} + {@link Http2MultiplexHandler}.
     * The per-stream {@link Http2Handler} is added separately on each stream child channel.
     */
    public void upgradePipelineToHttp2(ChannelPipeline pipeline) {
        // Remove HTTP/1.1 specific handlers
        if (pipeline.get(HTTP_CLIENT_CODEC) != null) {
            pipeline.remove(HTTP_CLIENT_CODEC);
        }
        if (pipeline.get(INFLATER_HANDLER) != null) {
            pipeline.remove(INFLATER_HANDLER);
        }
        if (pipeline.get(CHUNKED_WRITER_HANDLER) != null) {
            pipeline.remove(CHUNKED_WRITER_HANDLER);
        }
        if (pipeline.get(AHC_HTTP_HANDLER) != null) {
            pipeline.remove(AHC_HTTP_HANDLER);
        }

        // Add HTTP/2 frame codec (handles connection preface, SETTINGS, PING, flow control, etc.)
        Http2Settings settings = new Http2Settings()
                .initialWindowSize(config.getHttp2InitialWindowSize())
                .maxFrameSize(config.getHttp2MaxFrameSize())
                .headerTableSize(config.getHttp2HeaderTableSize())
                .maxHeaderListSize(config.getHttp2MaxHeaderListSize())
                // RFC 9113 §8.4: AsyncHttpClient never consumes server push, so advertise ENABLE_PUSH=0.
                // A conformant server then never opens push streams; without this the client relies on
                // Netty's default and a pushing server could trip a connection-level PROTOCOL_ERROR.
                .pushEnabled(false);

        Http2FrameCodec frameCodec = Http2FrameCodecBuilder.forClient()
                .initialSettings(settings)
                .build();

        // Http2MultiplexHandler creates a child channel per HTTP/2 stream.
        // Server-push streams are rejected with RST_STREAM(REFUSED_STREAM).
        Http2MultiplexHandler multiplexHandler = new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                // Reject server push by sending RST_STREAM(REFUSED_STREAM)
                ch.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM))
                        .addListener(f -> ch.close());
            }
        });

        pipeline.addLast(HTTP2_FRAME_CODEC, frameCodec);
        pipeline.addLast(HTTP2_MULTIPLEX, multiplexHandler);

        // Attach HTTP/2 connection state for MAX_CONCURRENT_STREAMS tracking and GOAWAY draining
        Http2ConnectionState state = new Http2ConnectionState();
        int configMaxStreams = config.getHttp2MaxConcurrentStreams();
        if (configMaxStreams > 0) {
            // Client's own cap; the server-advertised value (applied by the http2-settings-listener below)
            // can only lower the effective limit, never raise it above this.
            state.setClientMaxConcurrentStreams(configMaxStreams);
        }
        pipeline.channel().attr(Http2ConnectionState.HTTP2_STATE_KEY).set(state);

        // Install SETTINGS listener to update MAX_CONCURRENT_STREAMS from server
        pipeline.addLast("http2-settings-listener", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof Http2SettingsFrame) {
                    Http2SettingsFrame settingsFrame = (Http2SettingsFrame) msg;
                    Long maxStreams = settingsFrame.settings().maxConcurrentStreams();
                    if (maxStreams != null) {
                        Http2ConnectionState connState = ctx.channel().attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
                        if (connState != null) {
                            connState.updateMaxConcurrentStreams(maxStreams.intValue());
                        }
                    }
                }
                ctx.fireChannelRead(msg);
            }
        });

        // Install GOAWAY handler on the parent channel to mark the connection as draining
        // and remove it from the HTTP/2 registry. GOAWAY is a connection-level frame that
        // arrives on the parent channel, not on stream child channels.
        pipeline.addLast("http2-goaway-listener", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof Http2GoAwayFrame) {
                    Http2GoAwayFrame goAwayFrame = (Http2GoAwayFrame) msg;
                    int lastStreamId = goAwayFrame.lastStreamId();
                    Http2ConnectionState connState = ctx.channel().attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
                    if (connState != null) {
                        connState.setDraining(lastStreamId);
                        Object pk = connState.getPartitionKey();
                        if (pk != null) {
                            removeHttp2Connection(pk, ctx.channel());
                        }
                        // Free the round-robin per-host permit at drain start instead of at channel close.
                        // A draining connection rejects new streams but can stay open while in-flight
                        // streams finish, and holding the permit that whole time blocks a replacement
                        // connection (issue #2214). Once-only: the closeFuture release becomes a no-op,
                        // and in DEFAULT mode no hook is installed so this does nothing.
                        connState.releasePermitOnce();
                        // Fail requests still queued for a stream slot: a draining connection accepts no
                        // new streams, so they can never be opened here and would otherwise wait until the
                        // connection finally closes. Fail them now so they retry on a fresh connection (the
                        // registry no longer offers this one). Already-open streams below lastStreamId are
                        // untouched and complete normally. #12
                        connState.failPendingOpeners(orphan -> failOrphanedH2Opener(orphan,
                                "HTTP/2 connection received GOAWAY; request must retry on a new connection"));
                    }
                    LOGGER.debug("HTTP/2 GOAWAY received on {}, lastStreamId={}, errorCode={}",
                            ctx.channel(), lastStreamId, goAwayFrame.errorCode());
                    // Close the connection when no more active streams
                    if (connState != null && connState.getActiveStreams() <= 0) {
                        closeChannel(ctx.channel());
                    }
                }
                ctx.fireChannelRead(msg);
            }
        });

        // Install PING handler for keepalive if configured
        long pingIntervalMs = config.getHttp2PingInterval().toMillis();
        if (pingIntervalMs > 0) {
            pipeline.addLast("http2-idle-state", new IdleStateHandler(0, 0, pingIntervalMs, TimeUnit.MILLISECONDS));
            pipeline.addLast("http2-ping", new Http2PingHandler());
        }
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

    /**
     * Return the {@link AddressResolverGroup} used for async DNS resolution, or {@code null}
     * if per-request name resolvers should be used (legacy behavior).
     */
    public @Nullable AddressResolverGroup<InetSocketAddress> getAddressResolverGroup() {
        return addressResolverGroup;
    }

    /**
     * Builds a point-in-time stats snapshot in O(open channels + idle pooled channels).
     */
    public ClientStats getClientStats() {
        Map<String, ConnectionCounts> connectionsPerHost = new HashMap<>();
        for (Channel channel : openChannels) {
            SocketAddress remoteAddress = channel.remoteAddress();
            if (remoteAddress instanceof InetSocketAddress) {
                String host = ((InetSocketAddress) remoteAddress).getHostString();
                ConnectionCounts counts = connectionsPerHost.get(host);
                if (counts == null) {
                    counts = new ConnectionCounts();
                    connectionsPerHost.put(host, counts);
                }
                counts.totalConnectionCount++;
            }
        }

        for (Entry<String, Long> entry : channelPool.getIdleChannelCountPerHost().entrySet()) {
            ConnectionCounts counts = connectionsPerHost.get(entry.getKey());
            if (counts != null) {
                counts.idleConnectionCount = entry.getValue();
            }
        }

        Map<String, HostStats> statsPerHost = new HashMap<>(connectionsPerHost.size());
        for (Entry<String, ConnectionCounts> entry : connectionsPerHost.entrySet()) {
            ConnectionCounts counts = entry.getValue();
            statsPerHost.put(entry.getKey(), new HostStats(
                    counts.totalConnectionCount - counts.idleConnectionCount,
                    counts.idleConnectionCount));
        }
        return new ClientStats(statsPerHost);
    }

    private static final class ConnectionCounts {

        private long totalConnectionCount;
        private long idleConnectionCount;
    }

    public boolean isOpen() {
        return channelPool.isOpen();
    }

    public boolean isHttp2CleartextEnabled() {
        return config.isHttp2Enabled() && config.isHttp2CleartextEnabled();
    }
}
