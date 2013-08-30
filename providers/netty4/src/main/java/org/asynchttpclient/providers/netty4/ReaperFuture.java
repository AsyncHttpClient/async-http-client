package org.asynchttpclient.providers.netty4;

import static org.asynchttpclient.util.DateUtil.millisTime;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHttpClientConfig;

/**
 * Because some implementation of the ThreadSchedulingService do not clean up cancel task until they try to run them, we wrap the task with the future so the when the NettyResponseFuture cancel the reaper future this wrapper will release the references to the channel and the
 * nettyResponseFuture immediately. Otherwise, the memory referenced this way will only be released after the request timeout period which can be arbitrary long.
 */
public final class ReaperFuture implements Runnable {

    private final AtomicBoolean isClose;
    private final Channels channels;
    private Future<?> scheduledFuture;
    private NettyResponseFuture<?> nettyResponseFuture;
    private AsyncHttpClientConfig config;

    public ReaperFuture(NettyResponseFuture<?> nettyResponseFuture, AsyncHttpClientConfig config, AtomicBoolean isClose, Channels channels) {
        this.nettyResponseFuture = nettyResponseFuture;
        this.channels = channels;
        this.config = config;
        this.isClose = isClose;
    }

    public void setScheduledFuture(Future<?> scheduledFuture) {
        this.scheduledFuture = scheduledFuture;
    }

    /**
     * @Override
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        nettyResponseFuture = null;
        return scheduledFuture.cancel(mayInterruptIfRunning);
    }

    /**
     * @Override
     */
    public Object get() throws InterruptedException, ExecutionException {
        return scheduledFuture.get();
    }

    /**
     * @Override
     */
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return scheduledFuture.get(timeout, unit);
    }

    /**
     * @Override
     */
    public boolean isCancelled() {
        return scheduledFuture.isCancelled();
    }

    /**
     * @Override
     */
    public boolean isDone() {
        return scheduledFuture.isDone();
    }

    private void expire(String message) {
        NettyAsyncHttpProvider.LOGGER.debug("{} for {}", message, nettyResponseFuture);
        channels.abort(nettyResponseFuture, new TimeoutException(message));
        nettyResponseFuture = null;
    }

    /**
     * @Override
     */
    public synchronized void run() {
        if (isClose.get()) {
            cancel(true);
            return;
        }

        boolean futureDone = nettyResponseFuture.isDone();
        boolean futureCanceled = nettyResponseFuture.isCancelled();

        if (nettyResponseFuture != null && !futureDone && !futureCanceled) {
            long now = millisTime();
            if (nettyResponseFuture.hasRequestTimedOut(now)) {
                long age = now - nettyResponseFuture.getStart();
                expire("Request reached time out of " + nettyResponseFuture.getRequestTimeoutInMs() + " ms after " + age + " ms");
            } else if (nettyResponseFuture.hasConnectionIdleTimedOut(now)) {
                long age = now - nettyResponseFuture.getStart();
                expire("Request reached idle time out of " + config.getIdleConnectionTimeoutInMs() + " ms after " + age + " ms");
            }

        } else if (nettyResponseFuture == null || futureDone || futureCanceled) {
            cancel(true);
        }
    }
}