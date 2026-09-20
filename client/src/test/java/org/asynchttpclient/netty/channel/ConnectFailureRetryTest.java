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
package org.asynchttpclient.netty.channel;

import io.netty.channel.Channel;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.findFreePort;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A refused connect is retried, and the retry sends the body exactly once.
 */
public class ConnectFailureRetryTest extends AbstractBasicTest {

    private static final String BODY = "connect-retry-body";

    private final AtomicInteger requestsReceived = new AtomicInteger();
    private final AtomicInteger bodiesReceived = new AtomicInteger();

    @Override
    public AbstractHandler configureHandler() {
        return new CountingEchoHandler();
    }

    @Test
    public void refusedConnectIsRetriedOnTheNextResolution() throws Exception {
        requestsReceived.set(0);
        // First resolution: a closed port. Later ones: the live server. Only a retry can succeed.
        SwitchingResolverGroup resolverGroup = new SwitchingResolverGroup(findFreePort(), port1);
        try {
            try (AsyncHttpClient client = asyncHttpClient(config()
                    .setAddressResolverGroup(resolverGroup)
                    .setMaxRequestRetry(1))) {
                Response response = client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode());
                assertEquals(2, resolverGroup.resolutions(), "the refused connect was not retried");
                assertEquals(1, requestsReceived.get(), "the refused attempt must not have reached the server");
            }
        } finally {
            resolverGroup.close();
        }
    }

    @Test
    public void refusedConnectReplaysThePostBodyExactlyOnce() throws Exception {
        requestsReceived.set(0);
        bodiesReceived.set(0);
        SwitchingResolverGroup resolverGroup = new SwitchingResolverGroup(findFreePort(), port1);
        try {
            try (AsyncHttpClient client = asyncHttpClient(config()
                    .setAddressResolverGroup(resolverGroup)
                    .setMaxRequestRetry(1))) {
                ConnectCountingHandler handler = new ConnectCountingHandler();
                Response response = client.preparePost(getTargetUrl())
                        .setBody(BODY)
                        .execute(handler)
                        .get(TIMEOUT, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode());
                assertEquals(BODY, response.getResponseBody());
                assertEquals(1, requestsReceived.get(), "the request was sent more than once");
                assertEquals(1, bodiesReceived.get(), "the body was sent more than once");
                assertEquals(1, handler.connectSuccesses.get(), "a written request was replayed");
                assertEquals(1, handler.connectFailures.get(), "the refused attempt was not counted");
            }
        } finally {
            resolverGroup.close();
        }
    }

    private static final class ConnectCountingHandler extends AsyncCompletionHandler<Response> {

        private final AtomicInteger connectSuccesses = new AtomicInteger();
        private final AtomicInteger connectFailures = new AtomicInteger();

        @Override
        public void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
            connectSuccesses.incrementAndGet();
        }

        @Override
        public void onTcpConnectFailure(InetSocketAddress remoteAddress, Throwable cause) {
            connectFailures.incrementAndGet();
        }

        @Override
        public Response onCompleted(Response response) {
            return response;
        }
    }

    private final class CountingEchoHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            requestsReceived.incrementAndGet();
            String body = IOUtils.toString(request.getInputStream(), StandardCharsets.UTF_8);
            if (!body.isEmpty()) {
                bodiesReceived.incrementAndGet();
            }
            response.setStatus(200);
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
            baseRequest.setHandled(true);
        }
    }

    // Refusing address first, then the live one.
    private static final class SwitchingResolverGroup extends AddressResolverGroup<InetSocketAddress> {

        private final AtomicInteger resolutions = new AtomicInteger();
        private final int firstPort;
        private final int remainingPort;

        SwitchingResolverGroup(int firstPort, int remainingPort) {
            this.firstPort = firstPort;
            this.remainingPort = remainingPort;
        }

        int resolutions() {
            return resolutions.get();
        }

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress.class) {

                @Override
                protected boolean doIsResolved(InetSocketAddress address) {
                    return !address.isUnresolved();
                }

                @Override
                protected void doResolve(InetSocketAddress unresolvedAddress, Promise<InetSocketAddress> promise) {
                    promise.setSuccess(next());
                }

                @Override
                protected void doResolveAll(InetSocketAddress unresolvedAddress, Promise<List<InetSocketAddress>> promise) {
                    promise.setSuccess(Collections.singletonList(next()));
                }
            };
        }

        private InetSocketAddress next() {
            int port = resolutions.getAndIncrement() == 0 ? firstPort : remainingPort;
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }
    }
}
