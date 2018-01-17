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

import org.testng.annotations.Test;

import java.util.concurrent.Semaphore;

import static org.testng.Assert.*;

/**
 * @author Stepan Koltsov
 */
public class NonBlockingSemaphoreTest {

  @Test
  public void test0() {
    Mirror mirror = new Mirror(0);
    assertFalse(mirror.tryAcquire());
  }

  @Test
  public void three() {
    Mirror mirror = new Mirror(3);
    for (int i = 0; i < 3; ++i) {
      assertTrue(mirror.tryAcquire());
    }
    assertFalse(mirror.tryAcquire());
    mirror.release();
    assertTrue(mirror.tryAcquire());
  }

  @Test
  public void negative() {
    Mirror mirror = new Mirror(-1);
    assertFalse(mirror.tryAcquire());
    mirror.release();
    assertFalse(mirror.tryAcquire());
    mirror.release();
    assertTrue(mirror.tryAcquire());
  }

  private static class Mirror {
    private final Semaphore real;
    private final NonBlockingSemaphore nonBlocking;

    Mirror(int permits) {
      real = new Semaphore(permits);
      nonBlocking = new NonBlockingSemaphore(permits);
    }

    boolean tryAcquire() {
      boolean a = real.tryAcquire();
      boolean b = nonBlocking.tryAcquire();
      assertEquals(a, b);
      return a;
    }

    void release() {
      real.release();
      nonBlocking.release();
    }
  }

}
