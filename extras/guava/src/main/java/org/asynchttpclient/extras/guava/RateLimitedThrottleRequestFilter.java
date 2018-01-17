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
 * The <code>maxWaitMs</code> argument is respected accross both permit acquistions. For
 * example, if 1000 ms is given, and the filter spends 500 ms waiting for a connection,
 * it will only spend another 500 ms waiting for the rate limiter.
 */
public class RateLimitedThrottleRequestFilter implements RequestFilter {
  private final static Logger logger = LoggerFactory.getLogger(RateLimitedThrottleRequestFilter.class);
  private final Semaphore available;
  private final int maxWaitMs;
  private final RateLimiter rateLimiter;

  public RateLimitedThrottleRequestFilter(int maxConnections, double rateLimitPerSecond) {
    this(maxConnections, rateLimitPerSecond, Integer.MAX_VALUE);
  }

  public RateLimitedThrottleRequestFilter(int maxConnections, double rateLimitPerSecond, int maxWaitMs) {
    this.maxWaitMs = maxWaitMs;
    this.rateLimiter = RateLimiter.create(rateLimitPerSecond);
    available = new Semaphore(maxConnections, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException {
    try {
      if (logger.isDebugEnabled()) {
        logger.debug("Current Throttling Status {}", available.availablePermits());
      }

      long startOfWait = System.currentTimeMillis();
      attemptConcurrencyPermitAcquistion(ctx);

      attemptRateLimitedPermitAcquistion(ctx, startOfWait);
    } catch (InterruptedException e) {
      throw new FilterException(String.format("Interrupted Request %s with AsyncHandler %s", ctx.getRequest(), ctx.getAsyncHandler()));
    }

    return new FilterContext.FilterContextBuilder<>(ctx)
            .asyncHandler(ReleasePermitOnComplete.wrap(ctx.getAsyncHandler(), available))
            .build();
  }

  private <T> void attemptRateLimitedPermitAcquistion(FilterContext<T> ctx, long startOfWait) throws FilterException {
    long wait = getMillisRemainingInMaxWait(startOfWait);

    if (!rateLimiter.tryAcquire(wait, TimeUnit.MILLISECONDS)) {
      throw new FilterException(String.format("Wait for rate limit exceeded during processing Request %s with AsyncHandler %s",
              ctx.getRequest(), ctx.getAsyncHandler()));
    }
  }

  private <T> void attemptConcurrencyPermitAcquistion(FilterContext<T> ctx) throws InterruptedException, FilterException {
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
