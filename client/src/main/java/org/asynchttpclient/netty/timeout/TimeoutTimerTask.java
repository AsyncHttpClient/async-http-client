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

import io.netty.util.TimerTask;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class TimeoutTimerTask implements TimerTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutTimerTask.class);

    protected final AtomicBoolean done = new AtomicBoolean();
    protected final NettyRequestSender requestSender;
    final TimeoutsHolder timeoutsHolder;
    volatile NettyResponseFuture<?> nettyResponseFuture;

    TimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender, TimeoutsHolder timeoutsHolder) {
        this.nettyResponseFuture = nettyResponseFuture;
        this.requestSender = requestSender;
        this.timeoutsHolder = timeoutsHolder;
    }

    void expire(String message, long time) {
        LOGGER.debug("{} for {} after {} ms", message, nettyResponseFuture, time);
        requestSender.abort(nettyResponseFuture.channel(), nettyResponseFuture, new TimeoutException(message));
    }

    /**
     * When the timeout is cancelled, it could still be referenced for quite some time in the Timer. Holding a reference to the future might mean holding a reference to the
     * channel, and heavy objects such as SslEngines
     */
    public void clean() {
        if (done.compareAndSet(false, true)) {
            nettyResponseFuture = null;
        }
    }

    void appendRemoteAddress(StringBuilder sb) {
        InetSocketAddress remoteAddress = timeoutsHolder.remoteAddress();

        // Guard against null remoteAddress which can happen when the TimeoutsHolder
        // was created without an original remote address (for example when using a
        // pooled channel whose remoteAddress() returned null). In that case fall
        // back to the URI host/port from the request to avoid a NPE and provide
        // a useful diagnostic.
        if (remoteAddress == null) {
            if (nettyResponseFuture != null && nettyResponseFuture.getTargetRequest() != null) {
                try {
                    String host = nettyResponseFuture.getTargetRequest().getUri().getHost();
                    int port = nettyResponseFuture.getTargetRequest().getUri().getExplicitPort();
                    sb.append(host == null ? "unknown" : host);
                    sb.append(':').append(port);
                } catch (Exception ignored) {
                    sb.append("unknown:0");
                }
            } else {
                sb.append("unknown:0");
            }
            return;
        }

        sb.append(remoteAddress.getHostString());
        if (!remoteAddress.isUnresolved()) {
            sb.append('/').append(remoteAddress.getAddress().getHostAddress());
        }
        sb.append(':').append(remoteAddress.getPort());
    }
}
