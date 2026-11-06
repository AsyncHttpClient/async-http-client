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

import org.asynchttpclient.uri.Uri;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Measures {@link Uri#toRelativeUrl()}, which builds the HTTP/1.1 origin-form
 * request target (and the HTTP/2 {@code :path}) on every request, and
 * {@link Uri#getBaseUrl()}, which keys the channel pool when a proxy or a
 * virtual host is set. Run with {@code -prof gc} to see B/op.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class UriRelativeUrlBenchmark {

    @Param({
            "http://www.example.com/path/to/resource",
            "http://www.example.com/path/to/resource?a=1&b=2",
            "http://localhost"
    })
    public String url;

    private Uri uri;

    @Setup
    public void setup() {
        uri = Uri.create(url);
    }

    @Benchmark
    public String toRelativeUrl() {
        return uri.toRelativeUrl();
    }

    @Benchmark
    public String getBaseUrl() {
        return uri.getBaseUrl();
    }
}
