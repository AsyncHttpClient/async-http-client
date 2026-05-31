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

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Measures the per-request HTTP/2 header copy in {@code NettyRequestSender.sendHttp2Frames}: the
 * baseline (String-typed {@code forEach} + {@code toLowerCase()} + {@code HashSet} skip-set) versus
 * {@link HttpHeaders#iteratorCharSequence()} + {@link AsciiString#contentEqualsIgnoreCase}. Both still
 * lowercase forwarded names (Netty's validating {@link DefaultHttp2Headers} rejects uppercase), so the
 * header set includes mixed-case names to exercise that path.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class Http2HeaderConversionBenchmark {

    private static final Set<String> EXCLUDED_STRING = new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "host"));

    private HttpHeaders headers;

    @Setup
    public void setup() {
        // Representative request header set built the way NettyRequestFactory builds it:
        // names are AsciiString constants from HttpHeaderNames.
        headers = new DefaultHttpHeaders(false);
        headers.set(HttpHeaderNames.HOST, "www.example.com");
        headers.set(HttpHeaderNames.USER_AGENT, "AHC/3.0");
        headers.set(HttpHeaderNames.ACCEPT, "*/*");
        headers.set(HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate");
        headers.set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        headers.set(HttpHeaderNames.CONTENT_LENGTH, "256");
        headers.set(HttpHeaderNames.AUTHORIZATION, "Bearer abcdef0123456789");
        headers.set(HttpHeaderNames.COOKIE, "session=deadbeef; theme=dark");
        headers.set(HttpHeaderNames.CONNECTION, "keep-alive");
        headers.set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        // Mixed-case user-supplied names (stored as String, original casing preserved) — these are the
        // names the proposed path must lowercase, since validating DefaultHttp2Headers rejects uppercase.
        headers.add("X-Request-ID", "abc-123-def-456");
        headers.add("X-Custom-Header", "some-custom-value");
    }

    /** Exact reproduction of production NettyRequestSender.sendHttp2Frames header loop. */
    @Benchmark
    public Http2Headers baseline_forEach_toLowerCase() {
        Http2Headers h2 = new DefaultHttp2Headers()
                .method("GET").path("/path?q=1").scheme("https").authority("www.example.com");
        for (Map.Entry<String, String> entry : headers) {
            String name = entry.getKey().toLowerCase();
            if (!EXCLUDED_STRING.contains(name)) {
                h2.add(name, entry.getValue());
            }
        }
        return h2;
    }

    /** Proposed: iteratorCharSequence + AsciiString-keyed case-insensitive skip set. */
    @Benchmark
    public Http2Headers proposed_charSequence_ascii() {
        Http2Headers h2 = new DefaultHttp2Headers()
                .method("GET").path("/path?q=1").scheme("https").authority("www.example.com");
        Iterator<Map.Entry<CharSequence, CharSequence>> it = headers.iteratorCharSequence();
        while (it.hasNext()) {
            Map.Entry<CharSequence, CharSequence> entry = it.next();
            CharSequence name = entry.getKey();
            if (!containsIgnoreCase(name)) {
                h2.add(toLowerCaseName(name), entry.getValue());
            }
        }
        return h2;
    }

    /** Mirrors production NettyRequestSender.toLowerCaseHeaderName — allocation-free when already lowercase. */
    private static CharSequence toLowerCaseName(CharSequence name) {
        if (name instanceof AsciiString) {
            return ((AsciiString) name).toLowerCase();
        }
        return name.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(CharSequence name) {
        // Direct constant comparison: HTTP/2 forbids exactly these 6 connection-specific names.
        // AsciiString.contentEqualsIgnoreCase short-circuits on length mismatch (cheap).
        return HttpHeaderNames.CONNECTION.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.HOST.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.UPGRADE.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.KEEP_ALIVE.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.PROXY_CONNECTION.contentEqualsIgnoreCase(name);
    }
}
