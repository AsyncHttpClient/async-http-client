/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A {@link RequestFilter} that throttles concurrent requests using a semaphore-based mechanism.
 * This filter blocks new requests when the maximum number of concurrent requests is reached,
 * waiting for responses to complete before allowing new requests to proceed.
 *
 * <p>The filter automatically releases permits when requests complete (either successfully or with an error),
 * using the {@link ReleasePermitOnComplete} wrapper.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Limit to 10 concurrent requests, wait indefinitely for a slot
 * ThrottleRequestFilter throttle = new ThrottleRequestFilter(10);
 *
 * // Limit to 5 concurrent requests, wait max 5 seconds for a slot
 * ThrottleRequestFilter throttle = new ThrottleRequestFilter(5, 5000);
 *
 * // Limit to 20 concurrent requests with fair semaphore scheduling
 * ThrottleRequestFilter throttle = new ThrottleRequestFilter(20, Integer.MAX_VALUE, true);
 *
 * // Register with client
 * AsyncHttpClient client = Dsl.asyncHttpClient(
 *     new DefaultAsyncHttpClientConfig.Builder()
 *         .addRequestFilter(throttle)
 *         .build()
 * );
 * }</pre>
 */
public class ThrottleRequestFilter implements RequestFilter {
  private static final Logger logger = LoggerFactory.getLogger(ThrottleRequestFilter.class);
  private final Semaphore available;
  private final int maxWait;

  /**
   * Constructs a throttle filter with the specified maximum concurrent requests.
   * Requests will wait indefinitely for a permit to become available.
   *
   * @param maxConnections the maximum number of concurrent requests
   */
  public ThrottleRequestFilter(int maxConnections) {
    this(maxConnections, Integer.MAX_VALUE);
  }

  /**
   * Constructs a throttle filter with the specified maximum concurrent requests and wait timeout.
   *
   * @param maxConnections the maximum number of concurrent requests
   * @param maxWait the maximum time in milliseconds to wait for a permit
   */
  public ThrottleRequestFilter(int maxConnections, int maxWait) {
    this(maxConnections, maxWait, false);
  }

  /**
   * Constructs a throttle filter with full configuration options.
   *
   * @param maxConnections the maximum number of concurrent requests
   * @param maxWait the maximum time in milliseconds to wait for a permit
   * @param fair {@code true} to use fair semaphore scheduling (FIFO), {@code false} for non-fair
   */
  public ThrottleRequestFilter(int maxConnections, int maxWait, boolean fair) {
    this.maxWait = maxWait;
    available = new Semaphore(maxConnections, fair);
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
      if (!available.tryAcquire(maxWait, TimeUnit.MILLISECONDS)) {
        throw new FilterException(String.format("No slot available for processing Request %s with AsyncHandler %s",
                ctx.getRequest(), ctx.getAsyncHandler()));
      }
    } catch (InterruptedException e) {
      throw new FilterException(String.format("Interrupted Request %s with AsyncHandler %s",
              ctx.getRequest(), ctx.getAsyncHandler()));
    }

    return new FilterContext.FilterContextBuilder<>(ctx)
            .asyncHandler(ReleasePermitOnComplete.wrap(ctx.getAsyncHandler(), available))
            .build();
  }
}
