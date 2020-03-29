package org.asynchttpclient.cookie;

import java.util.concurrent.TimeUnit;

import org.asynchttpclient.AsyncHttpClientConfig;

import io.netty.util.Timeout;
import io.netty.util.TimerTask;

/**
 * Evicts expired cookies from the {@linkplain CookieStore} periodically.
 * The default delay is 30 seconds. Ypu may override the default using
 * {@linkplain AsyncHttpClientConfig#expiredCookieEvictionDelay()}.
 */
public class CookieEvictionTask implements TimerTask {

    private long evictDelayInMs;
    private CookieStore cookieStore;

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
