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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientImpl;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.util.StandardCharsets;
import org.testng.annotations.Test;

/**
 * Unit tests for remote site.
 * <p/>
 * see http://github.com/MSch/ning-async-http-client-bug/tree/master
 * 
 * @author Martin Schurrer
 */
public abstract class RemoteSiteTest extends AbstractBasicTest {

    public static final String URL = "http://google.com?q=";
    public static final String REQUEST_PARAM = "github github \n" + "github";

    @Test(groups = { "online", "default_provider" })
    public void testGoogleCom() throws Exception {
        AsyncHttpClientImpl c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(10000).build());
        try {
            Response response = c.prepareGet("http://www.google.com/").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void testMailGoogleCom() throws Exception {
        AsyncHttpClientImpl c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(10000).build());
        try {
            Response response = c.prepareGet("http://mail.google.com/").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void testMicrosoftCom() throws Exception {
        AsyncHttpClientImpl c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(10000).build());
        try {
            Response response = c.prepareGet("http://microsoft.com/").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 301);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void testWwwMicrosoftCom() throws Exception {
        AsyncHttpClientImpl c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(10000).build());
        try {
            Response response = c.prepareGet("http://www.microsoft.com/").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 302);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void testUpdateMicrosoftCom() throws Exception {
        AsyncHttpClientImpl c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(10000).build());
        try {
            Response response = c.prepareGet("http://update.microsoft.com/").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 302);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void testGoogleComWithTimeout() throws Exception {
        AsyncHttpClientImpl c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(10000).build());
        try {
            Response response = c.prepareGet("http://google.com/").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 301);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void asyncStatusHEADContentLenghtTest() throws Exception {
        AsyncHttpClientImpl p = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
        try {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = new RequestBuilder("HEAD").setUrl("http://www.google.com/").build();

            p.executeRequest(request, new AsyncCompletionHandlerAdapter() {
                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        return response;
                    } finally {
                        l.countDown();
                    }
                }
            }).get();

            if (!l.await(5, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        } finally {
            p.close();
        }
    }

    @Test(groups = { "online", "default_provider" }, enabled = false)
    public void invalidStreamTest2() throws Exception {
        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(10000).setFollowRedirects(true).setAllowPoolingConnection(false)
                .setMaximumNumberOfRedirects(6).build();

        AsyncHttpClientImpl c = getAsyncHttpClient(config);
        try {
            Response response = c.prepareGet("http://bit.ly/aUjTtG").execute().get();
            if (response != null) {
                System.out.println(response);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            assertNotNull(t.getCause());
            assertEquals(t.getCause().getMessage(), "invalid version format: ICY");
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void asyncFullBodyProperlyRead() throws Exception {
        final AsyncHttpClientImpl client = getAsyncHttpClient(null);
        try {
            Response r = client.prepareGet("http://www.cyberpresse.ca/").execute().get();

            InputStream stream = r.getResponseBodyAsStream();
            int contentLength = Integer.valueOf(r.getHeader("Content-Length"));

            assertEquals(contentLength, IOUtils.toByteArray(stream).length);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void testUrlRequestParametersEncoding() throws Exception {
        AsyncHttpClientImpl client = getAsyncHttpClient(null);
        try {
            String requestUrl2 = URL + URLEncoder.encode(REQUEST_PARAM, StandardCharsets.UTF_8.name());
            logger.info(String.format("Executing request [%s] ...", requestUrl2));
            Response response = client.prepareGet(requestUrl2).execute().get();
            assertEquals(response.getStatusCode(), 301);
        } finally {
            client.close();
        }
    }

    /**
     * See https://issues.sonatype.org/browse/AHC-61
     * 
     * @throws Exception
     */
    @Test(groups = { "online", "default_provider" })
    public void testAHC60() throws Exception {
        AsyncHttpClientImpl client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareGet("http://www.meetup.com/stackoverflow/Mountain-View-CA/").execute().get();
            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void stripQueryStringTest() throws Exception {

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build();
        AsyncHttpClientImpl c = getAsyncHttpClient(cg);
        try {
            Response response = c.prepareGet("http://www.freakonomics.com/?p=55846").execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void stripQueryStringNegativeTest() throws Exception {

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setRemoveQueryParamsOnRedirect(false).setFollowRedirects(true).build();
        AsyncHttpClientImpl c = getAsyncHttpClient(cg);
        try {
            Response response = c.prepareGet("http://www.freakonomics.com/?p=55846").execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 301);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void evilCoookieTest() throws Exception {
        AsyncHttpClientImpl c = getAsyncHttpClient(null);
        try {
            RequestBuilder builder2 = new RequestBuilder("GET");
            builder2.setFollowRedirects(true);
            builder2.setUrl("http://www.google.com/");
            builder2.addHeader("Content-Type", "text/plain");
            builder2.addCookie(new Cookie("evilcookie", "test", "test", ".google.com", "/", -1L, 10, false, false));
            Request request2 = builder2.build();
            Response response = c.executeRequest(request2).get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "online", "default_provider" }, enabled = false)
    public void testAHC62Com() throws Exception {
        AsyncHttpClientImpl c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
        try {
            Response response = c.prepareGet("http://api.crunchbase.com/v/1/financial-organization/kinsey-hills-group.js").execute(new AsyncHandler<Response>() {

                private Response.ResponseBuilder builder = new Response.ResponseBuilder();

                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                }

                public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                    System.out.println(bodyPart.getBodyPartBytes().length);
                    builder.accumulate(bodyPart);

                    return STATE.CONTINUE;
                }

                public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    builder.accumulate(responseStatus);
                    return STATE.CONTINUE;
                }

                public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                    builder.accumulate(headers);
                    return STATE.CONTINUE;
                }

                public Response onCompleted() throws Exception {
                    return builder.build();
                }
            }).get(10, TimeUnit.SECONDS);
            assertNotNull(response);
            assertTrue(response.getResponseBody().length() >= 3870);
        } finally {
            c.close();
        }
    }
}
