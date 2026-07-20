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
package org.asynchttpclient;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class OffloadedBodyReadTestSupport {

    private OffloadedBodyReadTestSupport() {
    }

    static void awaitExecutorState(ThreadPoolExecutor executor, int queued, int active)
            throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        while ((executor.getQueue().size() != queued || executor.getActiveCount() != active)
                && System.nanoTime() < deadline) {
            new CountDownLatch(1).await(10, MILLISECONDS);
        }
        assertEquals(queued, executor.getQueue().size(), "unexpected body-read executor queue size");
        assertEquals(active, executor.getActiveCount(), "unexpected body-read executor active count");
    }

    static final class BlockingInputStream extends InputStream {
        private final byte[] data;
        private final AtomicBoolean firstRead = new AtomicBoolean(true);
        private final AtomicInteger position = new AtomicInteger();
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);

        BlockingInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            awaitFirstRead();
            int current = position.get();
            if (current == data.length) {
                return -1;
            }
            int read = Math.min(length, data.length - current);
            System.arraycopy(data, current, buffer, offset, read);
            position.addAndGet(read);
            return read;
        }

        @Override
        public void close() {
            releaseRead.countDown();
        }

        private void awaitFirstRead() throws IOException {
            if (firstRead.compareAndSet(true, false)) {
                readStarted.countDown();
                try {
                    if (!releaseRead.await(10, SECONDS)) {
                        throw new IOException("timed out waiting to release blocking request body read");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("blocking request body read interrupted", e);
                }
            }
        }
    }

    static final class CloseProbeInputStream extends InputStream {
        final AtomicBoolean readAttempted = new AtomicBoolean();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() {
            readAttempted.set(true);
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            readAttempted.set(true);
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
