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
package org.asynchttpclient.async;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Simple stress test for exercising the follow redirect.
 */
public abstract class FollowingThreadTest extends AbstractBasicTest {

    private static final int COUNT = 10;

    @Test(timeOut = 30 * 1000, groups = { "online", "default_provider", "scalability" })
    public void testFollowRedirect() throws IOException, ExecutionException, TimeoutException, InterruptedException {

        final CountDownLatch countDown = new CountDownLatch(COUNT);
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            for (int i = 0; i < COUNT; i++) {
                pool.submit(new Runnable() {

                    private int status;

                    public void run() {
                        final CountDownLatch l = new CountDownLatch(1);
                        final AsyncHttpClient ahc = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirect(true).build());
                        try {
                            ahc.prepareGet("http://www.google.com/").execute(new AsyncHandler<Integer>() {

                                public void onThrowable(Throwable t) {
                                    t.printStackTrace();
                                }

                                public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                                    System.out.println(new String(bodyPart.getBodyPartBytes()));
                                    return STATE.CONTINUE;
                                }

                                public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                                    status = responseStatus.getStatusCode();
                                    System.out.println(responseStatus.getStatusText());
                                    return STATE.CONTINUE;
                                }

                                public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                                    return STATE.CONTINUE;
                                }

                                public Integer onCompleted() throws Exception {
                                    l.countDown();
                                    return status;
                                }
                            });

                            l.await();
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            ahc.close();
                            countDown.countDown();
                        }
                    }
                });
            }
            countDown.await();
        } finally {
            pool.shutdown();
        }
    }
}
