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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.head;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests HEAD request that gets 302 response.
 *
 * @author Hubert Iwaniuk
 */
public class Head302Test extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new Head302handler();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testHEAD302() throws Exception {
        AsyncHttpClientConfig clientConfig = new DefaultAsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        try (AsyncHttpClient client = asyncHttpClient(clientConfig)) {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = head("http://localhost:" + port1 + "/Test").build();

            Response response = client.executeRequest(request, new AsyncCompletionHandlerBase() {
                @Override
                public Response onCompleted(Response response) throws Exception {
                    l.countDown();
                    return super.onCompleted(response);
                }
            }).get(3, TimeUnit.SECONDS);

            if (l.await(TIMEOUT, TimeUnit.SECONDS)) {
                assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
                System.out.println(response.getResponseBody());
                // TODO: 19-11-2022 PTAL
//                assertTrue(response.getResponseBody().endsWith("_moved"));
            } else {
                fail("Timeout out");
            }
        }
    }

    /**
     * Handler that does Found (302) in response to HEAD method.
     */
    private static class Head302handler extends AbstractHandler {
        @Override
        public void handle(String s, org.eclipse.jetty.server.Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if ("HEAD".equalsIgnoreCase(request.getMethod())) {
                // See https://github.com/AsyncHttpClient/async-http-client/issues/1728#issuecomment-700007980
                // When setFollowRedirect == TRUE, a follow-up request to a HEAD request will also be a HEAD.
                // This will cause an infinite loop, which will error out once the maximum amount of redirects is hit (default 5).
                // Instead, we (arbitrarily) choose to allow for 3 redirects and then return a 200.
                if (request.getRequestURI().endsWith("_moved_moved_moved")) {
                    response.setStatus(HttpServletResponse.SC_OK);
                } else {
                    response.setStatus(HttpServletResponse.SC_FOUND); // 302
                    response.setHeader("Location", request.getPathInfo() + "_moved");
                }
            } else if ("GET".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_OK);
            } else {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }

            r.setHandled(true);
        }
    }
}
