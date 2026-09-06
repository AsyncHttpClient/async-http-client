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

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChannelManager#isHttp2(Channel)} answers from the {@link Http2ConnectionState} attached to a
 * connection, while the thing it stands for is the multiplex handler in the pipeline. These pin the two
 * together: either both say HTTP/2 or neither does, whichever way a later change to the upgrade attaches them.
 */
class ChannelManagerHttp2MarkerTest {

    // One per class: the upgrade is what is under test and it needs a ChannelManager only to be called. Building
    // one per test costs an SslContext and an event loop group each time, for state that lives on the channel.
    private static ChannelManager channelManager;
    private static Timer timer;

    private EmbeddedChannel channel;

    @BeforeAll
    static void startManager() {
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(config().build(), timer);
    }

    @AfterAll
    static void stopManager() {
        if (channelManager != null) {
            channelManager.close();
        }
        if (timer != null) {
            timer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aConnectionThatWasNeverUpgradedIsNotHttp2() {
        assertNull(channel.pipeline().get(ChannelManager.HTTP2_MULTIPLEX),
                "an untouched pipeline should not carry the multiplex handler");
        assertFalse(ChannelManager.isHttp2(channel), "and should not be reported as HTTP/2");
    }

    @Test
    void upgradingAConnectionBothInstallsTheHandlerAndReportsHttp2() {
        channelManager.upgradePipelineToHttp2(channel.pipeline());

        assertNotNull(channel.pipeline().get(ChannelManager.HTTP2_MULTIPLEX),
                "the upgrade should install the multiplex handler");
        assertTrue(ChannelManager.isHttp2(channel), "and should report the connection as HTTP/2");
    }

    @Test
    void aStreamOfAnHttp2ConnectionIsNotTheConnection() {
        // A real stream child rather than a bare channel: what is worth pinning is that a stream does not
        // inherit the connection state its parent carries, since that is now what identifies an HTTP/2
        // connection. The stream is where a request is written, so mistaking it for its parent would loop.
        channelManager.upgradePipelineToHttp2(channel.pipeline());
        channel.runPendingTasks();

        Channel stream = new Http2StreamChannelBootstrap(channel)
                .handler(new ChannelInboundHandlerAdapter())
                .open().syncUninterruptibly().getNow();
        try {
            assertNull(stream.pipeline().get(ChannelManager.HTTP2_MULTIPLEX),
                    "a stream child carries no multiplex handler of its own");
            assertFalse(ChannelManager.isHttp2(stream), "and is not the connection that multiplexes it");
        } finally {
            stream.close().syncUninterruptibly();
        }
    }
}
