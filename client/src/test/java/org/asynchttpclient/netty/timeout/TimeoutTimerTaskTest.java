/*
 *    Copyright (c) 2014-2025 AsyncHttpClient Project. All rights reserved.
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

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimeoutTimerTaskTest {

    @Test
    public void appendRemoteAddressShouldNotThrowWhenRemoteAddressIsNull() {
        Request request = new RequestBuilder().setUrl("http://example.com:12345").build();
        NettyResponseFuture<?> future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(org.asynchttpclient.Response response) throws Exception {
                return null;
            }
        }, null,
                0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);

        // create TimeoutsHolder without an original remote address
        TimeoutsHolder timeoutsHolder = new TimeoutsHolder(null, future, null, new DefaultAsyncHttpClientConfig.Builder().build(), null);

        TimeoutTimerTask task = new TimeoutTimerTask(future, null, timeoutsHolder) {
            @Override
            public void run(io.netty.util.Timeout timeout) {
                // no-op
            }
        };

        StringBuilder sb = new StringBuilder();
        task.appendRemoteAddress(sb);

        // fallback should include URI host/port
        assertTrue(sb.toString().contains("example.com:12345"), sb.toString());
    }

    @Test
    public void appendRemoteAddressShouldPrintResolvedAddressIfAvailable() {
        Request request = new RequestBuilder().setUrl("http://example.com:12345").build();
        NettyResponseFuture<?> future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(org.asynchttpclient.Response response) throws Exception {
                return null;
            }
        }, null,
                0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);

        TimeoutsHolder timeoutsHolder = new TimeoutsHolder(null, future, null, new DefaultAsyncHttpClientConfig.Builder().build(), null);

        // set a resolved remote address
        timeoutsHolder.setResolvedRemoteAddress(new InetSocketAddress("127.0.0.1", 8080));

        TimeoutTimerTask task = new TimeoutTimerTask(future, null, timeoutsHolder) {
            @Override
            public void run(io.netty.util.Timeout timeout) {
                // no-op
            }
        };

        StringBuilder sb = new StringBuilder();
        task.appendRemoteAddress(sb);
        assertTrue(sb.toString().contains(":8080"), sb.toString());
    }
}
