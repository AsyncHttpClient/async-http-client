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

import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.asynchttpclient.test.TestUtils.addHttpConnector;

public abstract class AbstractBasicTest {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractBasicTest.class);

    protected static final int TIMEOUT = 30;

    protected static Server server;
    protected static int port1 = -1;
    protected static int port2 = -1;

    @BeforeAll
    public static void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector1 = addHttpConnector(server);
        server.setHandler(configureHandler());
        ServerConnector connector2 = addHttpConnector(server);
        server.start();

        port1 = connector1.getLocalPort();
        port2 = connector2.getLocalPort();

        logger.info("Local HTTP server started successfully");
    }

    @AfterAll
    public static void tearDownGlobal() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    protected static String getTargetUrl() {
        return String.format("http://localhost:%d/foo/test", port1);
    }

    protected String getTargetUrl2() {
        return String.format("https://localhost:%d/foo/test", port2);
    }

    public static AbstractHandler configureHandler() throws Exception {
        return new EchoHandler();
    }

    public static class AsyncCompletionHandlerAdapter extends AsyncCompletionHandler<Response> {

        @Override
        public Response onCompleted(Response response) throws Exception {
            return response;
        }

        @Override
        public void onThrowable(Throwable t) {
            t.printStackTrace();
        }
    }
}
