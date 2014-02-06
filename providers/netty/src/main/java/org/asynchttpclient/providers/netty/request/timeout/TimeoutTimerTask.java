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

import io.netty.util.TimerTask;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TimeoutTimerTask implements TimerTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutTimerTask.class);

    protected final NettyResponseFuture<?> nettyResponseFuture;
    protected final Channels channels;
    protected final TimeoutsHolder timeoutsHolder;
    protected final AtomicBoolean clientClosed;

    public TimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture, Channels channels, TimeoutsHolder timeoutsHolder, AtomicBoolean clientClosed) {
        this.nettyResponseFuture = nettyResponseFuture;
        this.channels = channels;
        this.timeoutsHolder = timeoutsHolder;
        this.clientClosed = clientClosed;
    }

    protected void expire(String message, long ms) {
        LOGGER.debug("{} for {} after {} ms", message, nettyResponseFuture, ms);
        channels.abort(nettyResponseFuture, new TimeoutException(message));
    }
}
