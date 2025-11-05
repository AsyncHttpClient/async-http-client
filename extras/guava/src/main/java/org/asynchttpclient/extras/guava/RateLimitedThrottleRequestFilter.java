package org.asynchttpclient.extras.guava;

import com.google.common.util.concurrent.RateLimiter;
import org.asynchttpclient.filter.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A {@link org.asynchttpclient.filter.RequestFilter} that extends the capability of
 * {@link ThrottleRequestFilter} by allowing rate limiting per second in addition to the
 * number of concurrent connections.
 * <p>
 * The <code>maxWaitMs</code> argument is respected across both permit acquisitions. For
 * example, if 1000 ms is given, and the filter spends 500 ms waiting for a connection,
 * it will only spend another 500 ms waiting for the rate limiter.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Limit to 10 concurrent connections and 5 requests per second
 * RateLimitedThrottleRequestFilter filter =
 *     new RateLimitedThrottleRequestFilter(10, 5.0);
 *
 * AsyncHttpClient client = asyncHttpClient(config()
 *     .addRequestFilter(filter)
 *     .build());
 *
 * // With custom max wait time of 2 seconds
 * RateLimitedThrottleRequestFilter customFilter =
 *     new RateLimitedThrottleRequestFilter(10, 5.0, 2000);
 * }</pre>
 */
public class RateLimitedThrottleRequestFilter implements RequestFilter {
  private final static Logger logger = LoggerFactory.getLogger(RateLimitedThrottleRequestFilter.class);
  private final Semaphore available;
  private final int maxWaitMs;
  private final RateLimiter rateLimiter;

  /**
   * Creates a rate-limited throttle filter with default maximum wait time.
   * <p>
   * The maximum wait time defaults to {@link Integer#MAX_VALUE}, effectively
   * allowing requests to wait indefinitely for permits.
   *
   * @param maxConnections the maximum number of concurrent connections allowed
   * @param rateLimitPerSecond the maximum rate of requests per second
   */
  public RateLimitedThrottleRequestFilter(int maxConnections, double rateLimitPerSecond) {
    this(maxConnections, rateLimitPerSecond, Integer.MAX_VALUE);
  }

  /**
   * Creates a rate-limited throttle filter with a specified maximum wait time.
   * <p>
   * This filter enforces both connection concurrency limits and rate limiting.
   * The maxWaitMs applies to the total time spent waiting for both permits.
   *
   * @param maxConnections the maximum number of concurrent connections allowed
   * @param rateLimitPerSecond the maximum rate of requests per second
   * @param maxWaitMs the maximum time in milliseconds to wait for permits
   */
  public RateLimitedThrottleRequestFilter(int maxConnections, double rateLimitPerSecond, int maxWaitMs) {
    this.maxWaitMs = maxWaitMs;
    this.rateLimiter = RateLimiter.create(rateLimitPerSecond);
    available = new Semaphore(maxConnections, true);
  }

  /**
   * Filters the request by applying both concurrency and rate limiting constraints.
   * <p>
   * This method attempts to acquire both a connection permit and a rate limiter permit
   * before allowing the request to proceed. If either acquisition fails within the
   * configured maximum wait time, a {@link FilterException} is thrown.
   *
   * @param ctx the filter context containing the request and async handler
   * @param <T> the type of the async handler's result
   * @return a new filter context with a handler that releases the connection permit on completion
   * @throws FilterException if the request cannot acquire necessary permits within the maximum wait time
   *                        or if the thread is interrupted while waiting
   */
  @Override
  public <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException {
    try {
      if (logger.isDebugEnabled()) {
        logger.debug("Current Throttling Status {}", available.availablePermits());
      }

      long startOfWait = System.currentTimeMillis();
      attemptConcurrencyPermitAcquisition(ctx);

      attemptRateLimitedPermitAcquisition(ctx, startOfWait);
    } catch (InterruptedException e) {
      throw new FilterException(String.format("Interrupted Request %s with AsyncHandler %s", ctx.getRequest(), ctx.getAsyncHandler()));
    }

    return new FilterContext.FilterContextBuilder<>(ctx)
            .asyncHandler(ReleasePermitOnComplete.wrap(ctx.getAsyncHandler(), available))
            .build();
  }

  private <T> void attemptRateLimitedPermitAcquisition(FilterContext<T> ctx, long startOfWait) throws FilterException {
    long wait = getMillisRemainingInMaxWait(startOfWait);

    if (!rateLimiter.tryAcquire(wait, TimeUnit.MILLISECONDS)) {
      throw new FilterException(String.format("Wait for rate limit exceeded during processing Request %s with AsyncHandler %s",
              ctx.getRequest(), ctx.getAsyncHandler()));
    }
  }

  private <T> void attemptConcurrencyPermitAcquisition(FilterContext<T> ctx) throws InterruptedException, FilterException {
    if (!available.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS)) {
      throw new FilterException(String.format("No slot available for processing Request %s with AsyncHandler %s", ctx.getRequest(),
              ctx.getAsyncHandler()));
    }
  }

  private long getMillisRemainingInMaxWait(long startOfWait) {
    int MINUTE_IN_MILLIS = 60000;
    long durationLeft = maxWaitMs - (System.currentTimeMillis() - startOfWait);
    long nonNegativeDuration = Math.max(durationLeft, 0);

    // have to reduce the duration because there is a boundary case inside the Guava
    // rate limiter where if the duration to wait is near Long.MAX_VALUE, the rate
    // limiter's internal calculations can exceed Long.MAX_VALUE resulting in a
    // negative number which causes the tryAcquire() method to fail unexpectedly
    if (Long.MAX_VALUE - nonNegativeDuration < MINUTE_IN_MILLIS) {
      return nonNegativeDuration - MINUTE_IN_MILLIS;
    }

    return nonNegativeDuration;
  }
}
