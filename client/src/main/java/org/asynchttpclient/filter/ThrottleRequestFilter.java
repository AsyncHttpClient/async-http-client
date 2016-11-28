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

import org.asynchttpclient.AsyncHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A {@link org.asynchttpclient.filter.RequestFilter} throttles requests and block when the number of permits is reached,
 * waiting for the response to arrives before executing the next request.
 */
public class ThrottleRequestFilter implements RequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(ThrottleRequestFilter.class);
    private final Semaphore available;
    private final int maxWait;

    public ThrottleRequestFilter(int maxConnections) {
        this(maxConnections, Integer.MAX_VALUE);
    }

    public ThrottleRequestFilter(int maxConnections, int maxWait) {
        this(maxConnections, maxWait, false);
    }

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

        return new FilterContext.FilterContextBuilder<>(ctx).asyncHandler(wrap(ctx.getAsyncHandler())).build();
    }

    /**
     * Wrap {@link AsyncHandler} to release the permit of {@link #available} on {@link AsyncHandler#onCompleted()}.
     * This is done via a dynamic proxy to keep all interfaces of the wrapped handler.
     */
    protected <T> AsyncHandler<T> wrap(AsyncHandler<T> handler) {
        Class<?> handlerClass = handler.getClass();
        ClassLoader classLoader = handlerClass.getClassLoader();
        Class<?>[] interfaces = handlerClass.getInterfaces();

        return (AsyncHandler<T>) Proxy.newProxyInstance(classLoader, interfaces, new Wrapper(handler, available));
    }

    /**
     * Wrapper for {@link AsyncHandler}s to release a permit on {@link AsyncHandler#onCompleted()}.
     */
    static final class Wrapper<T> implements InvocationHandler {
        private final AsyncHandler<T> handler;
        private final Semaphore available;

        public Wrapper(AsyncHandler<T> handler, Semaphore available) {
            this.handler = handler;
            this.available = available;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(handler, args);
            } finally {
                if ("onCompleted".equals(method.getName())) {
                    available.release();
                }
            }
        }
    }
}
