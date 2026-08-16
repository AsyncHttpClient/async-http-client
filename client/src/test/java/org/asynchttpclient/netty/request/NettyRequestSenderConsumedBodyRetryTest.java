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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue #1973: a consumed InputStream body on retry must complete the future promptly through
 * {@link NettyRequestSender#writeRequest}, not hang until the request timeout.
 */
public class NettyRequestSenderConsumedBodyRetryTest extends AbstractBasicTest {

    private AsyncHttpClientConfig senderConfig;
    private ChannelManager channelManager;
    private NettyRequestSender sender;
    private Timer timer;

    @BeforeEach
    public void setUpSender() {
        senderConfig = config().build();
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(senderConfig, timer);
        sender = new NettyRequestSender(senderConfig, channelManager, timer, null);
    }

    @AfterEach
    public void tearDownSender() {
        if (channelManager != null) {
            channelManager.close();
        }
        if (timer != null) {
            timer.stop();
        }
    }

    @Test
    public void writeRequestAbortsWhenConsumedStreamCannotReset() throws Exception {
        Request request = new RequestBuilder("POST")
                .setUrl("http://example.com/")
                .setBody(InputStream.nullInputStream())
                .build();
        NettyRequestFactory factory = new NettyRequestFactory(senderConfig);
        NettyResponseFuture<Object> future = newFuture(request, factory.newNettyRequest(request, false, null, null, null));

        EmbeddedChannel channel = new EmbeddedChannel(new ChunkedWriteHandler());
        try {
            sender.writeRequest(future, channel);
            assertFalse(future.isDone(), "first write of an unconsumed stream must not abort");

            future.setNettyRequest(factory.newNettyRequest(request, false, null, null, null));
            sender.writeRequest(future, channel);

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, thrown.getCause());
            assertNotEquals("Stream closed", thrown.getCause().getMessage());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void writeRequestAbortsWhenMarkSupportedResetFails() throws Exception {
        BufferedInputStream is = new BufferedInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        Request request = new RequestBuilder("POST")
                .setUrl("http://example.com/")
                .setBody(is)
                .build();
        NettyRequestFactory factory = new NettyRequestFactory(senderConfig);
        NettyResponseFuture<Object> future = newFuture(request, factory.newNettyRequest(request, false, null, null, null));

        EmbeddedChannel channel = new EmbeddedChannel(new ChunkedWriteHandler());
        try {
            sender.writeRequest(future, channel);
            channel.runPendingTasks();
            assertFalse(future.isDone(), "first write of an unconsumed stream must not abort");
            assertThrows(IOException.class, is::read);

            future.setNettyRequest(factory.newNettyRequest(request, false, null, null, null));
            sender.writeRequest(future, channel);

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, thrown.getCause());
            assertNotEquals("Stream closed", thrown.getCause().getMessage());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void replayOfNonResettableInputStreamFailsTheFuturePromptly() throws Exception {
        assertReplayFailsPromptly(InputStream.nullInputStream());
    }

    @Test
    public void replayOfClosedBufferedInputStreamFailsTheFuturePromptly() throws Exception {
        assertReplayFailsPromptly(new BufferedInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }

    private void assertReplayFailsPromptly(InputStream body) throws Exception {
        AtomicBoolean replay = new AtomicBoolean(true);
        ResponseFilter replayOnce = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (replay.getAndSet(false)) {
                    return new FilterContext.FilterContextBuilder<T>(ctx.getAsyncHandler(), ctx.getRequest())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };

        try (AsyncHttpClient client = asyncHttpClient(config()
                .addResponseFilter(replayOnce)
                .setRequestTimeout(Duration.ofSeconds(30)))) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () ->
                    client.preparePost(getTargetUrl())
                            .setBody(body)
                            .execute()
                            .get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, thrown.getCause());
            assertNotEquals("Stream closed", thrown.getCause().getMessage());
        }
    }

    private static NettyResponseFuture<Object> newFuture(Request request, NettyRequest nettyRequest) {
        return new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        }, nettyRequest, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
    }
}
