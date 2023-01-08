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

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * A combined {@link ConnectionSemaphore} with two limits - a global limit and a per-host limit
 */
public class CombinedConnectionSemaphore extends PerHostConnectionSemaphore {
    protected final MaxConnectionSemaphore globalMaxConnectionSemaphore;

    CombinedConnectionSemaphore(int maxConnections, int maxConnectionsPerHost, int acquireTimeout) {
        super(maxConnectionsPerHost, acquireTimeout);
        globalMaxConnectionSemaphore = new MaxConnectionSemaphore(maxConnections, acquireTimeout);
    }

    @Override
    public void acquireChannelLock(Object partitionKey) throws IOException {
        long remainingTime = acquireTimeout > 0 ? acquireGlobalTimed(partitionKey) : acquireGlobal(partitionKey);

        try {
            if (remainingTime < 0 || !getFreeConnectionsForHost(partitionKey).tryAcquire(remainingTime, TimeUnit.MILLISECONDS)) {
                releaseGlobal(partitionKey);
                throw tooManyConnectionsPerHost;
            }
        } catch (InterruptedException e) {
            releaseGlobal(partitionKey);
            throw new RuntimeException(e);
        }
    }

    protected void releaseGlobal(Object partitionKey) {
        globalMaxConnectionSemaphore.releaseChannelLock(partitionKey);
    }

    protected long acquireGlobal(Object partitionKey) throws IOException {
        globalMaxConnectionSemaphore.acquireChannelLock(partitionKey);
        return 0;
    }

    /*
     * Acquires the global lock and returns the remaining time, in millis, to acquire the per-host lock
     */
    protected long acquireGlobalTimed(Object partitionKey) throws IOException {
        long beforeGlobalAcquire = System.currentTimeMillis();
        acquireGlobal(partitionKey);
        long lockTime = System.currentTimeMillis() - beforeGlobalAcquire;
        return acquireTimeout - lockTime;
    }

    @Override
    public void releaseChannelLock(Object partitionKey) {
        globalMaxConnectionSemaphore.releaseChannelLock(partitionKey);
        super.releaseChannelLock(partitionKey);
    }
}
