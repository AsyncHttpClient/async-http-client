package org.asynchttpclient.util;

/**
 * An interface that defines useful methods to track how many {@linkplain org.asynchttpclient.AsyncHttpClient}
 * instances share this particular implementation.
 * <p>
 * This interface provides thread-safe counter operations for managing shared resources across
 * multiple client instances. Implementations should ensure atomic operations for increment,
 * decrement, and count retrieval.
 * </p>
 */
public interface Counted {

    /**
     * Atomically increments the counter by one and returns the updated value.
     *
     * @return the updated counter value after incrementing
     */
    int incrementAndGet();

    /**
     * Atomically decrements the counter by one and returns the updated value.
     *
     * @return the updated counter value after decrementing
     */
    int decrementAndGet();

    /**
     * Returns the current value of the counter.
     * <p>
     * Note: The returned value is a snapshot and may change immediately after this method returns
     * if other threads are concurrently modifying the counter.
     * </p>
     *
     * @return the current counter value
     */
    int count();
}
