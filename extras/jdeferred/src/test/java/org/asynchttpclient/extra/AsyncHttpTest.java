/*
 * Copyright 2013 Ray Tsang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asynchttpclient.extra;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.extras.jdeferred.AsyncHttpDeferredObject;
import org.asynchttpclient.extras.jdeferred.HttpProgress;
import org.jdeferred.DoneCallback;
import org.jdeferred.ProgressCallback;
import org.jdeferred.Promise;
import org.jdeferred.impl.DefaultDeferredManager;
import org.jdeferred.multiple.MultipleResults;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class AsyncHttpTest {
  protected DefaultDeferredManager deferredManager = new DefaultDeferredManager();

  public void testPromiseAdapter() throws IOException {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicInteger successCount = new AtomicInteger();
    final AtomicInteger progressCount = new AtomicInteger();

    try (AsyncHttpClient client = asyncHttpClient()) {
      Promise<Response, Throwable, HttpProgress> p1 = AsyncHttpDeferredObject.promise(client.prepareGet("http://gatling.io"));
      p1.done(new DoneCallback<Response>() {
        @Override
        public void onDone(Response response) {
          try {
            assertEquals(response.getStatusCode(), 200);
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }
      }).progress(new ProgressCallback<HttpProgress>() {

        @Override
        public void onProgress(HttpProgress progress) {
          progressCount.incrementAndGet();
        }
      });

      latch.await();
      assertTrue(progressCount.get() > 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void testMultiplePromiseAdapter() throws IOException {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicInteger successCount = new AtomicInteger();

    try (AsyncHttpClient client = asyncHttpClient()) {
      Promise<Response, Throwable, HttpProgress> p1 = AsyncHttpDeferredObject.promise(client.prepareGet("http://gatling.io"));
      Promise<Response, Throwable, HttpProgress> p2 = AsyncHttpDeferredObject.promise(client.prepareGet("http://www.google.com"));
      AsyncHttpDeferredObject deferredRequest = new AsyncHttpDeferredObject(client.prepareGet("http://jdeferred.org"));

      deferredManager.when(p1, p2, deferredRequest).then(new DoneCallback<MultipleResults>() {
        @Override
        public void onDone(MultipleResults result) {
          try {
            assertEquals(result.size(), 3);
            assertEquals(Response.class.cast(result.get(0).getResult()).getStatusCode(), 200);
            assertEquals(Response.class.cast(result.get(1).getResult()).getStatusCode(), 200);
            assertEquals(Response.class.cast(result.get(2).getResult()).getStatusCode(), 200);
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }
      });
      latch.await();

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
