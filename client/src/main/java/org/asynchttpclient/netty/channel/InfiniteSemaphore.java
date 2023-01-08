/*
 *    Copyright (c) 2018-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.channel;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A java.util.concurrent.Semaphore that always has Integer.Integer.MAX_VALUE free permits
 *
 * @author Alex Maltinsky
 */
public class InfiniteSemaphore extends Semaphore {

    public static final InfiniteSemaphore INSTANCE = new InfiniteSemaphore();
    private static final long serialVersionUID = 1L;

    private InfiniteSemaphore() {
        super(Integer.MAX_VALUE);
    }

    @Override
    public void acquire() {
        // NO-OP
    }

    @Override
    public void acquireUninterruptibly() {
        // NO-OP
    }

    @Override
    public boolean tryAcquire() {
        return true;
    }

    @Override
    public boolean tryAcquire(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public void release() {
        // NO-OP
    }

    @Override
    public void acquire(int permits) {
        // NO-OP
    }

    @Override
    public void acquireUninterruptibly(int permits) {
        // NO-OP
    }

    @Override
    public boolean tryAcquire(int permits) {
        return true;
    }

    @Override
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public void release(int permits) {
        // NO-OP
    }

    @Override
    public int availablePermits() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int drainPermits() {
        return Integer.MAX_VALUE;
    }

    @Override
    protected void reducePermits(int reduction) {
        // NO-OP
    }

    @Override
    public boolean isFair() {
        return true;
    }

    @Override
    protected Collection<Thread> getQueuedThreads() {
        return Collections.emptyList();
    }
}
