/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.timeout;

import io.netty.util.Timeout;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.NettyResponseBodyControl;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.util.StringBuilderPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;

public class ReadTimeoutTimerTask extends TimeoutTimerTask implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadTimeoutTimerTask.class);

    private final long readTimeout;
    private final AtomicBoolean indefiniteSuspensionWarningLogged = new AtomicBoolean();

    ReadTimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender, TimeoutsHolder timeoutsHolder, long readTimeout) {
        super(nettyResponseFuture, requestSender, timeoutsHolder);
        this.readTimeout = readTimeout;
    }

    /**
     * The event-loop entry point. Nothing below reads the {@link Timeout}, which is the timer's own handle on
     * this task and something an event loop has no equivalent of, so both entry points share one body.
     */
    @Override
    public void run() {
        run(null);
    }

    @Override
    public void run(Timeout timeout) {
        if (done.getAndSet(true) || requestSender.isClosed()) {
            return;
        }

        if (nettyResponseFuture.isDone()) {
            timeoutsHolder.cancel();
            return;
        }

        if (NettyResponseBodyControl.isSuspended(nettyResponseFuture)) {
            warnIfIndefinitelySuspended();
            done.set(false);
            timeoutsHolder.startReadTimeout(this);
            return;
        }

        long now = unpreciseMillisTime();

        long currentReadTimeoutInstant = readTimeout + nettyResponseFuture.getLastTouch();
        long durationBeforeCurrentReadTimeout = currentReadTimeoutInstant - now;

        if (durationBeforeCurrentReadTimeout <= 0L) {
            // idleConnectTimeout reached
            StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder().append("Read timeout to ");
            appendRemoteAddress(sb);
            String message = sb.append(" after ").append(readTimeout).append(" ms").toString();
            long durationSinceLastTouch = now - nettyResponseFuture.getLastTouch();
            expire(message, durationSinceLastTouch);
            // cancel request timeout sibling
            timeoutsHolder.cancel();

        } else {
            done.set(false);
            timeoutsHolder.startReadTimeout(this);
        }
    }

    void warnIfIndefinitelySuspended() {
        if (timeoutsHolder.isRequestTimeoutDisabled()
                && indefiniteSuspensionWarningLogged.compareAndSet(false, true)) {
            LOGGER.warn("Response body reads for {} remain suspended while the request timeout is disabled; "
                            + "the exchange retains its transport resources until it is resumed or canceled",
                    nettyResponseFuture.getUri());
        }
    }
}
