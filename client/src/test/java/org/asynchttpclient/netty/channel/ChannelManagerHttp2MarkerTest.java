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
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChannelManager#isHttp2(io.netty.channel.Channel)} answers from an attribute, while the thing it stands
 * for is the multiplex handler in the pipeline. These pin the two together: either both say HTTP/2 or neither
 * does, whichever way a later change to the upgrade sets them.
 */
class ChannelManagerHttp2MarkerTest {

    private ChannelManager channelManager;
    private Timer timer;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(config().build(), timer);
        channel = new EmbeddedChannel();
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
        timer.stop();
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
    void aStreamChannelIsNotItsParentsConnection() {
        // Nothing marks a stream child, and its own pipeline carries no multiplex handler either, so the two
        // agree here as well: a stream is not the connection that multiplexes it.
        channelManager.upgradePipelineToHttp2(channel.pipeline());
        EmbeddedChannel stream = new EmbeddedChannel();
        try {
            assertNull(stream.pipeline().get(ChannelManager.HTTP2_MULTIPLEX));
            assertFalse(ChannelManager.isHttp2(stream));
        } finally {
            stream.finishAndReleaseAll();
        }
    }
}
