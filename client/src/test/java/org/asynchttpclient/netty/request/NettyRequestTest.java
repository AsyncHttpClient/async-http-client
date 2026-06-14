/*
 *    Copyright (c) 2014-2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link NettyRequest#release()} — the idempotent release that lets the HTTP/2
 * early-abort paths free the request body buffer that only {@code sendHttp2Frames} would otherwise
 * release. In the same package so the package-private constructor is reachable.
 */
public class NettyRequestTest {

    private static DefaultFullHttpRequest postWithBody(ByteBuf content) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
    }

    @Test
    public void releaseFreesTheRequestBodyExactlyOnce() {
        ByteBuf content = Unpooled.buffer().writeBytes("payload".getBytes(StandardCharsets.UTF_8));
        NettyRequest request = new NettyRequest(postWithBody(content), null);

        assertEquals(1, content.refCnt(), "precondition: body buffer is live");

        request.release();
        assertEquals(0, content.refCnt(), "release() must free the request body buffer");
    }

    @Test
    public void releaseIsIdempotent() {
        ByteBuf content = Unpooled.buffer().writeBytes("payload".getBytes(StandardCharsets.UTF_8));
        NettyRequest request = new NettyRequest(postWithBody(content), null);

        request.release();
        // A second (or third) release must be a no-op — NOT an IllegalReferenceCountException — because
        // several abort paths can race to release the same request (e.g. the inline closed-race check and
        // the parent closeFuture's failPendingOpeners).
        request.release();
        request.release();

        assertEquals(0, content.refCnt(), "buffer stays released; no double-free");
    }

    @Test
    public void releaseOfBodylessRequestIsSafe() {
        // GETs / bodyless requests carry Unpooled.EMPTY_BUFFER — releasing must not blow up.
        NettyRequest request = new NettyRequest(postWithBody(Unpooled.EMPTY_BUFFER), null);
        request.release();
        request.release();
    }
}
