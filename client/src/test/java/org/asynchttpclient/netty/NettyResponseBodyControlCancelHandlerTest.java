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
package org.asynchttpclient.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyResponseBodyControlCancelHandlerTest {

    private EmbeddedChannel channel;
    private NettyResponseFuture<?> future;
    private final List<Object[]> cancellations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel();
        future = new NettyResponseFuture<>(get("http://localhost/").build(), new AsyncCompletionHandlerBase(), null, 0,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    private NettyResponseBodyControl create() {
        return NettyResponseBodyControl.create(future, channel,
                (f, c, bodyFullyRead) -> cancellations.add(new Object[]{f, c, bodyFullyRead}));
    }

    @Test
    void cancelPassesFutureChannelAndBodyState() {
        NettyResponseBodyControl control = create();
        NettyResponseBodyControl.markBodyFullyRead(future);

        control.cancel();
        control.cancel();

        assertEquals(1, cancellations.size());
        assertSame(future, cancellations.get(0)[0]);
        assertSame(channel, cancellations.get(0)[1]);
        assertEquals(true, cancellations.get(0)[2]);
        assertNull(future.responseBodyControl());
    }

    @Test
    void cancelBeforeBodyIsReadReportsIt() {
        create().cancel();

        assertEquals(1, cancellations.size());
        assertEquals(false, cancellations.get(0)[2]);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void resumeTouchesFutureAndRestoresAutoRead() {
        NettyResponseBodyControl control = create();
        control.suspend();
        assertTrue(NettyResponseBodyControl.isSuspended(future, channel));
        assertFalse(channel.config().isAutoRead());

        long touchedBefore = future.getLastTouch();
        // Let the millisecond clock move on, in either direction, so that a touch is observable.
        while (unpreciseMillisTime() == touchedBefore) {
            Thread.onSpinWait();
        }
        control.resume();

        assertFalse(NettyResponseBodyControl.isSuspended(future, channel));
        assertTrue(channel.config().isAutoRead());
        assertNotEquals(touchedBefore, future.getLastTouch());
    }

    @Test
    void completeRestoresAutoReadAndClearsControl() {
        NettyResponseBodyControl control = create();
        control.suspend();

        NettyResponseBodyControl.complete(future);

        assertTrue(channel.config().isAutoRead());
        assertNull(future.responseBodyControl());
        assertFalse(NettyResponseBodyControl.isSuspended(future));
        control.cancel();
        assertTrue(cancellations.isEmpty());
    }
}
