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

import io.netty.resolver.InetNameResolver;
import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.LoadBalance;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.test.TestUtils.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An open WebSocket connection counts against its address under {@link LoadBalance#LEAST_CONNECTIONS}.
 */
public class LeastConnectionsWebSocketTest extends AbstractBasicWebSocketTest {

    private static NameResolver<InetAddress> fixedResolver(String... ips) throws Exception {
        final List<InetAddress> addresses = new ArrayList<>();
        for (String ip : ips) {
            addresses.add(InetAddress.getByName(ip));
        }
        return new InetNameResolver(ImmediateEventExecutor.INSTANCE) {
            @Override
            protected void doResolve(String inetHost, Promise<InetAddress> promise) {
                promise.setSuccess(addresses.get(0));
            }

            @Override
            protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
                promise.setSuccess(new ArrayList<>(addresses));
            }
        };
    }

    @Test
    public void anOpenWebSocketCountsAgainstItsAddress() throws Exception {
        NameResolver<InetAddress> resolver = fixedResolver("127.0.0.1", "127.0.0.2");
        try (AsyncHttpClient client = asyncHttpClient(config().setLoadBalance(LoadBalance.LEAST_CONNECTIONS)
                .setMaxRequestRetry(0).setConnectTimeout(Duration.ofSeconds(1)))) {
            WebSocket webSocket = client.prepareGet("ws://lc.test:" + port1 + '/').setNameResolver(resolver)
                    .execute(new WebSocketUpgradeHandler.Builder().build()).get(TIMEOUT, SECONDS);
            assertEquals("127.0.0.1", ((InetSocketAddress) webSocket.getRemoteAddress()).getAddress().getHostAddress());

            List<String> attempts = new CopyOnWriteArrayList<>();
            client.executeRequest(get("http://lc.test:" + port1 + '/').setNameResolver(resolver), new AsyncCompletionHandlerBase() {
                @Override
                public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
                    attempts.add(remoteAddress.getAddress().getHostAddress());
                }
            }).get(TIMEOUT, SECONDS);
            assertEquals("127.0.0.2", attempts.get(0), "the open WebSocket keeps a connection counted against 127.0.0.1");
            webSocket.sendCloseFrame().get(TIMEOUT, SECONDS);
        }
    }
}
