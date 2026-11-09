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
package org.asynchttpclient.bench;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * What arming and cancelling an exchange's timeouts costs on the client's {@link HashedWheelTimer}: one
 * {@link TimeoutsHolder} built, started, given its read timeout and cancelled, as every attempt does.
 * <p>
 * {@code readTimeoutMs} picks which timeout comes first: at 60000 the read timeout is not shorter than what is
 * left of the 60 s request timeout, so only the request timeout needs arming; at 10000 the read timeout is
 * due first and has to be served as well. {@code rawTimerEntry} is one bare {@code newTimeout} plus
 * {@code cancel}, the floor for a single entry.
 * <p>
 * The timer ticks every millisecond rather than the default 100: its worker moves at most 100000 new entries
 * into the wheel per tick, so at the rates measured here a 100 ms tick leaves a backlog that grows for the
 * whole run and turns the result into a measure of the collector.
 * <p>
 * Run with several thread counts, since the timer's queues and pending counter are shared by every caller:
 * {@code -t 1}, {@code -t 8}, {@code -t 32}, with {@code -prof gc}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class RequestTimeoutsArmBenchmark {

    private static final TimerTask NOOP = timeout -> {
    };

    @Param({"60000", "10000"})
    public int readTimeoutMs;

    private HashedWheelTimer timer;
    private DefaultAsyncHttpClient client;
    private AsyncHttpClientConfig config;
    private NettyRequestSender requestSender;
    private InetSocketAddress remoteAddress;

    @State(Scope.Thread)
    public static class Exchange {
        NettyResponseFuture<?> future;

        @Setup
        public void setup() {
            Request request = new RequestBuilder().setUrl("http://localhost:8080/").build();
            future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
                @Override
                public Object onCompleted(Response response) {
                    return null;
                }
            }, null, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
        }
    }

    @Setup
    public void setup() throws ReflectiveOperationException {
        timer = new HashedWheelTimer(1, TimeUnit.MILLISECONDS, 512);
        timer.start();
        config = new DefaultAsyncHttpClientConfig.Builder()
                .setNettyTimer(timer)
                .setRequestTimeout(Duration.ofMillis(60000))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
        client = new DefaultAsyncHttpClient(config);
        Field field = DefaultAsyncHttpClient.class.getDeclaredField("requestSender");
        field.setAccessible(true);
        requestSender = (NettyRequestSender) field.get(client);
        remoteAddress = InetSocketAddress.createUnresolved("localhost", 8080);
    }

    @TearDown
    public void tearDown() throws Exception {
        client.close();
        timer.stop();
    }

    @Benchmark
    public TimeoutsHolder armAndCancel(Exchange exchange) {
        TimeoutsHolder holder = new TimeoutsHolder(timer, null, exchange.future, requestSender, config, remoteAddress);
        holder.start();
        holder.startReadTimeout();
        holder.cancel();
        return holder;
    }

    @Benchmark
    public Timeout rawTimerEntry() {
        Timeout timeout = timer.newTimeout(NOOP, 60000, TimeUnit.MILLISECONDS);
        timeout.cancel();
        return timeout;
    }
}
