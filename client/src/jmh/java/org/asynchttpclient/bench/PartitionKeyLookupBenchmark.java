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

import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.channel.ChannelPoolPartitioning.PerHostChannelPoolPartitioning;
import org.asynchttpclient.proxy.ProxyServer;
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A pool lookup as a request does it: build the partition key for the target, then hash and compare it
 * against the key already in the pool's map. {@code virtualHost} and {@code proxy} build a
 * {@code CompositePartitionKey}; {@code plain} is the per-host key, for reference.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PartitionKeyLookupBenchmark {

    @Param({"plain", "virtualHost", "proxy"})
    public String route;

    private final ChannelPoolPartitioning partitioning = PerHostChannelPoolPartitioning.INSTANCE;
    private final ConcurrentHashMap<Object, Object> partitions = new ConcurrentHashMap<>();
    private Uri uri;
    private String virtualHost;
    private ProxyServer proxy;

    @Setup(Level.Trial)
    public void setup() {
        uri = Uri.create("https://api.example.com/v1/items?id=42");
        if ("virtualHost".equals(route)) {
            virtualHost = "tenant.example.com";
        } else if ("proxy".equals(route)) {
            proxy = new ProxyServer.Builder("proxy.example.com", 3128).build();
        }
        // A distinct but equal key, as the pool holds the one an earlier request built.
        partitions.put(partitioning.getPartitionKey(Uri.create(uri.toUrl()), virtualHost, proxy), Boolean.TRUE);
    }

    @Benchmark
    public Object lookup() {
        return partitions.get(partitioning.getPartitionKey(uri, virtualHost, proxy));
    }
}
