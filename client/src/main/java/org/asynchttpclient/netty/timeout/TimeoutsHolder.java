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
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimeoutsHolder {
    private final static Logger LOGGER = LoggerFactory.getLogger(TimeoutsHolder.class);
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private final Timer nettyTimer;
    private final NettyRequestSender requestSender;
    private final long requestTimeoutMillisTime;
    private final int readTimeoutValue;

    private volatile NettyResponseFuture<?> nettyResponseFuture;
    private final Timeout requestTimeout;
    private volatile Timeout readTimeout;
    private volatile Timeout retryTimeout;
    private volatile String remoteAddress = "not-connected";

    public TimeoutsHolder(Timer nettyTimer, NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender, AsyncHttpClientConfig config) {
        this.nettyTimer = nettyTimer;
        this.nettyResponseFuture = nettyResponseFuture;
        this.requestSender = requestSender;
        this.readTimeoutValue = config.getReadTimeout();

        int requestTimeoutInMs = nettyResponseFuture.getTargetRequest().getRequestTimeout();
        if (requestTimeoutInMs == 0) {
            requestTimeoutInMs = config.getRequestTimeout();
        }

        if (requestTimeoutInMs != -1) {
            requestTimeoutMillisTime = millisTime() + requestTimeoutInMs;
            requestTimeout = newTimeout(new RequestTimeoutTimerTask(nettyResponseFuture, requestSender, this, requestTimeoutInMs), requestTimeoutInMs);
        } else {
            requestTimeoutMillisTime = -1L;
            requestTimeout = null;
        }
    }

    public void initRemoteAddress(InetSocketAddress address) {
        remoteAddress = address.toString();
    }

    public void startReadTimeout() {
        if (readTimeoutValue != -1) {
            startReadTimeout(null);
        }
    }

    public void startRetryTimeout() {

        if (retryTimeout != null) {
            retryTimeout.cancel();
            RetryTimerTask.class.cast(retryTimeout.task()).clean();
        }

        final long nextDelayMs = nettyResponseFuture.getRetryHandler().nextRetryMillis();
        retryTimeout = newTimeout(new RetryTimerTask(nettyResponseFuture, requestSender, this),
                        nextDelayMs);

        LOGGER.debug("New RetryTask {} will be ran after {}ms", nettyResponseFuture, nextDelayMs);

    }

    void startReadTimeout(ReadTimeoutTimerTask task) {
        if (requestTimeout == null || (!requestTimeout.isExpired() && readTimeoutValue > (requestTimeoutMillisTime - millisTime()))) {
            // only schedule a new readTimeout if the requestTimeout doesn't happen first
            if (task == null) {
                // first call triggered from outside (else is read timeout is re-scheduling itself)
                task = new ReadTimeoutTimerTask(nettyResponseFuture, requestSender, this, readTimeoutValue);
            }
            Timeout readTimeout = newTimeout(task, readTimeoutValue);
            this.readTimeout = readTimeout;

        } else if (task != null) {
            // read timeout couldn't re-scheduling itself, clean up
            task.clean();
        }
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            if (requestTimeout != null) {
                requestTimeout.cancel();
                RequestTimeoutTimerTask.class.cast(requestTimeout.task()).clean();
            }
            if (readTimeout != null) {
                readTimeout.cancel();
                ReadTimeoutTimerTask.class.cast(readTimeout.task()).clean();
            }
            if(retryTimeout != null) {
                retryTimeout.cancel();
                RetryTimerTask.class.cast(retryTimeout.task()).clean();
            }
        }
    }

    private Timeout newTimeout(TimerTask task, long delay) {
        return nettyTimer.newTimeout(task, delay, TimeUnit.MILLISECONDS);
    }
    
    String remoteAddress() {
        return remoteAddress;
    }
}
