/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.handler.codec.compression.Decompressor;
import org.jetbrains.annotations.Nullable;

/**
 * Passes its input through, but only once it is told that the input has ended, as Netty 4.2.19's Brotli decompressor
 * did with the tail of a body.
 */
final class OutputAtEndOfInputDecompressor implements Decompressor {

    private @Nullable CompositeByteBuf held;
    private @Nullable ByteBuf output;
    private boolean ended;

    OutputAtEndOfInputDecompressor(ByteBufAllocator alloc) {
        held = alloc.compositeBuffer();
    }

    @Override
    public Status status() {
        return output != null ? Status.NEED_OUTPUT : ended ? Status.COMPLETE : Status.NEED_INPUT;
    }

    @Override
    public void addInput(ByteBuf buf) {
        held.addComponent(true, buf);
    }

    @Override
    public void endOfInput() {
        ended = true;
        output = held;
        held = null;
    }

    @Override
    public ByteBuf takeOutput() {
        ByteBuf taken = output;
        output = null;
        return taken;
    }

    @Override
    public void close() {
        if (held != null) {
            held.release();
            held = null;
        }
        if (output != null) {
            output.release();
            output = null;
        }
    }
}
