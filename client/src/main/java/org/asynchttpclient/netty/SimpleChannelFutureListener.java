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
package org.asynchttpclient.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

public abstract class SimpleChannelFutureListener implements ChannelFutureListener {

    @Override
    public final void operationComplete(ChannelFuture future) {
        Channel channel = future.channel();
        if (future.isSuccess()) {
            onSuccess(channel);
        } else {
            onFailure(channel, future.cause());
        }
    }

    public abstract void onSuccess(Channel channel);

    public abstract void onFailure(Channel channel, Throwable cause);
}
