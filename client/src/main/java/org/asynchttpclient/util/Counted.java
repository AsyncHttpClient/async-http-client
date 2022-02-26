package org.asynchttpclient.util;

/**
 * An interface that defines useful methods to check how many {@linkplain org.asynchttpclient.AsyncHttpClient}
 * instances this particular implementation is shared with.
 */
public interface Counted {

    /**
     * Increment counter and return the incremented value
     */
    int incrementAndGet();

    /**
     * Decrement counter and return the decremented value
     */
    int decrementAndGet();

    /**
     * Return the current counter
     */
    int count();
}
