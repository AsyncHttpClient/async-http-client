/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.timeout;

import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;
import io.netty.util.Timeout;

import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;

public class ReadTimeoutTimerTask extends TimeoutTimerTask {

    private final long readTimeout;

    public ReadTimeoutTimerTask(//
            NettyResponseFuture<?> nettyResponseFuture,//
            NettyRequestSender requestSender,//
            TimeoutsHolder timeoutsHolder,//
            int readTimeout) {
        super(nettyResponseFuture, requestSender, timeoutsHolder);
        this.readTimeout = readTimeout;
    }

    public void run(Timeout timeout) throws Exception {

        if (done.getAndSet(true) || requestSender.isClosed())
            return;
        
        if (nettyResponseFuture.isDone()) {
            timeoutsHolder.cancel();
            return;
        }

        long now = unpreciseMillisTime();

        long currentReadTimeoutInstant = readTimeout + nettyResponseFuture.getLastTouch();
        long durationBeforeCurrentReadTimeout = currentReadTimeoutInstant - now;

        if (durationBeforeCurrentReadTimeout <= 0L) {
            // idleConnectTimeout reached
            String message = "Read timeout to " + timeoutsHolder.remoteAddress() + " after " + readTimeout + " ms";
            long durationSinceLastTouch = now - nettyResponseFuture.getLastTouch();
            expire(message, durationSinceLastTouch);
            // cancel request timeout sibling
            timeoutsHolder.cancel();

        } else {
            done.set(false);
            timeoutsHolder.startReadTimeout(this);
        }
    }
}
