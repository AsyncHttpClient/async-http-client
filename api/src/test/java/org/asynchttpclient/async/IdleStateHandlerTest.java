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

import static org.asynchttpclient.async.util.TestUtils.*;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public abstract class IdleStateHandlerTest extends AbstractBasicTest {

    private class IdleStateHandler extends AbstractHandler {

        public void handle(String s, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

            try {
                Thread.sleep(20 * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();
        server = newJettyHttpServer(port1);
        server.setHandler(new IdleStateHandler());
        server.start();
        logger.info("Local HTTP server started successfully");
    }

    @Test(groups = { "online", "default_provider" })
    public void idleStateTest() throws Exception {
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setIdleConnectionInPoolTimeoutInMs(10 * 1000).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);

        try {
            c.prepareGet(getTargetUrl()).execute().get();
        } catch (ExecutionException e) {
            fail("Should allow to finish processing request.", e);
        } finally {
            c.close();
        }
    }
}
