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
import io.netty.buffer.Unpooled;

public class NettyByteArrayBody extends NettyDirectBody {

  private final byte[] bytes;

  public NettyByteArrayBody(byte[] bytes) {
    this.bytes = bytes;
  }

  @Override
  public long getContentLength() {
    return bytes.length;
  }

  @Override
  public ByteBuf byteBuf() {
    return Unpooled.wrappedBuffer(bytes);
  }
}
