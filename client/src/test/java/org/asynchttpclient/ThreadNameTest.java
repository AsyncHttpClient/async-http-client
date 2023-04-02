/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests configured client name is used for thread names.
 *
 * @author Stepan Koltsov
 */
public class ThreadNameTest extends AbstractBasicTest {

    private static Thread[] getThreads() {
        int count = Thread.activeCount() + 1;
        for (; ; ) {
            Thread[] threads = new Thread[count];
            int filled = Thread.enumerate(threads);
            if (filled < threads.length) {
                return Arrays.copyOf(threads, filled);
            }

            count *= 2;
        }
    }

    @Test
    public void testThreadName() throws Exception {
        registerRequest();

        String threadPoolName = "ahc-" + (new Random().nextLong() & 0x7fffffffffffffffL);
        try (AsyncHttpClient client = asyncHttpClient(config().setThreadPoolName(threadPoolName))) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/').execute();
            f.get(3, TimeUnit.SECONDS);

            // We cannot assert that all threads are created with specified name,
            // so we checking that at least one thread is.
            boolean found = false;
            for (Thread thread : getThreads()) {
                if (thread.getName().startsWith(threadPoolName)) {
                    found = true;
                    break;
                }
            }

            assertTrue(found, "must found threads starting with random string " + threadPoolName);
        } finally {
            deregisterRequest();
        }
    }
}
