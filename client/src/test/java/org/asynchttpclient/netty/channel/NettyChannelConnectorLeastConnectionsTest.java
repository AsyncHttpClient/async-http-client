/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.test.TestUtils.findFreePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests the per-attempt accounting of {@link NettyChannelConnector} in
 * {@link org.asynchttpclient.LoadBalance#LEAST_CONNECTIONS} mode: every counted attempt is released exactly once,
 * however it ends.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
public class NettyChannelConnectorLeastConnectionsTest {

    private EventLoopGroup group;

    @BeforeEach
    public void setUp() {
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    @AfterEach
    public void tearDown() throws Exception {
        group.shutdownGracefully(0, 1, SECONDS).sync();
    }

    private static NettyResponseFuture<Object> newFuture(AsyncHandler<Object> handler) {
        Request request = new RequestBuilder().setUrl("http://lc.test:12345").build();
        return new NettyResponseFuture<>(request, handler, null, 0,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
    }

    private static class AttemptRecorder extends AsyncCompletionHandler<Object> {
        private final List<InetSocketAddress> attempted = new CopyOnWriteArrayList<>();

        @Override
        public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
            attempted.add(remoteAddress);
        }

        @Override
        public Object onCompleted(Response response) {
            return null;
        }
    }

    private Bootstrap nioBootstrap() {
        return new Bootstrap().group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
            }
        });
    }

    private static InetSocketAddress refusedAddress() throws Exception {
        return new InetSocketAddress(InetAddress.getByName("127.0.0.1"), findFreePort());
    }

    private static InetSocketAddress addressOf(ServerSocket server) throws Exception {
        return new InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.getLocalPort());
    }

    // Counts a connection to the address, as an attempt that selected it would.
    private static void open(LeastConnectionsAddressSelector selector, InetSocketAddress address) {
        selector.selectAndAcquire("lc.test", new ArrayList<>(List.of(address)), 0);
    }

    @Test
    public void aRefusedAttemptIsReleasedOnceThoughBothItsFailureAndItsCloseReport() throws Exception {
        InetSocketAddress first = refusedAddress();
        InetSocketAddress second = refusedAddress();
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        // One more live connection to each address. A double release would wrongly take it away.
        open(selector, first);
        open(selector, second);

        AttemptRecorder handler = new AttemptRecorder();
        NettyResponseFuture<Object> future = newFuture(handler);
        NettyChannelConnector connector = new NettyChannelConnector(null, List.of(first, second), handler, null, null,
                selector, "lc.test");
        connector.connect(nioBootstrap(), new NettyConnectListener<>(future, null, null, null));

        assertThrows(ExecutionException.class, () -> future.get(10, SECONDS));
        assertEquals(List.of(first, second), handler.attempted, "both addresses tried, in order");
        // The failed channel closes in the same event loop task that failed the request, just after it.
        drainEventLoop();
        assertEquals(1, selector.activeConnections(first));
        assertEquals(1, selector.activeConnections(second));
    }

    @Test
    public void anAttemptWhoseChannelCannotBeCreatedIsReleased() throws Exception {
        InetSocketAddress first = refusedAddress();
        InetSocketAddress second = refusedAddress();
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        open(selector, first);
        open(selector, second);

        // When channel creation fails (out of file descriptors, say), the returned channel never closes.
        ChannelFactory<Channel> noChannel = () -> {
            throw new ChannelException("no channel for you");
        };
        Bootstrap failing = new Bootstrap().group(group).channelFactory(noChannel).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
            }
        });

        AttemptRecorder handler = new AttemptRecorder();
        NettyResponseFuture<Object> future = newFuture(handler);
        NettyChannelConnector connector = new NettyChannelConnector(null, List.of(first, second), handler, null, null,
                selector, "lc.test");
        connector.connect(failing, new NettyConnectListener<>(future, null, null, null));

        assertThrows(ExecutionException.class, () -> future.get(10, SECONDS));
        assertEquals(2, handler.attempted.size());
        assertEquals(1, selector.activeConnections(first));
        assertEquals(1, selector.activeConnections(second));
    }

    @Test
    public void anAttemptRefusedByTheHandlerIsReleased() throws Exception {
        InetSocketAddress first = refusedAddress();
        InetSocketAddress second = refusedAddress();
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);

        AttemptRecorder handler = new AttemptRecorder() {
            @Override
            public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
                throw new IllegalStateException("no attempt for you");
            }
        };
        NettyResponseFuture<Object> future = newFuture(handler);
        NettyChannelConnector connector = new NettyChannelConnector(null, List.of(first, second), handler, null, null,
                selector, "lc.test");
        connector.connect(nioBootstrap(), new NettyConnectListener<>(future, null, null, null));

        assertTrue(future.isDone(), "the handler's refusal fails the request on the spot");
        assertEquals(0, selector.activeConnections(first));
        assertEquals(0, selector.activeConnections(second));
    }

    @Test
    public void anEstablishedConnectionCountsUntilItCloses() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            InetSocketAddress live = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.getLocalPort());
            LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
            CompletableFuture<Channel> connected = new CompletableFuture<>();
            CompletableFuture<Integer> countWhileOpen = new CompletableFuture<>();
            AttemptRecorder handler = new AttemptRecorder() {
                @Override
                public void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
                    countWhileOpen.complete(selector.activeConnections(live));
                    connected.complete(connection);
                }
            };
            NettyResponseFuture<Object> future = newFuture(handler);
            NettyChannelConnector connector = new NettyChannelConnector(null, List.of(live, refusedAddress()), handler,
                    null, null, selector, "lc.test");
            // With no timeouts armed on the future, the connect listener closes the connection as soon as it is up.
            connector.connect(nioBootstrap(), new NettyConnectListener<>(future, null, null, null));

            Channel channel = connected.get(10, SECONDS);
            assertEquals(1, countWhileOpen.get(10, SECONDS));
            assertTrue(channel.closeFuture().await(10, SECONDS));
            drainEventLoop();
            assertEquals(0, selector.activeConnections(live), "released by the close future");
        }
    }

    @Test
    public void aFailoverGoesToTheLeastConnectedAddressNotTriedYetAndCountsThere() throws Exception {
        try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ServerSocket idle = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            InetSocketAddress refused = refusedAddress();
            InetSocketAddress busyAddress = addressOf(busy);
            InetSocketAddress idleAddress = addressOf(idle);
            LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
            open(selector, busyAddress);

            CompletableFuture<List<Integer>> countsWhileOpen = new CompletableFuture<>();
            AttemptRecorder handler = new AttemptRecorder() {
                @Override
                public void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
                    countsWhileOpen.complete(List.of(selector.activeConnections(refused),
                            selector.activeConnections(busyAddress), selector.activeConnections(idleAddress)));
                }
            };
            NettyResponseFuture<Object> future = newFuture(handler);
            List<InetSocketAddress> given = new ArrayList<>(List.of(refused, busyAddress, idleAddress));
            NettyChannelConnector connector = new NettyChannelConnector(null, given, handler, null, null, selector, "lc.test");
            connector.connect(nioBootstrap(), new NettyConnectListener<>(future, null, null, null));

            assertEquals(List.of(0, 1, 1), countsWhileOpen.get(10, SECONDS),
                    "the refused attempt released, the connection counted where it landed");
            assertEquals(List.of(refused, idleAddress), handler.attempted,
                    "after the refused address, the one without connections, not the next in order");
            assertEquals(List.of(refused, busyAddress, idleAddress), given, "the caller's list is left as it was");
        }
    }

    @Test
    public void aFailoverTriesTheOtherFamilyFirst() throws Exception {
        InetAddress ipv6Loopback = InetAddress.getByName("::1");
        try (ServerSocket v4 = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ServerSocket v6 = bindOrSkip(ipv6Loopback)) {
            InetSocketAddress refused = refusedAddress();
            InetSocketAddress v4Address = addressOf(v4);
            InetSocketAddress v6Address = new InetSocketAddress(ipv6Loopback, v6.getLocalPort());
            LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
            open(selector, v4Address);
            open(selector, v6Address);
            open(selector, v6Address);

            CompletableFuture<InetSocketAddress> connected = new CompletableFuture<>();
            AttemptRecorder handler = new AttemptRecorder() {
                @Override
                public void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
                    connected.complete(remoteAddress);
                }
            };
            NettyChannelConnector connector = new NettyChannelConnector(null, List.of(refused, v6Address, v4Address),
                    handler, null, null, selector, "lc.test");
            connector.connect(nioBootstrap(), new NettyConnectListener<>(newFuture(handler), null, null, null));

            assertEquals(v6Address, connected.get(10, SECONDS));
            assertEquals(List.of(refused, v6Address), handler.attempted,
                    "IPv4 failed, so IPv6 goes next although it has more connections");
        }
    }

    private static ServerSocket bindOrSkip(InetAddress address) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.bind(new InetSocketAddress(address, 0), 1);
        } catch (IOException notOnThisHost) {
            socket.close();
            assumeTrue(false, address + " is not usable on this host");
        }
        return socket;
    }

    @Test
    public void anErrorOrAnUnexpectedExceptionOutOfAnAttemptIsReleased() throws Exception {
        InetSocketAddress address = refusedAddress();
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);

        AttemptRecorder crashing = new AttemptRecorder() {
            @Override
            public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
                throw new AssertionError("no attempt for you");
            }
        };
        NettyChannelConnector connector = new NettyChannelConnector(null, List.of(address, refusedAddress()), crashing,
                null, null, selector, "lc.test");
        assertThrows(AssertionError.class,
                () -> connector.connect(nioBootstrap(), new NettyConnectListener<>(newFuture(crashing), null, null, null)));
        assertEquals(0, selector.activeConnections(address));

        // A bootstrap with no handler refuses to connect with an IllegalStateException.
        Bootstrap unusable = new Bootstrap().group(group).channel(NioSocketChannel.class);
        AttemptRecorder handler = new AttemptRecorder();
        NettyChannelConnector refusing = new NettyChannelConnector(null, List.of(address, refusedAddress()), handler,
                null, null, selector, "lc.test");
        assertThrows(IllegalStateException.class,
                () -> refusing.connect(unusable, new NettyConnectListener<>(newFuture(handler), null, null, null)));
        assertEquals(List.of(address), handler.attempted);
        assertEquals(0, selector.activeConnections(address));
    }

    // Runs after whatever the event loop is doing now, including the listeners of a close it has just performed.
    private void drainEventLoop() throws Exception {
        group.next().submit(() -> {
        }).get(10, SECONDS);
    }
}
