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
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.asynchttpclient.handler.MaxRedirectException;
import org.testng.annotations.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

import io.netty.channel.ChannelOption;

public class BasicHttpTestLocal extends HttpServerTestBase {

    @Test(groups = { "standalone", "async" }, expectedExceptions = { NullPointerException.class })
    public void asyncNullSchemeTest() throws IOException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            client.prepareGet("www.sun.com").execute();
        }
    }

    @Test(groups = { "standalone", "async" }, expectedExceptions = { ConnectException.class, UnresolvedAddressException.class, UnknownHostException.class })
    public void asyncConnectInvalidHandlerHost() throws Throwable {
        try (AsyncHttpClient client = asyncHttpClient()) {

            final AtomicReference<Throwable> exception = new AtomicReference<>();
            final CountDownLatch countDownLatch = new CountDownLatch(1);

            client.prepareGet("http://null.apache.org:9999/").execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public void onThrowable(Throwable t) {
                    exception.set(t);
                    countDownLatch.countDown();
                }
            });

            if (!countDownLatch.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
            assertNotNull(exception.get(), "Exception expected but not found");
            throw exception.get();
        }
    }

    @Test(groups = { "standalone", "async" })
    public void asyncDoGetMaxRedirectTest() throws IOException, InterruptedException {
    	WireMock.reset();
    	stubFor(WireMock.get(urlEqualTo(mockServers.get("google.com").getMockRelativeUrl())).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("")));
    	
        try (AsyncHttpClient client = asyncHttpClient(config().setMaxRedirects(0).setFollowRedirect(true))) {
            // Use a l in case the assert fail
            final CountDownLatch countDownLatch = new CountDownLatch(1);

            AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) {
                    fail("Should not be here");
                    return response;
                }

                @Override
                public void onThrowable(Throwable exception) {
                    exception.printStackTrace();
                    try {
                        assertEquals(exception.getClass(), MaxRedirectException.class);
                    } finally {
                        countDownLatch.countDown();
                    }
                }
            };

            client.prepareGet(mockServers.get("google.com").getMockUrl()).execute(handler);

            if (!countDownLatch.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        }
    }

    @Test(groups = { "standalone", "async" })
    public void asyncDoGetNestedTest() throws IOException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
        	WireMock.reset();
        	stubFor(WireMock.get(urlEqualTo(mockServers.get("www.lemonde.fr").getMockRelativeUrl())).willReturn(aResponse().
        			withStatus(200).withHeader("Content-Type", "application/json").withBody("")));

            final CountDownLatch countDownLatch = new CountDownLatch(2);

            final AsyncCompletionHandlerAdapter handler = new AsyncCompletionHandlerAdapter() {

                private final static int MAX_NESTED = 2;

                private AtomicInteger nestedCount = new AtomicInteger(0);

                @Override
                public Response onCompleted(Response response) {
                    try {
                        if (nestedCount.getAndIncrement() < MAX_NESTED) {
                            System.out.println("Executing a nested request: " + nestedCount);
                            client.prepareGet(mockServers.get("www.lemonde.fr").getMockUrl()).execute(this);
                        }
                    } finally {
                        countDownLatch.countDown();
                    }
                    return response;
                }

                @Override
                public void onThrowable(Throwable exception) {
                    exception.printStackTrace();
                }
            };

            client.prepareGet(mockServers.get("www.lemonde.fr").getMockUrl()).execute(handler);

            if (!countDownLatch.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        }
    }

    @Test(groups = { "standalone", "async" })
    public void asyncDoGetStreamAndBodyTest() throws IOException, InterruptedException, ExecutionException {
    	WireMock.reset();
    	stubFor(WireMock.get(urlEqualTo(mockServers.get("www.lemonde.fr").getMockRelativeUrl())).willReturn(aResponse().
    			withStatus(200).withHeader("Content-Type", "application/json").withBody("")));

        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet(mockServers.get("www.lemonde.fr").getMockUrl()).execute().get();
            assertEquals(response.getStatusCode(), 200, "Request was not successful");
        }
    }

    @Test(groups = { "standalone", "async" })
    public void asyncUrlWithoutPathTest() throws IOException, InterruptedException, ExecutionException {
    	WireMock.reset();
    	stubFor(WireMock.get(urlEqualTo(mockServers.get("www.lemonde.fr").getMockRelativeUrl())).willReturn(aResponse().withStatus(200).
    			withHeader("Content-Type", "application/json").withBody("")));

        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet(mockServers.get("www.lemonde.fr").getMockUrl()).execute().get();
            assertEquals(response.getStatusCode(), 200, "Request was not successful");
        }
    }

    @Test(groups = "standalone")
    public void testAwsS3() throws IOException, InterruptedException, ExecutionException {
    	WireMock.reset();
    	stubFor(WireMock.get(urlEqualTo(mockServers.get("test.s3.amazonaws.com").getMockRelativeUrl())).willReturn(aResponse().
    			withStatus(403).withHeader("Content-Type", "application/json").withBody("{}")));

        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet(mockServers.get("test.s3.amazonaws.com").getMockUrl()).execute().get();
            if (response.getResponseBody() == null || response.getResponseBody().equals("")) {
                fail("No response Body");
            } else {
                assertEquals(response.getStatusCode(), 403, "Expected resource access to be forbidden");
            }
        }
    }

    @Test(groups = "standalone")
    public void testAsyncHttpProviderConfig() throws IOException, InterruptedException, ExecutionException {
    	WireMock.reset();
    	stubFor(WireMock.get(urlEqualTo(mockServers.get("test.s3.amazonaws.com").getMockRelativeUrl())).willReturn(aResponse().
    			withStatus(403).withHeader("Content-Type", "application/json").withBody("{}")));

        try (AsyncHttpClient client = asyncHttpClient(config().addChannelOption(ChannelOption.TCP_NODELAY, Boolean.TRUE))) {
            Response response = client.prepareGet(mockServers.get("test.s3.amazonaws.com").getMockUrl()).execute().get();
            if (response.getResponseBody() == null || response.getResponseBody().equals("")) {
                fail("No response Body");
            } else {
                assertEquals(response.getStatusCode(), 403, "Expected resource access to be forbidden");
            }
        }
    }
}
