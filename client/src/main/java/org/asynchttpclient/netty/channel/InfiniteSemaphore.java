/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
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

