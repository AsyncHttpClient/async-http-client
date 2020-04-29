package org.asynchttpclient.netty.channel;

class SemaphoreRunner {

  final ConnectionSemaphore semaphore;
  final Thread acquireThread;

  volatile long acquireTime;
  volatile Exception acquireException;

  public SemaphoreRunner(ConnectionSemaphore semaphore, Object partitionKey) {
    this.semaphore = semaphore;
    this.acquireThread = new Thread(() -> {
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
    this.acquireThread.start();
  }

  public void interrupt() {
    this.acquireThread.interrupt();
  }

  public void await() {
    try {
      this.acquireThread.join();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public boolean finished() {
    return !this.acquireThread.isAlive();
  }

  public long getAcquireTime() {
    return acquireTime;
  }

  public Exception getAcquireException() {
    return acquireException;
  }
}
