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
package org.asynchttpclient.uri;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@link UriParser} per-thread scratch reuse: a parse must never see a field bled in from a
 * previous parse on the same thread, and concurrent parses on different threads must not cross-contaminate.
 */
public class UriParserReuseTest {

    @Test
    public void backToBackParseDoesNotBleedFields() {
        // A rich URL populates every field (userInfo, port, query, fragment).
        UriParser rich = UriParser.parse(null, "http://user:pass@host.example.com:8443/a/b?q=1#frag");
        assertEquals("host.example.com", rich.host);
        assertEquals("user:pass", rich.userInfo);
        assertEquals(8443, rich.port);
        assertEquals("/a/b", rich.path);
        assertEquals("q=1", rich.query);
        assertEquals("frag", rich.fragment);

        // A sparse URL on the SAME thread reuses the scratch: every field the sparse URL does not set must
        // be reset, not carry over from the rich parse.
        UriParser sparse = UriParser.parse(null, "http://host2/");
        assertEquals("host2", sparse.host);
        assertNull(sparse.userInfo, "userInfo must not bleed from the previous parse");
        assertEquals(-1, sparse.port, "port must be reset to -1, not bleed 8443");
        assertEquals("/", sparse.path);
        assertNull(sparse.query, "query must not bleed");
        assertNull(sparse.fragment, "fragment must not bleed");
    }

    @Test
    public void parseAfterSchemeRelativeDoesNotBleedScheme() {
        UriParser https = UriParser.parse(null, "https://secure.example.com/x");
        assertEquals("https", https.scheme);

        // Bare host with no scheme: scheme must be null, not carry "https".
        UriParser noScheme = UriParser.parse(null, "//other.example.com/y");
        assertNull(noScheme.scheme, "scheme must not bleed from the previous parse");
        assertEquals("other.example.com", noScheme.host);
    }

    @Test
    @Timeout(30)
    public void concurrentParsesDoNotCrossContaminate() throws InterruptedException {
        final int threads = 16;
        final int iterations = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger mismatches = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int id = t;
            // Each thread always parses the SAME distinct URL; if the thread-local scratch ever leaked
            // across threads, a thread would observe another thread's host/port.
            final String host = "h" + id + ".example.com";
            final int port = 1000 + id;
            final String url = "http://" + host + ':' + port + "/p" + id + "?k=" + id;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        UriParser p = UriParser.parse(null, url);
                        if (!host.equals(p.host) || p.port != port || !("k=" + id).equals(p.query)) {
                            mismatches.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(25, TimeUnit.SECONDS), "workers should finish in time");
        pool.shutdownNow();
        assertEquals(0, mismatches.get(), "no thread should observe another thread's parse fields");
    }
}
