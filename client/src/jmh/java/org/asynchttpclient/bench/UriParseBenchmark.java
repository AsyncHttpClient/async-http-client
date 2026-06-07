/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 */
package org.asynchttpclient.bench;

import org.asynchttpclient.uri.Uri;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Measures allocations of {@link Uri#create(String)} (the current production
 * parser) on a few representative URLs. The parser allocates a transient
 * {@code UriParser} object per call plus the resulting {@code Uri}; this bench
 * pins down the per-call byte cost so a proposal can quantify removing the
 * scratch object / scheme lower-casing substring churn.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class UriParseBenchmark {

    @Param({
            "http://www.example.com/path/to/resource?a=1&b=2",
            "https://user:pass@host.example.com:8443/a/b/c",
            "http://localhost/"
    })
    public String url;

    @Benchmark
    public Uri create() {
        return Uri.create(url);
    }

    /** Re-parse with a context (relative resolution path). */
    @Benchmark
    public void createWithContext(Blackhole bh) {
        Uri base = Uri.create(url);
        bh.consume(base);
        bh.consume(Uri.create(base, "/other/path?x=9"));
    }
}
