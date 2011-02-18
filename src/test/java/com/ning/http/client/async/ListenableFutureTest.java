/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Response;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;

/**
 * Tests case where response doesn't have body.
 *
 * @author Hubert Iwaniuk
 */
public abstract class ListenableFutureTest extends AbstractBasicTest {

    @Test(groups = {"standalone", "default_provider"})
    public void testListenableFuture() throws Throwable {
        final AtomicInteger statusCode = new AtomicInteger(500);
        AsyncHttpClient ahc = getAsyncHttpClient(null);
        final CountDownLatch latch = new CountDownLatch(1);
        final Future<Response> future = ahc.prepareGet(getTargetUrl()).execute();
        ((ListenableFuture)future).addListener(new Runnable(){

            public void run() {
                try {
                    statusCode.set(future.get().getStatusCode());
                    latch.countDown();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        }, Executors.newFixedThreadPool(1));

        latch.await(10, TimeUnit.SECONDS);
        assertEquals(statusCode.get(), 200);
        ahc.close();
    }
}
