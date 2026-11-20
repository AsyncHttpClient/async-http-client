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
 * The holder's clocks and the timer are all fakes, so the delay a task re-arms with is read straight off the
 * timer rather than inferred from how long a request took to fail.
 */
public class ReadTimeoutTimerTaskTest {

    private static final long READ_TIMEOUT_MS = 1_000;
    private static final long SINCE_LAST_READ_MS = 600;

    private final AtomicLong elapsedMillis = new AtomicLong();
    private long origin;
    private long nanoOrigin;
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
    public void aReadTimeoutStillDueBeforeTheRequestTimeoutIsReArmed() throws Exception {
        TimeoutsHolder holder = holder(Duration.ofMillis(1_500));
        holder.start();
        // The request timeout's entry, armed early to serve the read timeout too, fires before the request is
        // written, which leaves the read timeout to an entry of its own.
        elapsedMillis.set(100);
        armedTask(1, 0).run(null);
        holder.startReadTimeout();
        ReadTimeoutTimerTask task = (ReadTimeoutTimerTask) armedTask(3, 2);

        // The full read timeout would land after the request timeout, what is left of it does not.
        elapsedMillis.set(SINCE_LAST_READ_MS);
        task.run((Timeout) null);

        assertEquals(List.of(READ_TIMEOUT_MS, 1_400L, READ_TIMEOUT_MS, READ_TIMEOUT_MS - SINCE_LAST_READ_MS),
                armedDelays(4), "a read timeout that is due before the request timeout should not be dropped");
    }

    @Test
    public void aReArmNeverOutlastsAWholeWindow() {
        ReadTimeoutTimerTask task = armedReadTimeout(Duration.ofMillis(-1));

        // The wall clock stepped back since the last read.
        elapsedMillis.set(-5_000);
        task.run((Timeout) null);

        assertEquals(List.of(READ_TIMEOUT_MS, READ_TIMEOUT_MS), armedDelays(2),
                "a single re-arm should never be longer than the read timeout");
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
        holder(requestTimeout).startReadTimeout();

        TimerTask armed = armedTask(1, 0);
        assertSame(ReadTimeoutTimerTask.class, armed.getClass());
        return (ReadTimeoutTimerTask) armed;
    }

    private TimeoutsHolder holder(Duration requestTimeout) {
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
        nanoOrigin = future.getStartNanos();

        return new TimeoutsHolder(timer, null, future, requestSender, config, null, this::millis, this::nanos);
    }

    private TimerTask armedTask(int expectedArms, int index) {
        ArgumentCaptor<TimerTask> armed = ArgumentCaptor.forClass(TimerTask.class);
        verify(timer, times(expectedArms)).newTimeout(armed.capture(), anyLong(), any());
        return armed.getAllValues().get(index);
    }

    private List<Long> armedDelays(int expectedArms) {
        ArgumentCaptor<Long> delays = ArgumentCaptor.forClass(Long.class);
        verify(timer, times(expectedArms)).newTimeout(any(), delays.capture(), any(TimeUnit.class));
        return delays.getAllValues();
    }

    private long millis() {
        return origin + elapsedMillis.get();
    }

    private long nanos() {
        return nanoOrigin + TimeUnit.MILLISECONDS.toNanos(elapsedMillis.get());
    }
}
