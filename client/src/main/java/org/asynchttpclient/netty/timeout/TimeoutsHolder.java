/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;

public class TimeoutsHolder {

    private final Timeout requestTimeout;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Timer nettyTimer;
    private final NettyRequestSender requestSender;
    private final long requestTimeoutMillisTime;
    private final int readTimeoutValue;
    private volatile Timeout readTimeout;
    private final NettyResponseFuture<?> nettyResponseFuture;
    private volatile InetSocketAddress remoteAddress;

    public TimeoutsHolder(Timer nettyTimer, NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender,
                          AsyncHttpClientConfig config, InetSocketAddress originalRemoteAddress) {
        this.nettyTimer = nettyTimer;
        this.nettyResponseFuture = nettyResponseFuture;
        this.requestSender = requestSender;
        remoteAddress = originalRemoteAddress;

        final Request targetRequest = nettyResponseFuture.getTargetRequest();

        final int readTimeoutInMs = targetRequest.getReadTimeout();
        readTimeoutValue = readTimeoutInMs == 0 ? config.getReadTimeout() : readTimeoutInMs;

        long requestTimeoutInMs = targetRequest.getRequestTimeout().toMillis();
        if (requestTimeoutInMs == 0) {
            requestTimeoutInMs = config.getRequestTimeout().toMillis();
        }

        if (requestTimeoutInMs > -1) {
            requestTimeoutMillisTime = unpreciseMillisTime() + requestTimeoutInMs;
            requestTimeout = newTimeout(new RequestTimeoutTimerTask(nettyResponseFuture, requestSender, this, requestTimeoutInMs), requestTimeoutInMs);
        } else {
            requestTimeoutMillisTime = -1L;
            requestTimeout = null;
        }
    }

    public void setResolvedRemoteAddress(InetSocketAddress address) {
        remoteAddress = address;
    }

    InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    public void startReadTimeout() {
        if (readTimeoutValue != -1) {
            startReadTimeout(null);
        }
    }

    void startReadTimeout(ReadTimeoutTimerTask task) {
        if (requestTimeout == null || !requestTimeout.isExpired() && readTimeoutValue < requestTimeoutMillisTime - unpreciseMillisTime()) {
            // only schedule a new readTimeout if the requestTimeout doesn't happen first
            if (task == null) {
                // first call triggered from outside (else is read timeout is re-scheduling itself)
                task = new ReadTimeoutTimerTask(nettyResponseFuture, requestSender, this, readTimeoutValue);
            }
            readTimeout = newTimeout(task, readTimeoutValue);

        } else if (task != null) {
            // read timeout couldn't re-scheduling itself, clean up
            task.clean();
        }
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            if (requestTimeout != null) {
                requestTimeout.cancel();
                ((TimeoutTimerTask) requestTimeout.task()).clean();
            }
            if (readTimeout != null) {
                readTimeout.cancel();
                ((TimeoutTimerTask) readTimeout.task()).clean();
            }
        }
    }

    private Timeout newTimeout(TimerTask task, long delay) {
        return requestSender.isClosed() ? null : nettyTimer.newTimeout(task, delay, TimeUnit.MILLISECONDS);
    }
}
