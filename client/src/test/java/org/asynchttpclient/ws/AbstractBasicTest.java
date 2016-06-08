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

import static org.asynchttpclient.test.TestUtils.addHttpConnector;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

public abstract class AbstractBasicTest extends org.asynchttpclient.AbstractBasicTest {

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(getWebSocketHandler());
        server.start();
        port1 = connector.getLocalPort();
        logger.info("Local HTTP server started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        server.stop();
    }

    protected String getTargetUrl() {
        return String.format("ws://localhost:%d/", port1);
    }

    public abstract WebSocketHandler getWebSocketHandler();
}
