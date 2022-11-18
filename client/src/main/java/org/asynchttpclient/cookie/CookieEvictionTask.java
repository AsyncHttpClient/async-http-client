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
