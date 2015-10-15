package org.asynchttpclient.filter;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncHandlerWrapper<T> implements AsyncHandler<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncHandlerWrapper.class);
    private final AsyncHandler<T> asyncHandler;
    private final Semaphore available;
    private final AtomicBoolean complete = new AtomicBoolean(false);

    public AsyncHandlerWrapper(AsyncHandler<T> asyncHandler, Semaphore available) {
        this.asyncHandler = asyncHandler;
        this.available = available;
    }

    private void complete() {
        if (complete.compareAndSet(false, true))
            available.release();
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Current Throttling Status after onThrowable {}", available.availablePermits());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onThrowable(Throwable t) {
        try {
            asyncHandler.onThrowable(t);
        } finally {
            complete();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
        return asyncHandler.onBodyPartReceived(bodyPart);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
        return asyncHandler.onStatusReceived(responseStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        return asyncHandler.onHeadersReceived(headers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T onCompleted() throws Exception {
        try {
            return asyncHandler.onCompleted();
        } finally {
            complete();
        }
    }
}
