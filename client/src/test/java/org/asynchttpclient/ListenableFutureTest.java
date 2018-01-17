/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient;

import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.testng.Assert.assertEquals;

public class ListenableFutureTest extends AbstractBasicTest {

  @Test
  public void testListenableFuture() throws Exception {
    final AtomicInteger statusCode = new AtomicInteger(500);
    try (AsyncHttpClient ahc = asyncHttpClient()) {
      final CountDownLatch latch = new CountDownLatch(1);
      final ListenableFuture<Response> future = ahc.prepareGet(getTargetUrl()).execute();
      future.addListener(() -> {
        try {
          statusCode.set(future.get().getStatusCode());
          latch.countDown();
        } catch (InterruptedException | ExecutionException e) {
          e.printStackTrace();
        }
      }, Executors.newFixedThreadPool(1));

      latch.await(10, TimeUnit.SECONDS);
      assertEquals(statusCode.get(), 200);
    }
  }

  @Test
  public void testListenableFutureAfterCompletion() throws Exception {

    final CountDownLatch latch = new CountDownLatch(1);

    try (AsyncHttpClient ahc = asyncHttpClient()) {
      final ListenableFuture<Response> future = ahc.prepareGet(getTargetUrl()).execute();
      future.get();
      future.addListener(() -> latch.countDown(), Runnable::run);
    }

    latch.await(10, TimeUnit.SECONDS);
  }

  @Test
  public void testListenableFutureBeforeAndAfterCompletion() throws Exception {

    final CountDownLatch latch = new CountDownLatch(2);

    try (AsyncHttpClient ahc = asyncHttpClient()) {
      final ListenableFuture<Response> future = ahc.prepareGet(getTargetUrl()).execute();
      future.addListener(latch::countDown, Runnable::run);
      future.get();
      future.addListener(latch::countDown, Runnable::run);
    }

    latch.await(10, TimeUnit.SECONDS);
  }
}
