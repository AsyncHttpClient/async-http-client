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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import org.apache.log4j.BasicConfigurator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;


public class IdleStateHandlerTest extends AbstractBasicTest {
    private final AtomicBoolean isSet = new AtomicBoolean(false);

    private class IdleStateHandler extends AbstractHandler {


        public void handle(String s,
                           Request r,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse) throws IOException, ServletException {

            try {
                Thread.sleep(20 * 1000);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
            httpResponse.setStatus(200);
                httpResponse.getOutputStream().flush();
                httpResponse.getOutputStream().close();
        }
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();
        BasicConfigurator.configure();

        port1 = findFreePort();
        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port1);
        server.addConnector(listener);

        server.setHandler(new IdleStateHandler());
        server.start();
        log.info("Local HTTP server started successfully");
    }

    @Test(groups = "online")
    public void idleStateTest() throws Throwable {
        isSet.getAndSet(false);
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setIdleConnectionTimeoutInMs(10 * 1000).build();
        AsyncHttpClient c = new AsyncHttpClient(cg);

        try {
            c.prepareGet(getTargetUrl()).execute().get();
        } catch (ExecutionException e) {
            assertEquals(e.getCause().getMessage(),"No response received. Connection timed out after 10000");
        }

    }
}