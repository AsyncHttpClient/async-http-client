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
package org.asynchttpclient.netty.request.body;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression guard for Issue #1973: a consumed InputStream body must fail the request immediately on
 * retry instead of hanging until the request timeout.
 */
public class NettyInputStreamBodyTest {

    @Test
    public void http1WriteFailsWhenStreamAlreadyConsumedAndNotResettable() throws IOException {
        NettyInputStreamBody body = new NettyInputStreamBody(InputStream.nullInputStream());
        NettyResponseFuture<?> future = newFuture(true);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            IOException ex = assertThrows(IOException.class, () -> body.write(channel, future));
            assertNotEquals("Stream closed", ex.getMessage());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void http1WriteFailsWhenConsumedMarkSupportedStreamCannotReset() throws IOException {
        BufferedInputStream is = new BufferedInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        is.close();
        NettyInputStreamBody body = new NettyInputStreamBody(is);
        NettyResponseFuture<?> future = newFuture(true);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            IOException ex = assertThrows(IOException.class, () -> body.write(channel, future));
            assertNotEquals("Stream closed", ex.getMessage());
            assertTrue(ex.getCause() instanceof IOException);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void http1FirstWriteClosesTheStreamAndRetryThenFailsFast() throws IOException {
        BufferedInputStream is = new BufferedInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        NettyInputStreamBody body = new NettyInputStreamBody(is);
        NettyResponseFuture<?> future = newFuture(false);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            body.write(channel, future);
            channel.runPendingTasks();
            assertThrows(IOException.class, is::read);

            IOException ex = assertThrows(IOException.class, () -> body.write(channel, future));
            assertNotEquals("Stream closed", ex.getMessage());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void http2WriteFailsWhenStreamAlreadyConsumedAndNotResettable() {
        NettyInputStreamBody body = new NettyInputStreamBody(InputStream.nullInputStream());
        NettyResponseFuture<?> future = newFuture(true);

        IOException ex = assertThrows(IOException.class, () -> body.writeHttp2(mock(Http2StreamChannel.class), future));
        assertNotEquals("Stream closed", ex.getMessage());
    }

    @Test
    public void http2WriteFailsWhenConsumedMarkSupportedStreamCannotReset() throws IOException {
        BufferedInputStream is = new BufferedInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        is.close();
        NettyInputStreamBody body = new NettyInputStreamBody(is);
        NettyResponseFuture<?> future = newFuture(true);

        IOException ex = assertThrows(IOException.class, () -> body.writeHttp2(mock(Http2StreamChannel.class), future));
        assertNotEquals("Stream closed", ex.getMessage());
        assertTrue(ex.getCause() instanceof IOException);
    }

    private static NettyResponseFuture<?> newFuture(boolean streamConsumed) {
        NettyResponseFuture<?> future = new NettyResponseFuture<>(null, mock(AsyncHandler.class), null, 0, null, null, null);
        future.setStreamConsumed(streamConsumed);
        return future;
    }
}
