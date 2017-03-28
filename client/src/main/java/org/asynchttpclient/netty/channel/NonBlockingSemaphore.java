/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.channel;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Semaphore-like API, but without blocking.
 *
 * @author Stepan Koltsov
 */
class NonBlockingSemaphore implements NonBlockingSemaphoreLike {

    private final AtomicInteger permits;

    public NonBlockingSemaphore(int permits) {
        this.permits = new AtomicInteger(permits);
    }

    @Override
    public void release() {
        permits.incrementAndGet();
    }

    @Override
    public boolean tryAcquire() {
        for (;;) {
            int count = permits.get();
            if (count <= 0) {
                return false;
            }
            if (permits.compareAndSet(count, count - 1)) {
                return true;
            }
        }
    }

    @Override
    public String toString() {
        // mimic toString of Semaphore class
        return super.toString() + "[Permits = " + permits + "]";
    }
}
