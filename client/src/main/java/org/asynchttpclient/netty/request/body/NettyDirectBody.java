/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request.body;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.asynchttpclient.netty.NettyResponseFuture;

public abstract class NettyDirectBody implements NettyBody {

  public abstract ByteBuf byteBuf();

  @Override
  public void write(Channel channel, NettyResponseFuture<?> future) {
    throw new UnsupportedOperationException("This kind of body is supposed to be writen directly");
  }
}
