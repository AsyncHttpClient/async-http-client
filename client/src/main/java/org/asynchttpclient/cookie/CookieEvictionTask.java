/*
 *    Copyright (c) 2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.cookie;

import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import org.asynchttpclient.AsyncHttpClientConfig;

import java.util.concurrent.TimeUnit;

/**
 * Evicts expired cookies from the {@linkplain CookieStore} periodically.
 * The default delay is 30 seconds. You may override the default using
 * {@linkplain AsyncHttpClientConfig#expiredCookieEvictionDelay()}.
 */
public class CookieEvictionTask implements TimerTask {

    private final long evictDelayInMs;
    private final CookieStore cookieStore;

    public CookieEvictionTask(long evictDelayInMs, CookieStore cookieStore) {
        this.evictDelayInMs = evictDelayInMs;
        this.cookieStore = cookieStore;
    }

    @Override
    public void run(Timeout timeout) throws Exception {
        cookieStore.evictExpired();
        timeout.timer().newTimeout(this, evictDelayInMs, TimeUnit.MILLISECONDS);
    }
}
