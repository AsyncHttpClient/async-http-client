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

import java.util.concurrent.TimeoutException;

import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;
import com.ning.http.client.providers.netty.NettyResponseFuture;

public abstract class TimeoutTimerTask implements TimerTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutTimerTask.class);

    protected final NettyResponseFuture<?> nettyResponseFuture;
    protected final NettyAsyncHttpProvider provider;
    protected final TimeoutsHolder timeoutsHolder;

    public TimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture, NettyAsyncHttpProvider provider, TimeoutsHolder timeoutsHolder) {
        this.nettyResponseFuture = nettyResponseFuture;
        this.provider = provider;
        this.timeoutsHolder = timeoutsHolder;
    }

    protected void expire(String message, long time) {
        LOGGER.debug("{} for {} after {} ms", message, nettyResponseFuture, time);
        provider.abort(nettyResponseFuture, new TimeoutException(message));
    }
}
