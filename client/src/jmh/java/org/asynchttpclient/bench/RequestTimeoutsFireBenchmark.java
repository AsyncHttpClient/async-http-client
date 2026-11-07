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
import io.netty.util.Timer;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * An exchange that outlives its read window: the {@link RequestTimeoutsArmBenchmark#armAndCancel} lifecycle
 * with the entry that checks the read timeout firing {@code fires} times in between, each time finding the
 * channel read from within the window and arming again. Read timeout 10 s, request timeout 60 s, on the
 * client's {@link HashedWheelTimer}.
 * <p>
 * The benchmark fires the entry itself, on its own thread, rather than waiting out the window: it takes the
 * shortest entry the attempt has pending out of the wheel and runs its task. Taking it out is a cancel, which
 * a real expiry does not cost the caller; both builds pay it alike, once per fire.
 * <p>
 * Run with {@code -t 1}, {@code -t 8}, {@code -t 32}, with {@code -prof gc}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class RequestTimeoutsFireBenchmark {

    @Param({"1", "5"})
    public int fires;

    private HashedWheelTimer timer;
    private DefaultAsyncHttpClient client;
    private AsyncHttpClientConfig config;
    private NettyRequestSender requestSender;
    private InetSocketAddress remoteAddress;

    @State(Scope.Thread)
    public static class Exchange {
        NettyResponseFuture<?> future;
        ShortestEntryTimer timer;

        @Setup
        public void setup(RequestTimeoutsFireBenchmark benchmark) {
            Request request = new RequestBuilder().setUrl("http://localhost:8080/").build();
            future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
                @Override
                public Object onCompleted(Response response) {
                    return null;
                }
            }, null, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
            timer = new ShortestEntryTimer(benchmark.timer);
        }
    }

    /**
     * Arms on the shared timer and remembers this thread's shortest pending entry, which is the one that checks
     * the read timeout.
     */
    static final class ShortestEntryTimer implements Timer {
        private final Timer delegate;
        private TimerTask task;
        private Timeout timeout;
        private long delay = Long.MAX_VALUE;

        ShortestEntryTimer(Timer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
            Timeout timeout = delegate.newTimeout(task, delay, unit);
            if (delay < this.delay) {
                this.task = task;
                this.timeout = timeout;
                this.delay = delay;
            }
            return timeout;
        }

        void fireShortest() throws Exception {
            TimerTask task = this.task;
            Timeout timeout = this.timeout;
            this.task = null;
            this.timeout = null;
            delay = Long.MAX_VALUE;
            timeout.cancel();
            task.run(timeout);
        }

        void reset() {
            task = null;
            timeout = null;
            delay = Long.MAX_VALUE;
        }

        @Override
        public Set<Timeout> stop() {
            throw new UnsupportedOperationException();
        }
    }

    @Setup
    public void setup() throws ReflectiveOperationException {
        timer = new HashedWheelTimer(1, TimeUnit.MILLISECONDS, 512);
        timer.start();
        config = new DefaultAsyncHttpClientConfig.Builder()
                .setNettyTimer(timer)
                .setRequestTimeout(Duration.ofMillis(60000))
                .setReadTimeout(Duration.ofMillis(10000))
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
    public TimeoutsHolder armFireAndCancel(Exchange exchange) throws Exception {
        TimeoutsHolder holder = new TimeoutsHolder(exchange.timer, null, exchange.future, requestSender, config, remoteAddress);
        holder.start();
        holder.startReadTimeout();
        for (int i = 0; i < fires; i++) {
            exchange.future.touch();
            exchange.timer.fireShortest();
        }
        holder.cancel();
        exchange.timer.reset();
        return holder;
    }
}
