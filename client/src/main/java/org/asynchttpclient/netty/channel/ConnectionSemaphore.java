/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.channel;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.exception.TooManyConnectionsException;
import org.asynchttpclient.exception.TooManyConnectionsPerHostException;

import static io.netty.util.internal.ThrowableUtil.unknownStackTrace;

/**
 * Max connections and max-per-host connections limiter.
 *
 * @author Stepan Koltsov
 */
public class ConnectionSemaphore {

    private final int maxTotalConnections;
    private final NonBlockingSemaphoreLike freeChannels;
    private final int maxConnectionsPerHost;
    private final ConcurrentHashMap<Object, NonBlockingSemaphore> freeChannelsPerHost = new ConcurrentHashMap<>();

    private final IOException tooManyConnections;
    private final IOException tooManyConnectionsPerHost;

    public ConnectionSemaphore(AsyncHttpClientConfig config) {
        tooManyConnections = unknownStackTrace(new TooManyConnectionsException(config.getMaxConnections()), ConnectionSemaphore.class, "acquireChannelLock");
        tooManyConnectionsPerHost = unknownStackTrace(new TooManyConnectionsPerHostException(config.getMaxConnectionsPerHost()), ConnectionSemaphore.class, "acquireChannelLock");
        maxTotalConnections = config.getMaxConnections();
        maxConnectionsPerHost = config.getMaxConnectionsPerHost();

        freeChannels = maxTotalConnections > 0 ?
                new NonBlockingSemaphore(config.getMaxConnections()) :
                NonBlockingSemaphoreInfinite.INSTANCE;
    }

    private boolean tryAcquireGlobal() {
        return freeChannels.tryAcquire();
    }

    private NonBlockingSemaphoreLike getFreeConnectionsForHost(Object partitionKey) {
        return maxConnectionsPerHost > 0 ?
                freeChannelsPerHost.computeIfAbsent(partitionKey, pk -> new NonBlockingSemaphore(maxConnectionsPerHost)) :
                NonBlockingSemaphoreInfinite.INSTANCE;
    }

    private boolean tryAcquirePerHost(Object partitionKey) {
        return getFreeConnectionsForHost(partitionKey).tryAcquire();
    }

    public void acquireChannelLock(Object partitionKey) throws IOException {
        if (!tryAcquireGlobal())
            throw tooManyConnections;
        if (!tryAcquirePerHost(partitionKey)) {
            freeChannels.release();

            throw tooManyConnectionsPerHost;
        }
    }

    public void releaseChannelLock(Object partitionKey) {
        freeChannels.release();
        getFreeConnectionsForHost(partitionKey).release();
    }
}
