/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.websocket;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

public abstract class AbstractBasicTest extends org.asynchttpclient.async.AbstractBasicTest {

    protected final Logger log = LoggerFactory.getLogger(AbstractBasicTest.class);

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {

        server = new Server();
        port1 = findFreePort();

        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(port1);
        server.addConnector(connector);
        WebSocketHandler _wsHandler = getWebSocketHandler();

        server.setHandler(_wsHandler);

        server.start();
        log.info("Local HTTP server started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        server.stop();
    }

    public abstract class WebSocketHandler extends HandlerWrapper implements WebSocketFactory.Acceptor {
        private final WebSocketFactory _webSocketFactory = new WebSocketFactory(this, 32 * 1024);

        public WebSocketHandler() {
            _webSocketFactory.setMaxIdleTime(10000);
        }

        public WebSocketFactory getWebSocketFactory() {
            return _webSocketFactory;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if (_webSocketFactory.acceptWebSocket(request, response) || response.isCommitted())
                return;
            super.handle(target, baseRequest, request, response);
        }

        public boolean checkOrigin(HttpServletRequest request, String origin) {
            return true;
        }
    }

    protected String getTargetUrl() {
        return String.format("ws://127.0.0.1:%d/", port1);
    }

    public abstract WebSocketHandler getWebSocketHandler();
}
