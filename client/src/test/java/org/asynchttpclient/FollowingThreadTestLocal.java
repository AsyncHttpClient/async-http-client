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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.asynchttpclient.Dsl.*;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Simple stress test for exercising the follow redirect.
 */
public class FollowingThreadTestLocal extends HttpServerTestBase {

    private static final int COUNT = 10;

    @Test(timeOut = 30 * 1000, groups = { "standalone", "scalability" })
    public void testFollowRedirect() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        WireMock.reset();
        stubFor(WireMock.get(urlEqualTo(mockServers.get("www.google.com").getMockRelativeUrl())).willReturn(aResponse().withStatus(302).
                withHeader("Location", mockServers.get("www.google.com").getMockRelativeUrl() + "/1")));
        stubFor(WireMock.get(urlEqualTo(mockServers.get("www.google.com").getMockRelativeUrl() + "/1")).willReturn(aResponse().withStatus(302).
                withHeader("Location", mockServers.get("www.google.com").getMockRelativeUrl() + "/2")));
        stubFor(WireMock.get(urlEqualTo(mockServers.get("www.google.com").getMockRelativeUrl() + "/2")).willReturn(aResponse().withStatus(200).
                withHeader("Content-Type", "application/json").withBody("")));


        final CountDownLatch countDown = new CountDownLatch(COUNT);
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            for (int i = 0; i < COUNT; i++) {
                pool.submit(new Runnable() {

                    private int status;

                    public void run() {
                        final CountDownLatch countDownLatch = new CountDownLatch(1);
                        try (AsyncHttpClient ahc = asyncHttpClient(config().setFollowRedirect(true))) {
                            ahc.prepareGet(mockServers.get("www.google.com").getMockUrl()).execute(new AsyncHandler<Integer>() {

                                public void onThrowable(Throwable exception) {
                                    exception.printStackTrace();
                                }

                                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                                    System.out.println(new String(bodyPart.getBodyPartBytes()));
                                    return State.CONTINUE;
                                }

                                public State onStatusReceived(HttpResponseStatus responseStatus) {
                                    status = responseStatus.getStatusCode();
                                    System.out.println(responseStatus.getStatusText());
                                    Assert.assertEquals(status, 200, "Expecting OK (200) response from the webserver");
                                    return State.CONTINUE;
                                }

                                public State onHeadersReceived(HttpResponseHeaders headers) {
                                    return State.CONTINUE;
                                }

                                public Integer onCompleted() {
                                    countDownLatch.countDown();
                                    return status;
                                }
                            });

                            countDownLatch.await();
                        } catch (Exception exception2) {
                            exception2.printStackTrace();
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
