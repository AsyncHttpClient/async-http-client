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

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Reverse C10K Problem test.
 * 
 * @author Hubert Iwaniuk
 */
public abstract class RC10KTest extends AbstractBasicTest {
    private static final int C10K = 1000;
    private static final String ARG_HEADER = "Arg";
    private static final int SRV_COUNT = 10;
    protected List<Server> servers = new ArrayList<Server>(SRV_COUNT);
    private int[] ports;

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        ports = new int[SRV_COUNT];
        for (int i = 0; i < SRV_COUNT; i++) {
            ports[i] = createServer();
        }
        logger.info("Local HTTP servers started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        for (Server srv : servers) {
            srv.stop();
        }
    }

    private int createServer() throws Exception {
        int port = findFreePort();
        Server srv = newJettyHttpServer(port);
        srv.setHandler(configureHandler());
        srv.start();
        servers.add(srv);
        return port;
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
        AsyncHttpClient ahc = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setMaximumConnectionsPerHost(C10K).setAllowPoolingConnection(true).build());
        try {
            List<Future<Integer>> resps = new ArrayList<Future<Integer>>(C10K);
            int i = 0;
            while (i < C10K) {
                resps.add(ahc.prepareGet(String.format("http://127.0.0.1:%d/%d", ports[i % SRV_COUNT], i)).execute(new MyAsyncHandler(i++)));
            }
            i = 0;
            for (Future<Integer> fResp : resps) {
                Integer resp = fResp.get();
                assertNotNull(resp);
                assertEquals(resp.intValue(), i++);
            }
        } finally {
            ahc.close();
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

        public STATE onBodyPartReceived(HttpResponseBodyPart event) throws Exception {
            String s = new String(event.getBodyPartBytes());
            result.compareAndSet(-1, new Integer(s.trim().equals("") ? "-1" : s));
            return STATE.CONTINUE;
        }

        public STATE onStatusReceived(HttpResponseStatus event) throws Exception {
            assertEquals(event.getStatusCode(), 200);
            return STATE.CONTINUE;
        }

        public STATE onHeadersReceived(HttpResponseHeaders event) throws Exception {
            assertEquals(event.getHeaders().getJoinedValue(ARG_HEADER, ", "), arg);
            return STATE.CONTINUE;
        }

        public Integer onCompleted() throws Exception {
            return result.get();
        }
    }
}
