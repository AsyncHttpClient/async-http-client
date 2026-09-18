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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An HTTP/2 setting Netty will reject has to be rejected where the client is built, not where a connection is
 * upgraded. The upgrade runs inside a Netty future listener, which logs whatever escapes it and carries on, so
 * a throw there reaches nobody: the request waits out its timeout instead of failing.
 * <p>
 * These pin both halves. The config constructor refuses what Netty would refuse, and the upgrade builds
 * everything that can fail before touching the pipeline, so a connection is never left stripped of its
 * HTTP/1.1 handlers with no HTTP/2 handlers to replace them.
 */
class Http2ConfigValidationTest {

    private Timer timer;
    private ChannelManager channelManager;
    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
        if (channelManager != null) {
            channelManager.close();
        }
        if (timer != null) {
            timer.stop();
        }
    }

    @Test
    void aNegativeHeaderTableSizeIsRejectedWhenTheClientIsBuilt() {
        assertThrows(IllegalArgumentException.class, () -> config().setHttp2HeaderTableSize(-1).build());
    }

    @Test
    void aMaxHeaderListSizeBelowOneIsRejectedWhenTheClientIsBuilt() {
        // Netty's Http2Settings accepts 0 here. The HPACK decoder built from those settings does not, so
        // without this check the throw lands later, part-way through the upgrade rather than at Http2Settings.
        assertThrows(IllegalArgumentException.class, () -> config().setHttp2MaxHeaderListSize(0).build());
    }

    @Test
    void aNullPingIntervalIsRejectedWhenTheClientIsBuilt() {
        // Read at the very end of the upgrade, after the connection preface is already on the wire.
        assertThrows(IllegalArgumentException.class, () -> config().setHttp2PingInterval(null).build());
    }

    @Test
    void anUpgradeThatFailsLeavesTheConnectionOnHttp11() {
        // The checks above make this unreachable through the builder, so come in the way that bypasses them:
        // the getters are declared on the AsyncHttpClientConfig interface, and a custom implementation never
        // runs the constructor that validates them.
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(configReportingMaxHeaderListSize(0), timer);

        channel = new EmbeddedChannel();
        channel.pipeline().addLast(ChannelManager.HTTP_CLIENT_CODEC, new HttpClientCodec());

        assertThrows(IllegalArgumentException.class,
                () -> channelManager.upgradePipelineToHttp2(channel.pipeline()));

        assertNotNull(channel.pipeline().get(ChannelManager.HTTP_CLIENT_CODEC),
                "a failed upgrade must leave the HTTP/1.1 codec in place");
        assertNull(channel.pipeline().get(ChannelManager.HTTP2_MULTIPLEX),
                "and must not have installed the multiplex handler");
        assertFalse(ChannelManager.isHttp2(channel),
                "and must not have marked the connection as HTTP/2");
    }

    private static AsyncHttpClientConfig configReportingMaxHeaderListSize(int value) {
        AsyncHttpClientConfig delegate = config().build();
        return (AsyncHttpClientConfig) Proxy.newProxyInstance(
                Http2ConfigValidationTest.class.getClassLoader(),
                new Class<?>[]{AsyncHttpClientConfig.class},
                (proxy, method, args) -> "getHttp2MaxHeaderListSize".equals(method.getName())
                        ? value
                        : method.invoke(delegate, args));
    }
}
