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

import io.netty.channel.embedded.EmbeddedChannel;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.ResponseBodyControl;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseBodyControl;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * The response body control work that {@code HttpHandler} does for one HTTP/1.1 response whose handler never
 * touches the control: create it when the body starts, then complete it when the response finishes.
 * {@link EmbeddedChannel}'s event loop always reports {@code inEventLoop()}, as a real event loop does on this path.
 * <p>
 * Run with: {@code /tmp/run-jmh.sh ResponseBodyControlBenchmark -prof gc -f 1 -wi 5 -i 5}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ResponseBodyControlBenchmark {

    private static final ClassValue<Boolean> USES_RESPONSE_BODY_CONTROL = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("onResponseBodyStart", ResponseBodyControl.class).getDeclaringClass()
                        != AsyncHandler.class;
            } catch (NoSuchMethodException e) {
                return true;
            }
        }
    };

    private EmbeddedChannel channel;
    private NettyResponseFuture<?> future;
    private AsyncHandler<?> handler;
    private NettyResponseBodyControl.CancelHandler cancelHandler;

    @Setup(Level.Trial)
    public void setup() {
        channel = new EmbeddedChannel();
        handler = new AsyncCompletionHandlerBase();
        future = new NettyResponseFuture<>(Dsl.get("http://localhost/").build(), handler,
                null, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
        cancelHandler = (future, channel, bodyFullyRead) -> channel.close();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    /**
     * The call shape {@code HttpHandler} used on 5542c9fc4: a bound {@code future::touch} and a capturing cancel lambda.
     */
    @Benchmark
    public void lambdas(Blackhole bh) {
        NettyResponseFuture<?> future = this.future;
        EmbeddedChannel channel = this.channel;
        NettyResponseBodyControl control = NettyResponseBodyControl.create(future, channel, future::touch,
                bodyFullyRead -> bh.consume(channel.isOpen() && !bodyFullyRead || !future.isKeepAlive()));
        bh.consume(control);
        NettyResponseBodyControl.complete(future);
    }

    /**
     * A handler that overrides {@code onResponseBodyStart}: one {@link NettyResponseBodyControl.CancelHandler} serves
     * every response.
     */
    @Benchmark
    public void callback(Blackhole bh) {
        NettyResponseBodyControl control = NettyResponseBodyControl.create(future, channel, cancelHandler);
        bh.consume(control);
        NettyResponseBodyControl.complete(future);
    }

    /**
     * A handler that inherits {@code onResponseBodyStart}, such as {@link AsyncCompletionHandlerBase}: no control.
     */
    @Benchmark
    public void skipped(Blackhole bh) {
        bh.consume(USES_RESPONSE_BODY_CONTROL.get(handler.getClass()));
        NettyResponseBodyControl.complete(future);
    }
}
