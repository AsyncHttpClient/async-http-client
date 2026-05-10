/*
 *    Copyright (c) 2014-2026 AsyncHttpClient Project. All rights reserved.
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
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NettyConnectListenerTest {

    private static NettyResponseFuture<Object> newFuture() {
        Request request = new RequestBuilder().setUrl("http://example.com:12345").build();
        return new NettyResponseFuture<>(
                request,
                new AsyncCompletionHandler<Object>() {
                    @Override
                    public Object onCompleted(Response response) {
                        return null;
                    }
                },
                null,
                0,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE,
                null,
                null);
    }

    /**
     * Reproduces the race in issue #2172: a TimeoutsHolder was previously installed
     * on the future, but cancelTimeouts() has nulled it out before onSuccess fires
     * on the IO event loop. The previous code would NPE on
     * timeoutsHolder.setResolvedRemoteAddress(...). With the fix, the listener
     * silently closes the freshly-connected channel and returns.
     */
    @Test
    public void onSuccessShouldNotThrowWhenTimeoutsHolderIsNull() {
        NettyResponseFuture<Object> future = newFuture();
        TimeoutsHolder holder = new TimeoutsHolder(null, future, null,
                new DefaultAsyncHttpClientConfig.Builder().build(), null);
        future.setTimeoutsHolder(holder);
        // Simulate the race: cancelTimeouts has nulled the holder, but isDone is not
        // (yet) observable on this thread.
        future.cancelTimeouts();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, null, null);
        EmbeddedChannel channel = new EmbeddedChannel();

        // Must not throw NPE.
        listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 80));

        // Listener should have closed the freshly-connected channel.
        assertFalse(channel.isOpen(), "channel should be closed when holder is null");
        assertFalse(future.isDone(),
                "future state was not modified by cancelTimeouts alone — still not done");
    }

    /**
     * When the future has been aborted (e.g. by a request timeout firing while the
     * connect was in flight), abort() calls terminateAndExit() which both nulls the
     * holder and sets isDone=1. The early-out check must catch this — under the old
     * isCancelled()-only check it would have fallen through to the holder NPE since
     * abort() does not set isCancelled.
     */
    @Test
    public void onSuccessShouldExitEarlyWhenFutureWasAborted() {
        NettyResponseFuture<Object> future = newFuture();
        TimeoutsHolder holder = new TimeoutsHolder(null, future, null,
                new DefaultAsyncHttpClientConfig.Builder().build(), null);
        future.setTimeoutsHolder(holder);
        future.abort(new IOException("request timeout"));

        assertTrue(future.isDone(), "abort() should mark the future done");
        assertFalse(future.isCancelled(),
                "abort() must not set isCancelled — that's the whole reason the old check was insufficient");

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, null, null);
        EmbeddedChannel channel = new EmbeddedChannel();

        // Must not throw NPE.
        listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 80));

        assertFalse(channel.isOpen(), "channel should be closed when future is already done");
    }

    /**
     * Cancelling the future also nulls the holder and sets isCancelled=1.
     * Mirrors the abort case but via the explicit cancel path; guards against
     * future regressions of the early-out for either flag.
     */
    @Test
    public void onSuccessShouldExitEarlyWhenFutureWasCancelled() {
        NettyResponseFuture<Object> future = newFuture();
        TimeoutsHolder holder = new TimeoutsHolder(null, future, null,
                new DefaultAsyncHttpClientConfig.Builder().build(), null);
        future.setTimeoutsHolder(holder);
        future.cancel(true);

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, null, null);
        EmbeddedChannel channel = new EmbeddedChannel();

        listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 80));

        assertFalse(channel.isOpen());
    }
}
