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
package org.asynchttpclient.request.body.multipart.part;

import io.netty.buffer.ByteBuf;

public interface PartVisitor {

  void withBytes(byte[] bytes);

  void withByte(byte b);

  class CounterPartVisitor implements PartVisitor {

    private int count = 0;

    @Override
    public void withBytes(byte[] bytes) {
      count += bytes.length;
    }

    @Override
    public void withByte(byte b) {
      count++;
    }

    public int getCount() {
      return count;
    }
  }

  class ByteBufVisitor implements PartVisitor {
    private final ByteBuf target;

    public ByteBufVisitor(ByteBuf target) {
      this.target = target;
    }

    @Override
    public void withBytes(byte[] bytes) {
      target.writeBytes(bytes);
    }

    @Override
    public void withByte(byte b) {
      target.writeByte(b);
    }
  }
}
