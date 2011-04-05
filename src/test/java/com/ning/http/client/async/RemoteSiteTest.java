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
import com.ning.http.client.Response;
import com.ning.http.util.AsyncHttpProviderUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Unit tests for remote site.
 * <p/>
 * see http://github.com/MSch/ning-async-http-client-bug/tree/master
 *
 * @author Martin Schurrer
 */
public abstract class RemoteSiteTest extends AbstractBasicTest{
    private AsyncHttpClient c;
    private CyclicBarrier b;
    private AsyncCompletionHandler<Response> h;
    private Throwable t;

    public static final String URL = "http://google.com?q=";
    public static final String REQUEST_PARAM = "github github \n" +
            "github";

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
        if (c != null)
            c.close();

        if (t != null) {
            t.printStackTrace();
            Assert.fail("timeout?!");
        }
    }

    @Test(groups = {"online", "default_provider"})
    public void testGoogleCom() throws IOException, BrokenBarrierException, InterruptedException {
        // Works
        c.prepareGet("http://www.google.com/").execute(h);
        b.await();
    }

    @Test(groups = {"online", "default_provider"})
    public void testMailGoogleCom() throws IOException, BrokenBarrierException, InterruptedException {
        c.prepareGet("http://mail.google.com/").execute(h);
        b.await();
    }

    @Test(groups = {"online", "default_provider"})
    public void testMicrosoftCom() throws IOException, BrokenBarrierException, InterruptedException {
        // Works
        c.prepareGet("http://microsoft.com/").execute(h);
        b.await();
    }

    @Test(groups = {"online", "default_provider"})
    public void testWwwMicrosoftCom() throws IOException, BrokenBarrierException, InterruptedException {
        c.prepareGet("http://www.microsoft.com/").execute(h);
        b.await();
    }

    @Test(groups = {"online", "default_provider"})
    public void testUpdateMicrosoftCom() throws IOException, BrokenBarrierException, InterruptedException {
        c.prepareGet("http://update.microsoft.com/").execute(h);
        b.await();
    }

    @Test(groups = {"online", "default_provider"})
    public void testGoogleComWithTimeout() throws IOException, BrokenBarrierException, InterruptedException {
        // Works
        c.prepareGet("http://google.com/").execute(h);
        b.await();
        Thread.sleep(20000); // Wait for timeout
        if (t != null) {
            Assert.fail("timeout?!");
        }
    }

    @Test(groups = {"online", "default_provider"})
    public void asyncStatusHEADContentLenghtTest() throws Throwable {
        AsyncHttpClient p = new AsyncHttpClient(
                new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());

        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder("HEAD")
                .setUrl("http://www.google.com/")
                .build();

        p.executeRequest(request, new AsyncCompletionHandlerAdapter() {
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
        p.close();
    }

    @Test(groups = {"online", "default_provider"})
    public void invalidStreamTest2() throws Throwable {
        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder()
                .setRequestTimeoutInMs(10000)
                .setFollowRedirects(true)
                .setAllowPoolingConnection(false)
                .setMaximumNumberOfRedirects(6)
                .build();

        AsyncHttpClient c = new AsyncHttpClient(config);
        try {
            Response response = c.prepareGet("http://bit.ly/aUjTtG").execute().get();
            if (response != null) {
                System.out.println(response);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            assertNotNull(t.getCause());
            assertEquals(t.getCause().getMessage(), "invalid version format: ICY");
        }
        c.close();
    }

    @Test(groups = {"online", "default_provider"})
    public void asyncFullBodyProperlyRead() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        Response r = client.prepareGet("http://www.cyberpresse.ca/").execute().get();

        InputStream stream = r.getResponseBodyAsStream();
        int available = stream.available();
        int[] lengthWrapper = new int[1];
        byte[] bytes = AsyncHttpProviderUtils.readFully(stream, lengthWrapper);
        int byteToRead = lengthWrapper[0];

        Assert.assertEquals(available, byteToRead);
        client.close();
    }

    @Test(groups = {"online", "default_provider"})    
    public void testUrlRequestParametersEncoding() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();
        String requestUrl2 = URL + URLEncoder.encode(REQUEST_PARAM, "UTF-8");
        log.info(String.format("Executing request [%s] ...", requestUrl2));
        Response response = client.prepareGet(requestUrl2).execute().get();
        Assert.assertEquals(response.getStatusCode(), 301);
    }


    @Test(groups = {"online", "default_provider"})
    public void stripQueryStringTest() throws Throwable {

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build();
        AsyncHttpClient c = new AsyncHttpClient(cg);

        Response response = c.prepareGet("http://www.freakonomics.com/?p=55846")
                .execute().get();

        assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);


        c.close();
    }

    @Test(groups = {"online", "default_provider"})
    public void stripQueryStringNegativeTest() throws Throwable {

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder()
                .setRemoveQueryParamsOnRedirect(false).setFollowRedirects(true).build();
        AsyncHttpClient c = new AsyncHttpClient(cg);

        Response response = c.prepareGet("http://www.freakonomics.com/?p=55846")
                .execute().get();

        assertNotNull(response);
        assertEquals(response.getStatusCode(), 301);


        c.close();
    }

}

