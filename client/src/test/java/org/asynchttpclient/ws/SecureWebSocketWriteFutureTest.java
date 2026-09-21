/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.ws;

import org.asynchttpclient.AsyncHttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.BeforeEach;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;

// Under TLS a closing channel stays open until close_notify is flushed, so the pipeline alone fails a late
// write with whatever the SslHandler reports.
public class SecureWebSocketWriteFutureTest extends WebSocketWriteFutureTest {

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpsConnector(server);
        server.setHandler(configureHandler());
        server.start();
        port1 = connector.getLocalPort();
    }

    @Override
    protected String getTargetUrl() {
        return String.format("wss://localhost:%d/", port1);
    }

    @Override
    protected AsyncHttpClient newClient() {
        return asyncHttpClient(config().setUseInsecureTrustManager(true));
    }
}
