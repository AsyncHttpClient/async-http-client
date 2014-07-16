/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty.timeout;

import static com.ning.http.util.DateUtils.millisTime;

import org.jboss.netty.util.Timeout;

import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;
import com.ning.http.client.providers.netty.NettyResponseFuture;

public class IdleConnectionTimeoutTimerTask extends TimeoutTimerTask {

    private final long idleConnectionTimeout;
    private final long requestTimeoutInstant;

    public IdleConnectionTimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture, NettyAsyncHttpProvider provider, TimeoutsHolder timeoutsHolder,
            long requestTimeout, long idleConnectionTimeout) {
        super(nettyResponseFuture, provider, timeoutsHolder);
        this.idleConnectionTimeout = idleConnectionTimeout;
        requestTimeoutInstant = requestTimeout >= 0 ? nettyResponseFuture.getStart() + requestTimeout : Long.MAX_VALUE;
    }

    public void run(Timeout timeout) throws Exception {

        if (provider.isClose() || nettyResponseFuture.isDone()) {
            timeoutsHolder.cancel();
            return;
        }

        long now = millisTime();

        long currentIdleConnectionTimeoutInstant = idleConnectionTimeout + nettyResponseFuture.getLastTouch();
        long durationBeforeCurrentIdleConnectionTimeout = currentIdleConnectionTimeoutInstant - now;

        if (durationBeforeCurrentIdleConnectionTimeout <= 0L) {
            // idleConnectionTimeout reached
            String message = "Idle connection timeout to " + nettyResponseFuture.getChannelRemoteAddress() + " of " + idleConnectionTimeout + " ms";
            long durationSinceLastTouch = now - nettyResponseFuture.getLastTouch();
            expire(message, durationSinceLastTouch);
            nettyResponseFuture.setIdleConnectionTimeoutReached();

        } else if (currentIdleConnectionTimeoutInstant < requestTimeoutInstant) {
            // reschedule
            timeoutsHolder.idleConnectionTimeout = provider.newTimeoutInMs(this, durationBeforeCurrentIdleConnectionTimeout);

        } else {
            // otherwise, no need to reschedule: requestTimeout will happen sooner
            timeoutsHolder.idleConnectionTimeout = null;
        }
    }
}
