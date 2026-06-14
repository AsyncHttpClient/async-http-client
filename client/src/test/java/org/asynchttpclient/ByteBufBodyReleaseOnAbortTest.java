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
package org.asynchttpclient;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.asynchttpclient.netty.request.body.NettyByteBufBody;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression test for the {@code setBody(ByteBuf)} request-body leak on an abort-before-write.
 * <p>
 * {@link org.asynchttpclient.netty.request.body.NettyByteBufBody#byteBuf()} hands a {@code retainedDuplicate}
 * of the caller's buffer to the {@code DefaultFullHttpRequest} (so a retry/redirect can re-send the body
 * without double-freeing the user's buffer). On the normal HTTP/1.1 path Netty's encoder releases that
 * duplicate after the write; but on an abort BEFORE the request is written (connection failure,
 * {@code onRequestSend} crash, pool closed) nothing in the HTTP/1.1 path used to release it — leaking the
 * extra reference (the user's own release would only take the shared count {@code 2 -> 1}, never freeing the
 * orphaned duplicate). The fix releases the never-written request on the terminal/abort path, gated on
 * whether it was handed to a channel encoder, so it can never double-free.
 */
public class ByteBufBodyReleaseOnAbortTest {

    @Test
    public void byteBufBodyReleasedOnConnectFailureBeforeWrite() throws Exception {
        // A reference-counted request body owned by the caller (refCnt == 1).
        ByteBuf body = UnpooledByteBufAllocator.DEFAULT.buffer().writeBytes("payload-bytes".getBytes());
        assertEquals(1, body.refCnt());

        // A port nothing listens on, so the request aborts at connect — before it is ever written.
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            closedPort = ss.getLocalPort();
        }

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setConnectTimeout(Duration.ofSeconds(2))
                .setMaxRequestRetry(0))) {
            assertThrows(ExecutionException.class, () ->
                    client.preparePost("http://localhost:" + closedPort + "/")
                            .setBody(body)
                            .execute().get(10, SECONDS));
        }

        assertEquals(1, body.refCnt(),
                "AHC must release its internal retained-duplicate of the request body on an abort-before-write, "
                        + "leaving the caller's buffer at its original refCnt (otherwise the duplicate leaks)");

        // The caller still fully owns the buffer and can release it.
        body.release();
        assertEquals(0, body.refCnt());
    }

    /**
     * Directly pins the ownership contract that the abort test above cannot distinguish from a pre-fix revert
     * (both leave the caller's buffer at refCnt 1 on an abort-before-write). {@code byteBuf()} must hand out a
     * <em>distinct</em> retained duplicate — not the caller's own buffer — so each send/retry owns its own
     * reference and the caller's buffer survives. Reverting it to {@code return bb} makes this fail.
     */
    @Test
    public void byteBufBodyHandsOutDistinctRetainedDuplicate() {
        ByteBuf body = UnpooledByteBufAllocator.DEFAULT.buffer().writeBytes("payload-bytes".getBytes());
        assertEquals(1, body.refCnt());

        ByteBuf handed = new NettyByteBufBody(body).byteBuf();

        assertNotSame(body, handed, "byteBuf() must hand out a duplicate, never the caller's own buffer");
        assertEquals(2, body.refCnt(),
                "byteBuf() must retain (shared refcount 1 -> 2) so the caller's buffer survives the send and retries");

        // Releasing the duplicate (as Netty's encoder, or an abort, does) returns to the caller's single reference.
        handed.release();
        assertEquals(1, body.refCnt());

        body.release();
        assertEquals(0, body.refCnt());
    }
}
