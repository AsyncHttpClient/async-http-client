/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request.body;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.asynchttpclient.netty.NettyResponseFuture;

public abstract class NettyDirectBody implements NettyBody {

    public abstract ByteBuf byteBuf();

    @Override
    public void write(Channel channel, NettyResponseFuture<?> future) {
        throw new UnsupportedOperationException("This kind of body is supposed to be written directly");
    }
}
