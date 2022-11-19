package org.asynchttpclient.util;

import org.asynchttpclient.AsyncHttpClient;

/**
 * An interface that defines useful methods to check how many {@linkplain AsyncHttpClient}
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
