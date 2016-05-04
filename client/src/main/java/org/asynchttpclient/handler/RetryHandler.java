package org.asynchttpclient.handler;

/**
 * Created by charlie.chang on 5/3/16.
 */
public interface RetryHandler {
    long nextRetryMillis();
    void reset();
}
