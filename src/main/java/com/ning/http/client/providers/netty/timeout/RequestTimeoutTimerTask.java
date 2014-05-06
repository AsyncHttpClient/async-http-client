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

public class RequestTimeoutTimerTask extends TimeoutTimerTask {

    public RequestTimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture, NettyAsyncHttpProvider provider, TimeoutsHolder timeoutsHolder) {
        super(nettyResponseFuture, provider, timeoutsHolder);
    }

    public void run(Timeout timeout) throws Exception {

        // in any case, cancel possible idleConnectionTimeout
        timeoutsHolder.cancel();

        if (provider.isClose()) {
            return;
        }

        if (!nettyResponseFuture.isDone() && !nettyResponseFuture.isCancelled()) {
            String message = "Request timed out to " + nettyResponseFuture.getChannelRemoteAddress() + " of " + nettyResponseFuture.getRequestTimeoutInMs() + " ms";
            long age = millisTime() - nettyResponseFuture.getStart();
            expire(message, age);
            nettyResponseFuture.setRequestTimeoutReached();
        }
    }
}
