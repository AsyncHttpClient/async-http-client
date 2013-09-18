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

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.asynchttpclient.async.util.EchoHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

public abstract class AbstractBasicTest {

    protected final static int TIMEOUT = 30;

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected Server server;
    protected int port1;
    protected int port2;

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {

        port1 = findFreePort();
        port2 = findFreePort();

        server = newJettyHttpServer(port1);
        server.setHandler(configureHandler());
        addHttpConnector(server, port2);
        server.start();

        logger.info("Local HTTP server started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        if (server != null)
            server.stop();
    }

    protected String getTargetUrl() {
        return String.format("http://127.0.0.1:%d/foo/test", port1);
    }

    protected String getTargetUrl2() {
        return String.format("https://127.0.0.1:%d/foo/test", port2);
    }

    public AbstractHandler configureHandler() throws Exception {
        return new EchoHandler();
    }

    public static class AsyncCompletionHandlerAdapter extends AsyncCompletionHandler<Response> {
        public Runnable runnable;

        @Override
        public Response onCompleted(Response response) throws Exception {
            return response;
        }

        @Override
        public void onThrowable(Throwable t) {
            t.printStackTrace();
            fail("Unexpected exception: " + t.getMessage(), t);
        }
    }

    public static class AsyncHandlerAdapter implements AsyncHandler<String> {

        @Override
        public void onThrowable(Throwable t) {
            t.printStackTrace();
            fail("Unexpected exception", t);
        }

        @Override
        public STATE onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
            return STATE.CONTINUE;
        }

        @Override
        public STATE onStatusReceived(final HttpResponseStatus responseStatus) throws Exception {
            return STATE.CONTINUE;
        }

        @Override
        public STATE onHeadersReceived(final HttpResponseHeaders headers) throws Exception {
            return STATE.CONTINUE;
        }

        @Override
        public String onCompleted() throws Exception {
            return "";
        }
    }

    public abstract AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config);
}
