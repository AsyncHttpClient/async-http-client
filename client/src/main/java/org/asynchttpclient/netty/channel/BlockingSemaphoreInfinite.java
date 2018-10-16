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
public class BlockingSemaphoreInfinite extends Semaphore {

  public static final BlockingSemaphoreInfinite INSTANCE = new BlockingSemaphoreInfinite();
  private static final long serialVersionUID = 1L;

  private BlockingSemaphoreInfinite() {
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

