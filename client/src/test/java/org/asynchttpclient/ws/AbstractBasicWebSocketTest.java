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
package org.asynchttpclient.ws;

import org.asynchttpclient.AbstractBasicTest;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractBasicWebSocketTest extends AbstractBasicTest {

    private int serversStarted;
    private int serversStopped;

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(configureHandler());
        server.start();
        serversStarted++;
        port1 = connector.getLocalPort();
        logger.info("Local HTTP server started successfully");
    }

    // An override does not inherit @AfterAll. Without this annotation no server is ever stopped.
    @Override
    @AfterEach
    public void tearDownGlobal() throws Exception {
        if (server != null) {
            server.stop();
            serversStopped++;
        }
    }

    @AfterAll
    public void assertEveryStartedServerWasStopped() {
        // >= because ProxyTunnellingTest starts its own servers but stops them through this class.
        assertTrue(serversStopped >= serversStarted,
                "started " + serversStarted + " Jetty servers but stopped only " + serversStopped
                        + "; a lifecycle override most likely dropped its annotation");
    }

    @Override
    protected String getTargetUrl() {
        return String.format("ws://localhost:%d/", port1);
    }

    @Override
    public AbstractHandler configureHandler() {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        // Configure specific websocket behavior
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            // Configure default max size
            wsContainer.setMaxTextMessageSize(65535);

            // Add websockets
            wsContainer.addMapping("/", EchoWebSocket.class);
        });
        return context;
    }
}
