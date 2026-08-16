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
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * HTTP/1 must replay a body on 100 Continue only when that body was deferred for Expect: 100-continue.
 */
public class Continue100InterceptorTest {

    private ChannelManager channelManager;
    private Timer timer;
    private Continue100Interceptor interceptor;

    @BeforeEach
    public void setUp() {
        AsyncHttpClientConfig cfg = config().build();
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(cfg, timer);
        interceptor = new Continue100Interceptor(new NettyRequestSender(cfg, channelManager, timer, null));
    }

    @AfterEach
    public void tearDown() {
        if (channelManager != null) {
            channelManager.close();
        }
        if (timer != null) {
            timer.stop();
        }
    }

    @Test
    public void http1Unsolicited100DoesNotReplayABodyThatWasAlreadySent() {
        NettyResponseFuture<?> future = newFuture();
        future.setDontWriteBodyBecauseExpectContinue(false);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertTrue(interceptor.exitAfterHandling100(channel, future));
            assertFalse(Channels.getAttribute(channel) instanceof OnLastHttpContentCallback);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void http1Deferred100SchedulesTheBodyWriteAfterLastHttpContent() {
        NettyResponseFuture<?> future = newFuture();
        future.setDontWriteBodyBecauseExpectContinue(true);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertTrue(interceptor.exitAfterHandling100(channel, future));
            assertFalse(future.isDontWriteBodyBecauseExpectContinue());
            assertTrue(Channels.getAttribute(channel) instanceof OnLastHttpContentCallback);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static NettyResponseFuture<?> newFuture() {
        return new NettyResponseFuture<>(null, mock(AsyncHandler.class), null, 0, null, null, null);
    }
}
