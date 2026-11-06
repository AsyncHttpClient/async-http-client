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
package org.asynchttpclient.netty.timeout;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a read timeout does when it fires before its window has run out because the channel was read from
 * since it was armed: it has to wait only for what is left of the window, or the exchange is failed up to
 * twice the read timeout after its last read.
 * <p>
 * The holder's clock and the timer are both fakes, so the delay a task re-arms with is read straight off the
 * timer rather than inferred from how long a request took to fail.
 */
public class ReadTimeoutTimerTaskTest {

    private static final long READ_TIMEOUT_MS = 1_000;
    private static final long SINCE_LAST_READ_MS = 600;

    private final AtomicLong elapsedMillis = new AtomicLong();
    private long origin;
    private NettyResponseFuture<?> future;

    private HashedWheelTimer senderTimer;
    private ChannelManager channelManager;
    private Timer timer;

    @BeforeEach
    void setUp() {
        timer = mock(Timer.class);
        when(timer.newTimeout(any(), anyLong(), any())).thenAnswer(invocation -> mock(Timeout.class));
    }

    @AfterEach
    void tearDown() {
        if (channelManager != null) {
            channelManager.close();
        }
        if (senderTimer != null) {
            senderTimer.stop();
        }
    }

    @Test
    public void aReadTimeoutThatFiresEarlyWaitsOnlyForWhatIsLeft() {
        ReadTimeoutTimerTask task = armedReadTimeout(Duration.ofMillis(-1));

        elapsedMillis.set(SINCE_LAST_READ_MS);
        task.run((Timeout) null);

        assertEquals(List.of(READ_TIMEOUT_MS, READ_TIMEOUT_MS - SINCE_LAST_READ_MS), armedDelays(2),
                "the read timeout should re-arm for the rest of its window, not for a whole one");
    }

    @Test
    public void aReadTimeoutStillDueBeforeTheRequestTimeoutIsReArmed() {
        // The full read timeout would land after the request timeout, what is left of it does not.
        ReadTimeoutTimerTask task = armedReadTimeout(Duration.ofMillis(1_500));

        elapsedMillis.set(SINCE_LAST_READ_MS);
        task.run((Timeout) null);

        assertEquals(List.of(READ_TIMEOUT_MS, READ_TIMEOUT_MS - SINCE_LAST_READ_MS), armedDelays(2),
                "a read timeout that is due before the request timeout should not be dropped");
    }

    @Test
    public void aWallClockSteppingBackDoesNotStretchTheWindow() {
        ReadTimeoutTimerTask task = armedReadTimeout(Duration.ofMillis(-1));

        elapsedMillis.set(-SINCE_LAST_READ_MS);
        task.run((Timeout) null);

        assertEquals(List.of(READ_TIMEOUT_MS, READ_TIMEOUT_MS), armedDelays(2),
                "a clock stepping back should not re-arm for more than a whole read timeout");
    }

    @Test
    public void aReadTimeoutWithNoReadInItsWindowFailsTheExchange() {
        ReadTimeoutTimerTask task = armedReadTimeout(Duration.ofMillis(-1));

        elapsedMillis.set(READ_TIMEOUT_MS);
        task.run((Timeout) null);

        armedDelays(1);
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, thrown.getCause());
    }

    private ReadTimeoutTimerTask armedReadTimeout(Duration requestTimeout) {
        AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .setRequestTimeout(requestTimeout)
                .build();
        senderTimer = new HashedWheelTimer();
        channelManager = new ChannelManager(config, senderTimer);
        NettyRequestSender requestSender = new NettyRequestSender(config, channelManager, senderTimer,
                mock(AsyncHttpClientState.class));

        Request request = new RequestBuilder().setUrl("http://example.com:12345").build();
        future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        }, null, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
        origin = future.getLastTouch();

        TimeoutsHolder holder = new TimeoutsHolder(timer, null, future, requestSender, config, null,
                this::millis, System::nanoTime);
        holder.startReadTimeout();

        ArgumentCaptor<TimerTask> armed = ArgumentCaptor.forClass(TimerTask.class);
        verify(timer).newTimeout(armed.capture(), anyLong(), any());
        assertSame(ReadTimeoutTimerTask.class, armed.getValue().getClass());
        return (ReadTimeoutTimerTask) armed.getValue();
    }

    private List<Long> armedDelays(int expectedArms) {
        ArgumentCaptor<Long> delays = ArgumentCaptor.forClass(Long.class);
        verify(timer, times(expectedArms)).newTimeout(any(), delays.capture(), any(TimeUnit.class));
        return delays.getAllValues();
    }

    private long millis() {
        return origin + elapsedMillis.get();
    }
}
