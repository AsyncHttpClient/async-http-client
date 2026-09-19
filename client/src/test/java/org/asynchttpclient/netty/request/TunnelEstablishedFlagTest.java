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
package org.asynchttpclient.netty.request;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@code tunnelEstablished} answers "is this socket a tunnel to the origin, or still a plaintext hop to the
 * proxy?", and callers act on it by deciding whether proxy credentials may go out. It therefore has to
 * describe the CONNECT currently in flight, not one an earlier stage of the same exchange completed —
 * a 407 retry and a cross-host redirect both build a fresh CONNECT on the same future, and until that new
 * CONNECT is accepted the socket is a plaintext hop again.
 * <p>
 * Lives in this package because {@link NettyRequest}'s constructor is package-private.
 */
public class TunnelEstablishedFlagTest {

    @Test
    public void attachingAConnectClearsAPreviouslyEstablishedTunnel() {
        NettyResponseFuture<?> future = newFuture();
        future.setTunnelEstablished(true);

        future.setNettyRequest(nettyRequest(HttpMethod.CONNECT));

        assertFalse(future.isTunnelEstablished(),
                "a new CONNECT must re-arm the flag: its own answer has not been read yet");
    }

    @Test
    public void attachingANonConnectRequestLeavesTheTunnelEstablished() {
        NettyResponseFuture<?> future = newFuture();
        future.setNettyRequest(nettyRequest(HttpMethod.CONNECT));
        future.setTunnelEstablished(true);

        // The origin request that goes out through the tunnel just built must not clear it.
        future.setNettyRequest(nettyRequest(HttpMethod.GET));

        assertTrue(future.isTunnelEstablished(), "the tunnel is still up once the origin request is attached");
    }

    private static NettyResponseFuture<?> newFuture() {
        return new NettyResponseFuture<>(null, mock(AsyncHandler.class), null, 0, null, null, null);
    }

    private static NettyRequest nettyRequest(HttpMethod method) {
        return new NettyRequest(
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, "localhost:443"), null, false);
    }
}
