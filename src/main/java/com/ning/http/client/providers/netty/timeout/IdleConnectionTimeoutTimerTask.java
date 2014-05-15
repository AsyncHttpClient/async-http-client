/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.providers.netty.timeout;

import static com.ning.http.util.DateUtil.millisTime;

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
        if (provider.isClose()) {
            timeoutsHolder.cancel();
            return;
        }

        if (!nettyResponseFuture.isDone() && !nettyResponseFuture.isCancelled()) {

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

        } else {
            timeoutsHolder.cancel();
        }
    }
}