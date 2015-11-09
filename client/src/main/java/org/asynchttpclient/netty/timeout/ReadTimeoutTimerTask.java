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

import static org.asynchttpclient.util.DateUtils.millisTime;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Timeout;

import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;

public class ReadTimeoutTimerTask extends TimeoutTimerTask {

    private final long readTimeout;
    private final long requestTimeoutInstant;
    private final HttpRequest httpRequest;

    public ReadTimeoutTimerTask(//
            NettyResponseFuture<?> nettyResponseFuture,//
            NettyRequestSender requestSender,//
            TimeoutsHolder timeoutsHolder,//
            long requestTimeout,//
            long readTimeout) {
        this(nettyResponseFuture, requestSender, timeoutsHolder, requestTimeout, readTimeout, null);
    }

    public ReadTimeoutTimerTask(//
            NettyResponseFuture<?> nettyResponseFuture,//
            NettyRequestSender requestSender,//
            TimeoutsHolder timeoutsHolder,//
            long requestTimeout,//
            long readTimeout,//
            HttpRequest httpRequest) {
        super(nettyResponseFuture, requestSender, timeoutsHolder);
        this.readTimeout = readTimeout;
        this.httpRequest = httpRequest;
        requestTimeoutInstant = requestTimeout >= 0 ? nettyResponseFuture.getStart() + requestTimeout : Long.MAX_VALUE;
    }

    public void run(Timeout timeout) throws Exception {

        if (done.getAndSet(true) || requestSender.isClosed())
            return;
        
        if (nettyResponseFuture.isDone()) {
            timeoutsHolder.cancel();
            return;
        }

        long now = millisTime();

        long currentReadTimeoutInstant = readTimeout + nettyResponseFuture.getLastTouch();
        long durationBeforeCurrentReadTimeout = currentReadTimeoutInstant - now;

        if (durationBeforeCurrentReadTimeout <= 0L) {
            // idleConnectTimeout reached
            String message;
            if (httpRequest == null) {
                message = "Read timeout to " + remoteAddress + " of " + readTimeout + " ms";
            } else {
                message = "Read timeout to " + httpRequest.getUri() + " of " + readTimeout + " ms";
            }
            long durationSinceLastTouch = now - nettyResponseFuture.getLastTouch();
            expire(message, durationSinceLastTouch);
            // cancel request timeout sibling
            timeoutsHolder.cancel();

        } else if (currentReadTimeoutInstant < requestTimeoutInstant) {
            // reschedule
            done.set(false);
            timeoutsHolder.readTimeout = requestSender.newTimeout(this, durationBeforeCurrentReadTimeout);

        } else {
            // otherwise, no need to reschedule: requestTimeout will happen sooner
            timeoutsHolder.readTimeout = null;
        }
    }
}
