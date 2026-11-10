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

import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.exception.FilterException;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.uri.Uri;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;

/**
 * Measures {@code DefaultAsyncHttpClient.executeRequest} up to the request filters, for a request whose URL has a
 * cookie in the client's cookie store ({@code storedCookie}) and for one whose URL has none ({@code noStoredCookie}).
 * The difference between the two is what adding the stored cookies to the request costs.
 * <p>
 * A filter ends each call by throwing a preallocated exception, so nothing reaches the network; the failed future
 * that {@code executeRequest} then builds is the same in both arms.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StoredCookieRequestBenchmark {

    private static final FilterException STOP = new FilterException("stop");

    @Param({"4", "16"})
    public int headerCount;

    private AsyncHttpClient client;
    private Request withStoredCookie;
    private Request withoutStoredCookie;
    private AsyncCompletionHandlerBase handler;

    @Setup(Level.Trial)
    public void setup() {
        ThreadSafeCookieStore cookieStore = new ThreadSafeCookieStore();
        DefaultCookie session = new DefaultCookie("SESSION", "0123456789abcdef0123456789abcdef");
        session.setPath("/");
        cookieStore.add(Uri.create("https://api.example.com/"), session);

        RequestFilter stop = new RequestFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException {
                throw STOP;
            }
        };
        client = asyncHttpClient(config().setCookieStore(cookieStore).addRequestFilter(stop));
        withStoredCookie = request("https://api.example.com/v1/items?page=2&size=50");
        withoutStoredCookie = request("https://other.example.org/v1/items?page=2&size=50");
        handler = new AsyncCompletionHandlerBase() {
            @Override
            public void onThrowable(Throwable t) {
            }
        };
    }

    private Request request(String url) {
        RequestBuilder builder = new RequestBuilder("GET").setUrl(url);
        for (int i = 0; i < headerCount; i++) {
            builder.addHeader("X-Header-" + i, "value-" + i);
        }
        return builder.build();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        client.close();
    }

    @Benchmark
    public ListenableFuture<Response> storedCookie() {
        return client.executeRequest(withStoredCookie, handler);
    }

    @Benchmark
    public ListenableFuture<Response> noStoredCookie() {
        return client.executeRequest(withoutStoredCookie, handler);
    }
}
