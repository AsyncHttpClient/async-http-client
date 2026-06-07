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

import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures allocations of {@link ThreadSafeCookieStore#get(Uri)} which is on the
 * request path: every outgoing request for which a cookie store is configured
 * calls it to collect applicable cookies. The current implementation walks
 * sub-domains and, for each, runs a Stream pipeline
 * ({@code entrySet().stream().filter(lambda).map(lambda).collect(toList())}).
 *
 * This bench pins the per-get byte cost so a proposal can quantify replacing
 * the Stream pipeline + per-subdomain list copies with an imperative scan.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CookieStoreGetBenchmark {

    private ThreadSafeCookieStore store;
    private Uri requestUri;

    @Param({"1", "5"})
    public int cookiesPerDomain;

    @Setup(Level.Trial)
    public void setup() {
        store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("https://www.example.com/some/path");
        for (int i = 0; i < cookiesPerDomain; i++) {
            DefaultCookie c = new DefaultCookie("cookie" + i, "value" + i);
            c.setDomain("www.example.com");
            c.setPath("/some");
            store.add(uri, c);
        }
        // a couple of parent-domain cookies to force the sub-domain walk to find matches
        DefaultCookie root = new DefaultCookie("root", "v");
        root.setDomain("example.com");
        root.setPath("/");
        store.add(uri, root);

        requestUri = Uri.create("https://www.example.com/some/path/leaf");
    }

    @Benchmark
    public List<Cookie> get() {
        return store.get(requestUri);
    }
}
