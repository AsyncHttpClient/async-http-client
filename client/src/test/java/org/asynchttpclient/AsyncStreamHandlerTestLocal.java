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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.options;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotEquals;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

import io.netty.handler.codec.http.HttpHeaders;

public class AsyncStreamHandlerTestLocal extends HttpServerTestBase {

    @Test(groups = "standalone")
    public void asyncStream302RedirectWithBody() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    	WireMock.reset();
    	stubFor(get(urlEqualTo(mockServers.get("google.com").getMockRelativeUrl())).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").
    			withHeader("Server","gws").withBody("{}")));

        final AtomicReference<Integer> statusCode = new AtomicReference<>(0);
        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            Future<String> futureResponse = client.prepareGet(mockServers.get("google.com").getMockUrl()).execute(new AsyncHandlerAdapter() {

                public State onStatusReceived(HttpResponseStatus status) {
                    statusCode.set(status.getStatusCode());
                    return State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) {
                    responseHeaders.set(content.getHeaders());
                    return State.CONTINUE;
                }

                @Override
                public String onCompleted() {
                    return null;
                }
            });

            futureResponse.get(20, TimeUnit.SECONDS);
            assertNotEquals(statusCode.get(), 302, "Target webserver seems to have been moved temporarily");
            HttpHeaders headers = responseHeaders.get();
            assertNotNull(headers, "Expecting the webserver to have set headers, but found none through the client");
            assertEquals(headers.get("server"), "gws", "Expecting the webserver to have set a 'gws' header, but found none through the client");
        }
    }
    
    @Test(groups = "standalone")
    public void asyncOptionsTest() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    	WireMock.reset();
    	stubFor(options(urlEqualTo(mockServers.get("www.apache.org").getMockRelativeUrl())).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}").
    			          withHeader("Allow","GET,HEAD,OPTIONS,POST,TRACE").withBody("{}")));

        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();

        try (AsyncHttpClient client = asyncHttpClient()) {
            final String[] expectedHeaders = { "GET", "HEAD", "OPTIONS", "POST", "TRACE" };
            Future<String> futureReponse = client.prepareOptions(mockServers.get("www.apache.org").getMockUrl()).execute(new AsyncHandlerAdapter() {

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) {
                    responseHeaders.set(content.getHeaders());
                    return State.ABORT;
                }

                @Override
                public String onCompleted() {
                    return "OK";
                }
            });

            futureReponse.get(20, TimeUnit.SECONDS) ;
            HttpHeaders headers = responseHeaders.get();
            assertNotNull(headers);
            String[] headerValues = headers.get(HttpHeaders.Names.ALLOW).split(",|, ");
            assertNotNull(headerValues, "Expecting the webserver to have set headers, but found none through the client");
            assertEquals(headerValues.length, expectedHeaders.length, "No. of headers returned by the client does not match no. of headers set by the webserver");
            Arrays.sort(headerValues);
            assertEquals(headerValues, expectedHeaders, "The headers set by the webserver didn't match the headers returned in the response from the client");
        }
    }
}
