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
package org.asynchttpclient;

import static org.asynchttpclient.Dsl.*;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;

/**
 * Simple stress test for exercising the follow redirect.
 */
public class FollowingThreadTest extends AbstractBasicTest {

    private static final int COUNT = 10;

    @Test(groups = "online", timeOut = 30 * 1000)
    public void testFollowRedirect() throws IOException, ExecutionException, TimeoutException, InterruptedException {

        final CountDownLatch countDown = new CountDownLatch(COUNT);
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            for (int i = 0; i < COUNT; i++) {
                pool.submit(new Runnable() {

                    private int status;

                    public void run() {
                        final CountDownLatch l = new CountDownLatch(1);
                        try (AsyncHttpClient ahc = asyncHttpClient(config().setFollowRedirect(true))) {
                            ahc.prepareGet("http://www.google.com/").execute(new AsyncHandler<Integer>() {

                                public void onThrowable(Throwable t) {
                                    t.printStackTrace();
                                }

                                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                                    System.out.println(new String(bodyPart.getBodyPartBytes()));
                                    return State.CONTINUE;
                                }

                                public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                                    status = responseStatus.getStatusCode();
                                    System.out.println(responseStatus.getStatusText());
                                    return State.CONTINUE;
                                }

                                public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                                    return State.CONTINUE;
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
