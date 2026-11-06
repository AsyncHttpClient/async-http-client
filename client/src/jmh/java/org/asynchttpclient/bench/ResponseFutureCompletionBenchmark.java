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

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequest;
import org.asynchttpclient.netty.request.body.NettyBody;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.concurrent.TimeUnit;

/**
 * The per-response and per-read bookkeeping {@link NettyResponseFuture} does on the HTTP/1.1 path.
 * <p>
 * {@code completeHandedRequest} is what {@code AsyncHttpClientHandler#finishUpdate} does to a future whose request
 * was written to the channel: cancel the timeouts, then {@code done()}, which cancels them again and offers the
 * request back for release; most of its time goes to allocating the future. {@code cancelClearedTimeouts} and
 * {@code releaseHandedRequest} isolate the two calls in there that find nothing left to do. {@code touch} is what
 * every {@code channelRead}, i.e. every body chunk, does.
 * <p>
 * Run with: {@code /tmp/run-jmh.sh ResponseFutureCompletionBenchmark -prof gc -f 3 -wi 5 -i 10}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ResponseFutureCompletionBenchmark {

    private static final MethodHandle NETTY_REQUEST_CONSTRUCTOR;

    static {
        try {
            Constructor<NettyRequest> constructor = NettyRequest.class.getDeclaredConstructor(HttpRequest.class, NettyBody.class);
            constructor.setAccessible(true);
            NETTY_REQUEST_CONSTRUCTOR = MethodHandles.lookup().unreflectConstructor(constructor)
                    .asType(MethodType.methodType(NettyRequest.class, HttpRequest.class, NettyBody.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AsyncHandler<Object> handler = new AsyncHandler<Object>() {
        @Override
        public AsyncHandler.State onStatusReceived(HttpResponseStatus responseStatus) {
            return AsyncHandler.State.CONTINUE;
        }

        @Override
        public AsyncHandler.State onHeadersReceived(HttpHeaders headers) {
            return AsyncHandler.State.CONTINUE;
        }

        @Override
        public AsyncHandler.State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
            return AsyncHandler.State.CONTINUE;
        }

        @Override
        public void onThrowable(Throwable t) {
        }

        @Override
        public Object onCompleted() {
            return Boolean.TRUE;
        }
    };

    private final HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

    private NettyResponseFuture<Object> future;
    private NettyRequest handedRequest;

    @Setup
    public void setup() throws Throwable {
        future = newFuture();
        handedRequest = future.getNettyRequest();
    }

    private NettyResponseFuture<Object> newFuture() throws Throwable {
        NettyRequest nettyRequest = (NettyRequest) NETTY_REQUEST_CONSTRUCTOR.invokeExact(httpRequest, (NettyBody) null);
        nettyRequest.markHandedToChannel();
        return new NettyResponseFuture<>(null, handler, nettyRequest, 5, null, null, null);
    }

    @Benchmark
    public Object completeHandedRequest() throws Throwable {
        NettyResponseFuture<Object> future = newFuture();
        future.cancelTimeouts();
        future.done();
        return future.toCompletableFuture().getNow(null);
    }

    /** The second of the two cancellations a completed exchange goes through. */
    @Benchmark
    public void cancelClearedTimeouts() {
        future.cancelTimeouts();
    }

    /** What {@code done()} does to a request Netty's encoder already owns. */
    @Benchmark
    public void releaseHandedRequest() {
        handedRequest.release();
    }

    @Benchmark
    public void touch() {
        future.touch();
    }
}
