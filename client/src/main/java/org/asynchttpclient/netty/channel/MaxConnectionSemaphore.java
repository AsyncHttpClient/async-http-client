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

import org.asynchttpclient.exception.TooManyConnectionsException;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;

/**
 * Max connections limiter.
 *
 * @author Stepan Koltsov
 * @author Alex Maltinsky
 */
public class MaxConnectionSemaphore implements ConnectionSemaphore {

    protected final Semaphore freeChannels;
    protected final IOException tooManyConnections;
    protected final int acquireTimeout;

    MaxConnectionSemaphore(int maxConnections, int acquireTimeout) {
        tooManyConnections = unknownStackTrace(new TooManyConnectionsException(maxConnections), MaxConnectionSemaphore.class, "acquireChannelLock");
        freeChannels = maxConnections > 0 ? new Semaphore(maxConnections) : InfiniteSemaphore.INSTANCE;
        this.acquireTimeout = Math.max(0, acquireTimeout);
    }

    @Override
    public void acquireChannelLock(Object partitionKey) throws IOException {
        try {
            if (!freeChannels.tryAcquire(acquireTimeout, TimeUnit.MILLISECONDS)) {
                throw tooManyConnections;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void releaseChannelLock(Object partitionKey) {
        freeChannels.release();
    }
}
