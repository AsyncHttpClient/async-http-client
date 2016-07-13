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

import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

/**
 * Tests HEAD request that gets 302 response.
 * 
 * @author Hubert Iwaniuk
 */
public class Head302Test extends AbstractBasicTest {

    /**
     * Handler that does Found (302) in response to HEAD method.
     */
    private static class Head302handler extends AbstractHandler {
        public void handle(String s, org.eclipse.jetty.server.Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if ("HEAD".equalsIgnoreCase(request.getMethod())) {
                if (request.getPathInfo().endsWith("_moved")) {
                    response.setStatus(HttpServletResponse.SC_OK);
                } else {
                    response.setStatus(HttpServletResponse.SC_FOUND); // 302
                    response.setHeader("Location", request.getPathInfo() + "_moved");
                }
            } else { // this handler is to handle HEAD request
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new Head302handler();
    }

    @Test(groups = "standalone")
    public void testHEAD302() throws IOException, BrokenBarrierException, InterruptedException, ExecutionException, TimeoutException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = head("http://localhost:" + port1 + "/Test").build();

            client.executeRequest(request, new AsyncCompletionHandlerBase() {
                @Override
                public Response onCompleted(Response response) throws Exception {
                    l.countDown();
                    return super.onCompleted(response);
                }
            }).get(3, TimeUnit.SECONDS);

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        }
    }
}
