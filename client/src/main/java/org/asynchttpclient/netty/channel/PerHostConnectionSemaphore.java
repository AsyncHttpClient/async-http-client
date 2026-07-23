/*
 *    Copyright (c) 2018-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.channel;

import org.asynchttpclient.exception.TooManyConnectionsPerHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;

/**
 * Max per-host connections limiter.
 */
public class PerHostConnectionSemaphore implements ConnectionSemaphore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PerHostConnectionSemaphore.class);

    private static final class TrackedSemaphore extends Semaphore {

        private static final long serialVersionUID = 1L;

        // Guarded by freeChannelsPerHost.compute/computeIfPresent for this entry's key.
        private int references;

        private TrackedSemaphore(int permits) {
            super(permits);
        }
    }

    protected final ConcurrentHashMap<Object, Semaphore> freeChannelsPerHost = new ConcurrentHashMap<>();
    protected final int maxConnectionsPerHost;
    protected final IOException tooManyConnectionsPerHost;
    protected final int acquireTimeout;

    PerHostConnectionSemaphore(int maxConnectionsPerHost, int acquireTimeout) {
        tooManyConnectionsPerHost = unknownStackTrace(new TooManyConnectionsPerHostException(maxConnectionsPerHost),
                PerHostConnectionSemaphore.class, "acquireChannelLock");
        this.maxConnectionsPerHost = maxConnectionsPerHost;
        this.acquireTimeout = Math.max(0, acquireTimeout);
    }

    @Override
    public void acquireChannelLock(Object partitionKey) throws IOException {
        acquireChannelLock(partitionKey, false);
    }

    @Override
    public void acquireChannelLock(Object partitionKey, boolean nonBlocking) throws IOException {
        Semaphore freeConnections = reserveFreeConnectionsForHost(partitionKey);
        boolean acquired = false;
        try {
            // nonBlocking (the caller is on the event loop): try once and fail fast rather than parking
            // the loop for up to acquireTimeout.
            acquired = nonBlocking
                    ? freeConnections.tryAcquire()
                    : freeConnections.tryAcquire(acquireTimeout, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw tooManyConnectionsPerHost;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (!acquired) {
                releaseHostReservation(partitionKey, freeConnections);
            }
        }
    }

    @Override
    public void releaseChannelLock(Object partitionKey) {
        if (maxConnectionsPerHost <= 0) {
            return;
        }
        Semaphore freeConnections = freeChannelsPerHost.get(partitionKey);
        if (freeConnections != null) {
            freeConnections.release();
            releaseHostReservation(partitionKey, freeConnections);
        } else {
            LOGGER.debug("releaseChannelLock called for partition key {} with no matching reservation; ignoring "
                    + "(likely a duplicate release)", partitionKey);
        }
    }

    final Semaphore reserveFreeConnectionsForHost(Object partitionKey) {
        if (maxConnectionsPerHost <= 0) {
            return InfiniteSemaphore.INSTANCE;
        }
        return freeChannelsPerHost.compute(partitionKey, (pk, current) -> {
            TrackedSemaphore semaphore = current == null
                    ? new TrackedSemaphore(maxConnectionsPerHost)
                    : (TrackedSemaphore) current;
            semaphore.references++;
            return semaphore;
        });
    }

    final void releaseHostReservation(Object partitionKey, Semaphore expected) {
        if (maxConnectionsPerHost <= 0) {
            return;
        }
        freeChannelsPerHost.computeIfPresent(partitionKey, (pk, current) -> {
            if (current != expected) {
                LOGGER.debug("releaseHostReservation for partition key {} found a different semaphore instance "
                        + "than expected; leaving it untouched (likely a duplicate release racing a prune)", pk);
                return current;
            }
            TrackedSemaphore semaphore = (TrackedSemaphore) current;
            semaphore.references--;
            return semaphore.references == 0 ? null : semaphore;
        });
    }

    /**
     * @deprecated does not participate in the reference-counted reservation bookkeeping that {@link
     * #acquireChannelLock(Object, boolean)}/{@link #releaseChannelLock(Object)} rely on to prune idle
     * host entries, so a permit acquired through the returned {@link Semaphore} should be released
     * through the same instance rather than through {@link #releaseChannelLock(Object)}. Delegates to
     * {@link #reserveFreeConnectionsForHost(Object)} so the returned instance is always the map's
     * current {@code TrackedSemaphore} for this key, kept only for binary compatibility.
     */
    @Deprecated
    protected Semaphore getFreeConnectionsForHost(Object partitionKey) {
        return reserveFreeConnectionsForHost(partitionKey);
    }
}
