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
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reverse C1K Problem test.
 *
 * @author Hubert Iwaniuk
 */
public class RC1KTest extends AbstractBasicTest {
    private static final int C1K = 1000;
    private static final String ARG_HEADER = "Arg";
    private static final int SRV_COUNT = 10;
    private static final Server[] servers = new Server[SRV_COUNT];
    private static int[] ports = new int[SRV_COUNT];

    @Override
    @BeforeEach
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

    @Override
    @AfterEach
    public void tearDownGlobal() throws Exception {
        for (Server srv : servers) {
            srv.stop();
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {
            @Override
            public void handle(String s, Request r, HttpServletRequest req, HttpServletResponse resp) throws IOException {
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

    @RepeatedIfExceptionsTest(repeats = 5)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 10 * 60 * 1000)
    public void rc10kProblem() throws Exception {
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

    private static class MyAsyncHandler implements AsyncHandler<Integer> {
        private final String arg;
        private final AtomicInteger result = new AtomicInteger(-1);

        MyAsyncHandler(int i) {
            arg = String.format("%d", i);
        }

        @Override
        public void onThrowable(Throwable t) {
            logger.warn("onThrowable called.", t);
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart event) {
            String s = new String(event.getBodyPartBytes());
            result.compareAndSet(-1, Integer.valueOf(s.trim().isEmpty() ? "-1" : s));
            return State.CONTINUE;
        }

        @Override
        public State onStatusReceived(HttpResponseStatus event) {
            assertEquals(event.getStatusCode(), 200);
            return State.CONTINUE;
        }

        @Override
        public State onHeadersReceived(HttpHeaders event) {
            assertEquals(event.get(ARG_HEADER), arg);
            return State.CONTINUE;
        }

        @Override
        public Integer onCompleted() {
            return result.get();
        }
    }
}
