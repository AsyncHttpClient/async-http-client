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
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Reverse C1K Problem test.
 * 
 * @author Hubert Iwaniuk
 */
public class RC1KTest extends AbstractBasicTest {
    private static final int C1K = 1000;
    private static final String ARG_HEADER = "Arg";
    private static final int SRV_COUNT = 10;
    protected Server[] servers = new Server[SRV_COUNT];
    private int[] ports = new int[SRV_COUNT];

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        ports = new int[SRV_COUNT];
        for (int i = 0; i < SRV_COUNT; i++) {
            Server server = new Server();
            ServerConnector connector = addHttpConnector(server);
            server.setHandler(configureHandler());
            server.start();
            servers[i] = server;
            ports[i] = connector.getLocalPort();
        }
        logger.info("Local HTTP servers started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        for (Server srv : servers) {
            srv.stop();
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {
            public void handle(String s, Request r, HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                resp.setContentType("text/pain");
                String arg = s.substring(1);
                resp.setHeader(ARG_HEADER, arg);
                resp.setStatus(200);
                resp.getOutputStream().print(arg);
                resp.getOutputStream().flush();
                resp.getOutputStream().close();
            }
        };
    }

    @Test(timeOut = 10 * 60 * 1000, groups = "scalability")
    public void rc10kProblem() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient ahc = asyncHttpClient(config().setMaxConnectionsPerHost(C1K).setKeepAlive(true))) {
            List<Future<Integer>> resps = new ArrayList<>(C1K);
            int i = 0;
            while (i < C1K) {
                resps.add(ahc.prepareGet(String.format("http://localhost:%d/%d", ports[i % SRV_COUNT], i)).execute(new MyAsyncHandler(i++)));
            }
            i = 0;
            for (Future<Integer> fResp : resps) {
                Integer resp = fResp.get();
                assertNotNull(resp);
                assertEquals(resp.intValue(), i++);
            }
        }
    }

    private class MyAsyncHandler implements AsyncHandler<Integer> {
        private String arg;
        private AtomicInteger result = new AtomicInteger(-1);

        public MyAsyncHandler(int i) {
            arg = String.format("%d", i);
        }

        public void onThrowable(Throwable t) {
            logger.warn("onThrowable called.", t);
        }

        public State onBodyPartReceived(HttpResponseBodyPart event) throws Exception {
            String s = new String(event.getBodyPartBytes());
            result.compareAndSet(-1, new Integer(s.trim().equals("") ? "-1" : s));
            return State.CONTINUE;
        }

        public State onStatusReceived(HttpResponseStatus event) throws Exception {
            assertEquals(event.getStatusCode(), 200);
            return State.CONTINUE;
        }

        public State onHeadersReceived(HttpResponseHeaders event) throws Exception {
            assertEquals(event.getHeaders().get(ARG_HEADER), arg);
            return State.CONTINUE;
        }

        public Integer onCompleted() throws Exception {
            return result.get();
        }
    }
}
