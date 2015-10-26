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

import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

public class ListenableFutureTest extends AbstractBasicTest {

    @Test(groups = "standalone")
    public void testListenableFuture() throws Exception {
        final AtomicInteger statusCode = new AtomicInteger(500);
        try (AsyncHttpClient ahc = asyncHttpClient()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final ListenableFuture<Response> future = ahc.prepareGet(getTargetUrl()).execute();
            future.addListener(new Runnable() {

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
        }
    }
}
