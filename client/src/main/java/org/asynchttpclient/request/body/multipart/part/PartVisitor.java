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
package org.asynchttpclient.request.body.multipart.part;

import io.netty.buffer.ByteBuf;

public interface PartVisitor {

    void withBytes(byte[] bytes);

    void withByte(byte b);

    class CounterPartVisitor implements PartVisitor {

        private int count;

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
