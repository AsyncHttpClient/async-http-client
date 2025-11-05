package org.asynchttpclient.cookie;

import java.util.concurrent.TimeUnit;

import org.asynchttpclient.AsyncHttpClientConfig;

import io.netty.util.Timeout;
import io.netty.util.TimerTask;

/**
 * Periodic task that evicts expired cookies from a {@link CookieStore}.
 * This task runs at a configurable interval to clean up cookies that have passed
 * their expiration time, preventing memory leaks from accumulating expired cookies.
 *
 * <p>The default eviction delay is 30 seconds. You can override this using
 * {@link AsyncHttpClientConfig#expiredCookieEvictionDelay()}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Configure custom eviction delay (in milliseconds)
 * AsyncHttpClient client = Dsl.asyncHttpClient(
 *     new DefaultAsyncHttpClientConfig.Builder()
 *         .setExpiredCookieEvictionDelay(60000) // 60 seconds
 *         .build()
 * );
 * }</pre>
 */
public class CookieEvictionTask implements TimerTask {

    private final long evictDelayInMs;
    private final CookieStore cookieStore;

    /**
     * Constructs a new cookie eviction task.
     *
     * @param evictDelayInMs the delay in milliseconds between eviction runs
     * @param cookieStore the cookie store to evict expired cookies from
     */
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
