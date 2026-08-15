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
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Regression guard for Issue #1973: a non-resettable {@link InputStream} body that has already been
 * consumed must fail the request immediately on retry instead of hanging until the request timeout.
 */
public class NettyInputStreamBodyTest {

    @Test
    public void http1WriteFailsWhenStreamAlreadyConsumedAndNotResettable() {
        NettyInputStreamBody body = new NettyInputStreamBody(new NonResettableInputStream(new byte[] {1, 2, 3}));
        NettyResponseFuture<?> future = new NettyResponseFuture<>(null, mock(AsyncHandler.class), null, 0, null, null, null);
        future.setStreamConsumed(true);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            IOException ex = assertThrows(IOException.class, () -> body.write(channel, future));
            assertEquals(
                    "HTTP/1 request body InputStream already consumed and cannot be reset for a retry",
                    ex.getMessage());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class NonResettableInputStream extends InputStream {

        private final byte[] data;
        private int index;

        NonResettableInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return index < data.length ? data[index++] & 0xFF : -1;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
