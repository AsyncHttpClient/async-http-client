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

import org.asynchttpclient.Param;
import org.asynchttpclient.Realm;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.HttpUtils;
import org.asynchttpclient.util.StringUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Production paths that encode a pooled StringBuilder to bytes through
 * {@code StringUtils.charSequence2ByteBuffer}: an url-encoded form body, and the three
 * hashes of a Digest response, end to end and the encoding step alone. Run against two builds to
 * compare them.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FormDigestEncodeBenchmark {

    private static final String UTF8_USER = "пользователь";
    private static final String UTF8_PASSWORD = "пароль";

    private List<Param> smallForm;
    private List<Param> largeForm;
    private Realm.Builder asciiDigest;
    private Realm.Builder utf8Digest;
    private StringBuilder formBuilder;
    private StringBuilder digestBuilder;
    private StringBuilder utf8DigestBuilder;

    @Setup(Level.Trial)
    public void setup() {
        smallForm = form(4);
        largeForm = form(256);
        ByteBuffer formBody = HttpUtils.urlEncodeFormParams(largeForm, StandardCharsets.UTF_8);
        formBuilder = new StringBuilder(StandardCharsets.US_ASCII.decode(formBody));
        // HA1 ":" nonce ":" nc ":" cnonce ":" qop ":" HA2
        digestBuilder = new StringBuilder("939e7578ed9e3c518a452acee763bce9:dcd98b7102dd2f0e8b11d0f600bfb0c093:00000001:"
                + "0a4f113b5c8e1d2a:auth:39aff3a2bab6126f332b942af96d3366");
        // username ":" realm ":" password
        utf8DigestBuilder = new StringBuilder(UTF8_USER + ":protected:" + UTF8_PASSWORD);
        Uri uri = Uri.create("http://localhost:8080/protected/resource?id=42");
        String challenge = "Digest realm=\"protected\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", qop=\"auth\"";
        asciiDigest = new Realm.Builder("user", "secret-password")
                .setUri(uri)
                .setMethodName("GET")
                .parseWWWAuthenticateHeader(challenge);
        utf8Digest = new Realm.Builder(UTF8_USER, UTF8_PASSWORD)
                .setUri(uri)
                .setMethodName("GET")
                .parseWWWAuthenticateHeader(challenge + ", charset=UTF-8");
    }

    private static List<Param> form(int params) {
        List<Param> list = new ArrayList<>(params);
        for (int i = 0; i < params; i++) {
            list.add(new Param("field" + i, "value " + i + "&x=y"));
        }
        return list;
    }

    /** 4 params, a 91 byte body. */
    @Benchmark
    public ByteBuffer formSmall() {
        return HttpUtils.urlEncodeFormParams(smallForm, StandardCharsets.UTF_8);
    }

    /** 256 params, a 6,691 byte body. */
    @Benchmark
    public ByteBuffer formLarge() {
        return HttpUtils.urlEncodeFormParams(largeForm, StandardCharsets.UTF_8);
    }

    /** ISO-8859-1 digest of ASCII credentials. */
    @Benchmark
    public Realm digestAscii() {
        return asciiDigest.build();
    }

    /** UTF-8 digest of Cyrillic credentials: HA1 is not ASCII. */
    @Benchmark
    public Realm digestUtf8() {
        return utf8Digest.build();
    }

    /** The encoding step of {@link #formLarge} alone. */
    @Benchmark
    public ByteBuffer encodeFormBuilder() {
        return StringUtils.charSequence2ByteBuffer(formBuilder, StandardCharsets.US_ASCII);
    }

    /** The encoding step of the last {@link #digestAscii} hash alone. */
    @Benchmark
    public ByteBuffer encodeDigestBuilder() {
        return StringUtils.charSequence2ByteBuffer(digestBuilder, StandardCharsets.ISO_8859_1);
    }

    /** The encoding step of the first {@link #digestUtf8} hash alone: non-ASCII input. */
    @Benchmark
    public ByteBuffer encodeUtf8DigestBuilder() {
        return StringUtils.charSequence2ByteBuffer(utf8DigestBuilder, StandardCharsets.UTF_8);
    }
}
