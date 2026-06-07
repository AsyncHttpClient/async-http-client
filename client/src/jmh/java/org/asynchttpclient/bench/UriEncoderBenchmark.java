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
import org.asynchttpclient.util.UriEncoder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Measures allocations of {@code UriEncoder.encode(uri, queryParams)}, which is
 * invoked once per request build via {@code RequestBuilderBase.computeUri()}.
 *
 * When the path needs no percent-encoding and there are no extra query params
 * (the overwhelmingly common case for already-well-formed URLs), both
 * {@code encodePath} and {@code encodeQuery} return the *same* String
 * instances, yet {@code UriEncoder.encode} still builds a brand-new {@code Uri}
 * with identical field values — a wasted ~64 B allocation per request build.
 *
 * This bench measures the production `encode` (FIXING) on a clean URL with no
 * query params, pinning the per-build Uri allocation a fast-path
 * "return the same Uri when nothing changed" check would remove.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class UriEncoderBenchmark {

    private UriEncoder encoder;
    private Uri cleanUri;

    @Setup(Level.Trial)
    public void setup() {
        encoder = UriEncoder.uriEncoder(false); // FIXING
        cleanUri = Uri.create("http://www.example.com/path/to/resource?a=1&b=2");
    }

    /** Production path: builds a new Uri even when path/query are unchanged. */
    @Benchmark
    public Uri encodeNoChange() {
        return encoder.encode(cleanUri, null);
    }
}
