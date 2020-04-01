package org.asynchttpclient.util;

/**
 * A contract that has methods to represent how many AHC instances
 * the implementation is shared with.
 */
public interface Counted {
    int incrementAndGet();

    int decrementAndGet();

    int count();
}
