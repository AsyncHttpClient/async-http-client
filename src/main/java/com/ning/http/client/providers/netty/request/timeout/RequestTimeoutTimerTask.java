/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty.request.timeout;

import static com.ning.http.util.DateUtils.millisTime;

import org.jboss.netty.util.Timeout;

import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.NettyRequestSender;

public class RequestTimeoutTimerTask extends TimeoutTimerTask {

    private final long requestTimeout;

    public RequestTimeoutTimerTask(//
            NettyResponseFuture<?> nettyResponseFuture,//
            NettyRequestSender requestSender,//
            TimeoutsHolder timeoutsHolder,//
            long requestTimeout) {
        super(nettyResponseFuture, requestSender, timeoutsHolder);
        this.requestTimeout = requestTimeout;
    }

    public void run(Timeout timeout) throws Exception {

        if (done.getAndSet(true) || requestSender.isClosed())
            return;

        // in any case, cancel possible readTimeout sibling
        timeoutsHolder.cancel();

        if (nettyResponseFuture.isDone())
            return;

        String message = "Request timed out to " + remoteAddress + " of " + requestTimeout + " ms";
        long age = millisTime() - nettyResponseFuture.getStart();
        expire(message, age);
    }
}
