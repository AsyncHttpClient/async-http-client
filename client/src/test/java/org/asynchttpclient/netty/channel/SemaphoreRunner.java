/*
 *    Copyright (c) 2023 AsyncHttpClient Project. All rights reserved.
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

class SemaphoreRunner {

    final ConnectionSemaphore semaphore;
    final Thread acquireThread;

    volatile long acquireTime;
    volatile Exception acquireException;

    SemaphoreRunner(ConnectionSemaphore semaphore, Object partitionKey) {
        this.semaphore = semaphore;
        acquireThread = new Thread(() -> {
            long beforeAcquire = System.currentTimeMillis();
            try {
                semaphore.acquireChannelLock(partitionKey);
            } catch (Exception e) {
                acquireException = e;
            } finally {
                acquireTime = System.currentTimeMillis() - beforeAcquire;
            }
        });
    }

    public void acquire() {
        acquireThread.start();
    }

    public void interrupt() {
        acquireThread.interrupt();
    }

    public void await() {
        try {
            acquireThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean finished() {
        return !acquireThread.isAlive();
    }

    public long getAcquireTime() {
        return acquireTime;
    }

    public Exception getAcquireException() {
        return acquireException;
    }
}
