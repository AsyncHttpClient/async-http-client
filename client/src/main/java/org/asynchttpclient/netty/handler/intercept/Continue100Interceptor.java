/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.Channel;

import java.io.IOException;

import org.asynchttpclient.netty.Callback;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;

public class Continue100Interceptor {

    private final NettyRequestSender requestSender;

    public Continue100Interceptor(NettyRequestSender requestSender) {
        this.requestSender = requestSender;
    }

    public boolean exitAfterHandling100(final Channel channel, final NettyResponseFuture<?> future, int statusCode) {
        future.setHeadersAlreadyWrittenOnContinue(true);
        future.setDontWriteBodyBecauseExpectContinue(false);
        // directly send the body
        Channels.setAttribute(channel, new Callback(future) {
            @Override
            public void call() throws IOException {
                Channels.setAttribute(channel, future);
                requestSender.writeRequest(future, channel);
            }
        });
        return true;
    }
}
