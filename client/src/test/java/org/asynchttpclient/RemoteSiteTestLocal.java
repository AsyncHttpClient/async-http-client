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
import static org.testng.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Unit tests for remote site. <br>
 * see http://github.com/MSch/ning-async-http-client-bug/tree/master
 *
 * @author Martin Schurrer
 */
public class RemoteSiteTestLocal extends HttpServerTestBase {

    public static final String URL = "http://localhost:8000/?q=";
    public static final String REQUEST_PARAM = "github github \n" + "github";

    @Test(groups = "standalone")
    public void testGoogleCom() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    	WireMock.reset();
    	stubFor(WireMock.get(urlEqualTo(mockServers.get("www.google.com").getMockRelativeUrl())).
    	        willReturn(aResponse().withStatus(200).
    			withHeader("Content-Type", "application/json").withBody("{}")));

        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(10000))) {
            Response response = client.prepareGet(mockServers.get("www.google.com").getMockUrl()).execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response, "No response received from client");
        }
    }

    @Test(groups = "standalone")
    public void testMailGoogleCom() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    	WireMock.reset();
    	stubFor(WireMock.get(urlEqualTo(mockServers.get("mail.google.com").getMockRelativeUrl())).willReturn(aResponse().
    			withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")));

        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(10000))) {
            Response response = client.prepareGet(mockServers.get("mail.google.com").getMockUrl()).execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response, "No response received from client");
            assertEquals(response.getStatusCode(), 200, "Request was not successful");
        }
    }

    @Test(groups = "standalone")
    public void testMicrosoftCom() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        WireMock.reset();
        stubFor(WireMock.get(urlEqualTo(mockServers.get("www.microsoft.com").getMockRelativeUrl())).willReturn(aResponse().
                withStatus(301).withHeader("Location", mockServers.get("www.microsoft.com").getMockRelativeUrl() + "/movedhere")));
        stubFor(WireMock.get(urlEqualTo(mockServers.get("www.microsoft.com").getMockRelativeUrl() + "/movedhere")).willReturn(aResponse().withStatus(200).
                withHeader("Content-Type", "application/json").withBody("")));
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(10000))) {
            Response response = client.prepareGet(mockServers.get("www.microsoft.com").getMockUrl()).execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response, "No response received from client");
            assertEquals(response.getStatusCode(), 301, "Expecting a permanent redirect");
        }
    }

    @Test(groups = "standalone")
    public void testGoogleComWithTimeout() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    	WireMock.reset();
    	stubFor(WireMock.get(urlEqualTo(mockServers.get("www.google.com").getMockRelativeUrl())).willReturn(aResponse().
    			withStatus(301).withHeader("Content-Type", "application/json").withBody("{}")));

        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(10000))) {
            Response response = client.prepareGet(mockServers.get("www.google.com").getMockUrl()).execute().get(10, TimeUnit.SECONDS);
            assertNotNull(response, "No response received from client");
            assertTrue(response.getStatusCode() == 301 || response.getStatusCode() == 302, "Request URL not found");
        }
    }

    @Test(groups = "standalone")
    public void asyncFullBodyProperlyRead() throws IOException, InterruptedException, ExecutionException {
    	WireMock.reset();
    	stubFor(WireMock.get(urlEqualTo(mockServers.get("www.cyberpresse.ca").getMockRelativeUrl())).willReturn(aResponse().withStatus(200).
    			withHeader("Content-Length", "2").withBody("{}")));

        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet(mockServers.get("www.cyberpresse.ca").getMockUrl()).execute().get();

            InputStream stream = response.getResponseBodyAsStream();
            int contentLength = Integer.valueOf(response.getHeader("Content-Length"));

            assertEquals(response.getStatusCode(), 200, "Expecting OK (200) response from webserver.");
            assertEquals(contentLength, IOUtils.toByteArray(stream).length, "Content length header mismatched the actual body length.");
        }
    }

    @Test(groups = "standalone")
    public void testAHC62Com() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        WireMock.reset();
        StringBuilder stubResponse = new StringBuilder();
        for (int i=0; i<3870; i++)
            stubResponse.append(' ');
        stubFor(WireMock.get(urlEqualTo(mockServers.get("www.google.com").getMockRelativeUrl())).willReturn(aResponse().withStatus(200).
                withHeader("Content-Type", "application/json").withBody(stubResponse.toString())));

        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = client.prepareGet(mockServers.get("www.google.com").getMockUrl()).execute(new AsyncHandler<Response>() {

                private Response.ResponseBuilder builder = new Response.ResponseBuilder();

                public void onThrowable(Throwable exception) {
                    exception.printStackTrace();
                }

                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                    System.out.println(bodyPart.getBodyPartBytes().length);
                    builder.accumulate(bodyPart);

                    return State.CONTINUE;
                }

                public State onStatusReceived(HttpResponseStatus responseStatus) {
                    builder.accumulate(responseStatus);
                    return State.CONTINUE;
                }

                public State onHeadersReceived(HttpResponseHeaders headers) {
                    builder.accumulate(headers);
                    return State.CONTINUE;
                }

                public Response onCompleted() {
                    return builder.build();
                }
            }).get(10, TimeUnit.SECONDS);
            assertNotNull(response, "No response received from client");
            assertTrue(response.getResponseBody().length() >= 3870, "Expected response body length to be greater than 3870");
        }
    }
}