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
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebSocketMessageTooBigTest extends AbstractBasicWebSocketTest {

    private static volatile CompletableFuture<Integer> serverCloseCode = new CompletableFuture<>();

    public static class FragmentedSender extends WebSocketAdapter {

        @Override
        public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            try {
                String fragment = "x".repeat(1000);
                for (int i = 0; i < 4; i++) {
                    getRemote().sendPartialString(fragment, i == 3);
                }
            } catch (IOException e) {
                serverCloseCode.completeExceptionally(e);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            serverCloseCode.complete(statusCode);
            super.onWebSocketClose(statusCode, reason);
        }
    }

    @Override
    public AbstractHandler configureHandler() {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(context,
                (servletContext, wsContainer) -> wsContainer.addMapping("/", FragmentedSender.class));
        return context;
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 30000)
    public void aMessageOverTheBufferLimitClosesWith1009() throws Exception {
        serverCloseCode = new CompletableFuture<>();
        try (AsyncHttpClient client = asyncHttpClient(config().setWebSocketMaxBufferSize(1024))) {
            client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().build()).get();
            assertEquals(1009, serverCloseCode.get(10, TimeUnit.SECONDS));
        }
    }
}
