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

import org.asynchttpclient.Realm;
import org.asynchttpclient.netty.channel.PrincipalScopedPartitionKey;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link PrincipalScopedPartitionKey#scope(Object, Realm, Realm, boolean)} runs for every pool poll and offer
 * of a connection-authenticated exchange: an NTLM or Negotiate origin, or HTTPS through a proxy that asks
 * for a login. {@code lookup} also hashes the key into a map, as the pool does.
 *
 * <p>{@code realms = 1} reuses one realm instance per hop, as a client-wide or reused request realm does.
 * {@code realms = 1024} cycles through that many equal but separately built instances, as building a realm
 * per request does, so every call misses a per-instance cache.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PrincipalScopedPartitionKeyBenchmark {

    /**
     * {@code ntlm}: NTLM origin, no proxy. {@code proxyTunnel}: Basic origin, HTTPS through a Basic proxy
     * login. {@code both}: NTLM origin through a Basic proxy login.
     */
    @Param({"ntlm", "proxyTunnel", "both"})
    public String hops;

    @Param({"1", "1024"})
    public int realms;

    private final Object baseKey = "https://intranet.example.com:443";
    private Realm[] originRealms;
    private Realm[] proxyRealms;
    private Map<Object, Object> pool;
    private int next;

    @Setup(Level.Trial)
    public void setup() {
        originRealms = new Realm[realms];
        proxyRealms = new Realm[realms];
        for (int i = 0; i < realms; i++) {
            Realm ntlm = new Realm.Builder("alice", "secret")
                    .setScheme(Realm.AuthScheme.NTLM)
                    .setNtlmDomain("CORP")
                    .setNtlmHost("workstation")
                    .build();
            Realm basic = new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.BASIC).build();
            Realm proxyLogin = new Realm.Builder("proxyuser", "proxysecret").setScheme(Realm.AuthScheme.BASIC).build();
            switch (hops) {
                case "ntlm":
                    originRealms[i] = ntlm;
                    break;
                case "proxyTunnel":
                    originRealms[i] = basic;
                    proxyRealms[i] = proxyLogin;
                    break;
                case "both":
                    originRealms[i] = ntlm;
                    proxyRealms[i] = proxyLogin;
                    break;
                default:
                    throw new IllegalArgumentException(hops);
            }
        }
        pool = new HashMap<>();
        pool.put(scope(), Boolean.TRUE);
    }

    private Object scope() {
        int i = next;
        next = i + 1 == realms ? 0 : i + 1;
        Realm proxyRealm = proxyRealms[i];
        return PrincipalScopedPartitionKey.scope(baseKey, originRealms[i], proxyRealm, proxyRealm != null);
    }

    @Benchmark
    public Object scopeOnly() {
        return scope();
    }

    @Benchmark
    public Object lookup() {
        return pool.get(scope());
    }
}
