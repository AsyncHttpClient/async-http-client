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
 * hashes of a Digest response. Run against two builds to compare them.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FormDigestEncodeBenchmark {

    private List<Param> smallForm;
    private List<Param> largeForm;
    private Realm.Builder asciiDigest;
    private Realm.Builder utf8Digest;

    @Setup(Level.Trial)
    public void setup() {
        smallForm = form(4);
        largeForm = form(256);
        Uri uri = Uri.create("http://localhost:8080/protected/resource?id=42");
        String challenge = "Digest realm=\"protected\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", qop=\"auth\"";
        asciiDigest = new Realm.Builder("user", "secret-password")
                .setUri(uri)
                .setMethodName("GET")
                .parseWWWAuthenticateHeader(challenge);
        utf8Digest = new Realm.Builder("пользователь", "пароль")
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
}
