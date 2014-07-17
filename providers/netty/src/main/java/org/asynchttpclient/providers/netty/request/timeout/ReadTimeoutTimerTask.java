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
package org.asynchttpclient.providers.netty.request.timeout;

import static org.asynchttpclient.util.DateUtils.millisTime;

import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;

import io.netty.util.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;

public class ReadTimeoutTimerTask extends TimeoutTimerTask {

    private final long readTimeout;
    private final long requestTimeoutInstant;

    public ReadTimeoutTimerTask(//
            NettyResponseFuture<?> nettyResponseFuture,//
            Channels channels,//
            TimeoutsHolder timeoutsHolder,//
            AtomicBoolean clientClosed,//
            long requestTimeout,//
            long readTimeout) {
        super(nettyResponseFuture, channels, timeoutsHolder, clientClosed);
        this.readTimeout = readTimeout;
        requestTimeoutInstant = requestTimeout >= 0 ? nettyResponseFuture.getStart() + requestTimeout : Long.MAX_VALUE;
    }

    @Override
    public void run(Timeout timeout) throws Exception {
        if (clientClosed.get()) {
            timeoutsHolder.cancel();
            return;
        }

        if (!nettyResponseFuture.isDone() && !nettyResponseFuture.isCancelled()) {

            long now = millisTime();

            long currentReadTimeoutInstant = readTimeout + nettyResponseFuture.getLastTouch();
            long durationBeforeCurrentReadTimeout = currentReadTimeoutInstant - now;

            if (durationBeforeCurrentReadTimeout <= 0L) {
                // idleConnectionTimeout reached
                String message = "Idle connection timeout to " + nettyResponseFuture.getChannelRemoteAddress() + " of " + readTimeout + " ms";
                long durationSinceLastTouch = now - nettyResponseFuture.getLastTouch();
                expire(message, durationSinceLastTouch);
                nettyResponseFuture.setIdleConnectionTimeoutReached();

            } else if (currentReadTimeoutInstant < requestTimeoutInstant) {
                // reschedule
                timeoutsHolder.readTimeout = channels.newTimeoutInMs(this, durationBeforeCurrentReadTimeout);

            } else {
                // otherwise, no need to reschedule: requestTimeout will happen sooner
                timeoutsHolder.readTimeout = null;
            }

        } else {
            timeoutsHolder.cancel();
        }
    }
}
