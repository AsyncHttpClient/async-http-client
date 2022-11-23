/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;

/**
 * Simple stress test for exercising the follow redirect.
 */
public class FollowingThreadTest extends AbstractBasicTest {

    private static final int COUNT = 10;

    @RepeatedIfExceptionsTest(repeats = 5)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 30 * 1000)
    public void testFollowRedirect() throws InterruptedException {

        final CountDownLatch countDown = new CountDownLatch(COUNT);
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            for (int i = 0; i < COUNT; i++) {
                pool.submit(new Runnable() {

                    private int status;

                    @Override
                    public void run() {
                        final CountDownLatch l = new CountDownLatch(1);
                        try (AsyncHttpClient ahc = asyncHttpClient(config().setFollowRedirect(true))) {
                            ahc.prepareGet("http://www.google.com/").execute(new AsyncHandler<Integer>() {

                                @Override
                                public void onThrowable(Throwable t) {
                                    t.printStackTrace();
                                }

                                @Override
                                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                                    System.out.println(new String(bodyPart.getBodyPartBytes()));
                                    return State.CONTINUE;
                                }

                                @Override
                                public State onStatusReceived(HttpResponseStatus responseStatus) {
                                    status = responseStatus.getStatusCode();
                                    System.out.println(responseStatus.getStatusText());
                                    return State.CONTINUE;
                                }

                                @Override
                                public State onHeadersReceived(HttpHeaders headers) {
                                    return State.CONTINUE;
                                }

                                @Override
                                public Integer onCompleted() {
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
