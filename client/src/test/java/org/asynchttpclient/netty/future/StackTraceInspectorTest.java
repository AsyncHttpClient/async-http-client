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
package org.asynchttpclient.netty.future;

import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;

import static org.asynchttpclient.test.TestUtils.findFreePort;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A refused connect must be recoverable on every JDK and transport, and nothing else reported through the
 * same wrapper may be.
 */
public class StackTraceInspectorTest {

    private static final long CONNECT_WAIT_MILLIS = 5000;

    @Test
    public void refusedNioConnectIsRecoverable() throws Exception {
        assertTrue(StackTraceInspector.recoverOnNettyDisconnectException(annotated(refusedNioConnect())));
    }

    // Native transports report a ConnectException with no sun.nio.ch frame.
    @Test
    public void refusedNativeTransportConnectIsRecoverable() {
        ConnectException refused = thrownHere("finishConnect(..) failed with error(-111): Connection refused");
        for (StackTraceElement element : refused.getStackTrace()) {
            assertFalse(element.getClassName().startsWith("sun.nio.ch"), "fixture must carry no NIO frame");
        }
        assertTrue(StackTraceInspector.recoverOnNettyDisconnectException(annotated(refused)));
    }

    // NoRouteToHostException is not a ConnectException, so only the frame probes match it. The running JDK
    // produces just one of the two frames (checkConnect up to JDK 12, pollConnect after), hence the fake stacks.
    @Test
    public void unreachablePeerReportedFromConnectCompletionIsRecoverable() {
        assertTrue(StackTraceInspector.recoverOnNettyDisconnectException(
                unreachablePeer("sun.nio.ch.SocketChannelImpl", "checkConnect")));
        assertTrue(StackTraceInspector.recoverOnNettyDisconnectException(
                unreachablePeer("sun.nio.ch.Net", "pollConnect")));
    }

    // ConnectTimeoutException extends ConnectException: retrying it would multiply the connect timeout.
    @Test
    public void connectTimeoutIsNotRecoverable() {
        assertFalse(StackTraceInspector.recoverOnNettyDisconnectException(
                new ConnectTimeoutException("connection timed out: localhost/127.0.0.1:1")));
        assertFalse(StackTraceInspector.recoverOnNettyDisconnectException(
                annotated(new ConnectTimeoutException("connection timed out: localhost/127.0.0.1:1"))));
    }

    // NettyChannelConnector wraps every failure in a ConnectException, so the wrapper's type must not count.
    @Test
    public void wrappedNonConnectFailuresAreNotRecoverable() {
        assertFalse(StackTraceInspector.recoverOnNettyDisconnectException(annotated(new BindException("Address already in use"))));
        assertFalse(StackTraceInspector.recoverOnNettyDisconnectException(annotated(new UnresolvedAddressException())));
        assertFalse(StackTraceInspector.recoverOnNettyDisconnectException(annotated(new IllegalStateException("boom"))));
        assertFalse(StackTraceInspector.recoverOnNettyDisconnectException(new ConnectException("no cause to inspect")));
    }

    @Test
    public void closedChannelIsRecoverable() {
        assertTrue(StackTraceInspector.recoverOnNettyDisconnectException(new ClosedChannelException()));
    }

    // Same shape as Netty's AnnotatedConnectException: empty stack trace, the original as cause.
    private static ConnectException annotated(Throwable cause) {
        ConnectException wrapper = new ConnectException(cause.getMessage() + ": localhost/127.0.0.1:1");
        wrapper.initCause(cause);
        wrapper.setStackTrace(new StackTraceElement[0]);
        return wrapper;
    }

    // Netty annotates it, then NettyChannelConnector wraps it in a ConnectException.
    private static ConnectException unreachablePeer(String className, String methodName) {
        NoRouteToHostException original = new NoRouteToHostException("No route to host");
        original.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(className, methodName, null, -2),
                new StackTraceElement("io.netty.channel.socket.nio.NioSocketChannel", "doFinishConnect", "NioSocketChannel.java", 330)});
        NoRouteToHostException nettyAnnotated = new NoRouteToHostException(original.getMessage() + ": localhost/127.0.0.1:1");
        nettyAnnotated.initCause(original);
        nettyAnnotated.setStackTrace(new StackTraceElement[0]);
        return annotated(nettyAnnotated);
    }

    private static ConnectException thrownHere(String message) {
        try {
            throw new ConnectException(message);
        } catch (ConnectException e) {
            return e;
        }
    }

    // A real refused non-blocking connect, so the frames are whatever this JDK produces.
    private static ConnectException refusedNioConnect() throws IOException {
        int closedPort = findFreePort();
        try (SocketChannel channel = SocketChannel.open(); Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_CONNECT);
            channel.connect(new InetSocketAddress("127.0.0.1", closedPort));
            selector.select(CONNECT_WAIT_MILLIS);
            try {
                channel.finishConnect();
            } catch (ConnectException e) {
                return e;
            }
            return fail("connect to closed port " + closedPort + " neither failed nor was refused in "
                    + CONNECT_WAIT_MILLIS + "ms");
        }
    }
}
