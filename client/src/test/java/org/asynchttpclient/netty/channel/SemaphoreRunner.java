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
