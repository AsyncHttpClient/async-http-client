package org.asynchttpclient.handler;

public class ExponentialRetryHandler implements RetryHandler {

    private final int initialIntervalMs;
    private final int maxIntervalMs;
    private final float multiplier;
    private int interval;

    public ExponentialRetryHandler(int initialIntervalMs, int maxIntervalMs, float multiplier) {
        this.initialIntervalMs = initialIntervalMs;
        this.interval = initialIntervalMs;
        this.maxIntervalMs = maxIntervalMs;
        this.multiplier = multiplier;
    }

    @Override public long nextRetryMillis() {

        final int currentInterval = interval;

        if(interval >= maxIntervalMs / multiplier) {
            interval = maxIntervalMs;
        } else {
            interval = (int)(interval * multiplier);
        }

        return currentInterval;
    }

    @Override public void reset() {
        this.interval = initialIntervalMs;
    }
}
