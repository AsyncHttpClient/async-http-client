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

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.RequestType;
import com.ning.http.client.Response;
import com.ning.http.client.async.AbstractBasicTest.AsyncCompletionHandlerAdapter;
import com.ning.http.client.providers.NettyAsyncHttpProvider;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for remote site.
 *
 * see http://github.com/MSch/ning-async-http-client-bug/tree/master
 * @author Martin Schurrer
 */
public class RemoteSiteTest {
    private AsyncHttpClient c;
    private CyclicBarrier b;
    private AsyncCompletionHandler<Response> h;
    private Throwable t;

    @BeforeClass
    public void before() {
        b = new CyclicBarrier(2);
        c = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(10000).build());
        t = null;
        h = new AsyncCompletionHandler<Response>() {
            public void onThrowable(Throwable t) {
                try {
                    RemoteSiteTest.this.t = t;
                }
                finally {
                    try {
                        b.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (BrokenBarrierException e) {
                        e.printStackTrace();
                    }
                }
            }

            public Response onCompleted(Response response) throws Exception {
                try {
                    return response;
                } finally {
                    try {
                        b.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (BrokenBarrierException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
    }

    @AfterClass
    public void checkThrowable() {
        if (t != null) {
            t.printStackTrace();
            Assert.fail("timeout?!");
        }
    }

    @Test(groups = "online")
    public void testGoogleCom() throws IOException, BrokenBarrierException, InterruptedException {
        // Works
        c.prepareGet("http://www.google.com/").execute(h);
        b.await();
    }

    @Test(groups = "online")
    public void testMailGoogleCom() throws IOException, BrokenBarrierException, InterruptedException {
        c.prepareGet("http://mail.google.com/").execute(h);
        b.await();
    }

    @Test(groups = "online")
    public void testPlanetromeoCom() throws IOException, BrokenBarrierException, InterruptedException {
        c.prepareGet("http://planetromeo.com/").execute(h);
        b.await();
    }

    @Test(groups = "online")
    public void testMiniPlanetromeoCom() throws IOException, BrokenBarrierException, InterruptedException {
        c.prepareGet("http://mini.planetromeo.com/").execute(h);
        b.await();
    }

    @Test(groups = "online")
    public void testMicrosoftCom() throws IOException, BrokenBarrierException, InterruptedException {
        // Works
        c.prepareGet("http://microsoft.com/").execute(h);
        b.await();
    }

    @Test(groups = "online")
    public void testWwwMicrosoftCom() throws IOException, BrokenBarrierException, InterruptedException {
        c.prepareGet("http://www.microsoft.com/").execute(h);
        b.await();
    }

    @Test(groups = "online")
    public void testUpdateMicrosoftCom() throws IOException, BrokenBarrierException, InterruptedException {
        c.prepareGet("http://update.microsoft.com/").execute(h);
        b.await();
    }

    @Test(groups = "online")
    public void testGoogleComWithTimeout() throws IOException, BrokenBarrierException, InterruptedException {
        // Works
        c.prepareGet("http://google.com/").execute(h);
        b.await();
        Thread.sleep(20000); // Wait for timeout
        if (t != null){
            Assert.fail("timeout?!");            
        }
    }

    @Test(groups = "online")
    public void asyncStatusHEADContentLenghtTest() throws Throwable {
        NettyAsyncHttpProvider n = new NettyAsyncHttpProvider(
                new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());

        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder(RequestType.HEAD)
                .setUrl("http://www.google.com/")
                .build();

        n.execute(request, new AsyncCompletionHandlerAdapter() {
            @Override
            public Response onCompleted(Response response) throws Exception {
                Assert.assertEquals(response.getStatusCode(), 200);
                l.countDown();
                return response;
            }
        }).get();

        if (!l.await(5, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }
}

