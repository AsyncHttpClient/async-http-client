/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.Channel;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;

class Continue100Interceptor {

    private final NettyRequestSender requestSender;

    Continue100Interceptor(NettyRequestSender requestSender) {
        this.requestSender = requestSender;
    }

    public boolean exitAfterHandling100(final Channel channel, final NettyResponseFuture<?> future) {
        future.setHeadersAlreadyWrittenOnContinue(true);
        future.setDontWriteBodyBecauseExpectContinue(false);
        // directly send the body
        Channels.setAttribute(channel, new OnLastHttpContentCallback(future) {
            @Override
            public void call() {
                Channels.setAttribute(channel, future);
                requestSender.writeRequest(future, channel);
            }
        });
        return true;
    }
}
