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
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.testng.annotations.BeforeClass;

import static org.asynchttpclient.test.TestUtils.addHttpConnector;

public abstract class AbstractBasicWebSocketTest extends AbstractBasicTest {

  @BeforeClass(alwaysRun = true)
  @Override
  public void setUpGlobal() throws Exception {
    server = new Server();
    ServerConnector connector = addHttpConnector(server);
    server.setHandler(configureHandler());
    server.start();
    port1 = connector.getLocalPort();
    logger.info("Local HTTP server started successfully");
  }

  protected String getTargetUrl() {
    return String.format("ws://localhost:%d/", port1);
  }

  @Override
  public WebSocketHandler configureHandler() {
    return new WebSocketHandler() {
      @Override
      public void configure(WebSocketServletFactory factory) {
        factory.register(EchoWebSocket.class);
      }
    };
  }
}
