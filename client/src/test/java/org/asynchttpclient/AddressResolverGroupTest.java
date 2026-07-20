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
package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.test.EventCollectingHandler;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.test.TestUtils.isExternalNetworkAvailable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class AddressResolverGroupTest extends HttpTest {

    private static final String GOOGLE_URL = "https://www.google.com/";
    private static final String EXAMPLE_URL = "https://www.example.com/";
    private static final String INITIAL_HOST = "initial.test";
    private static final String REDIRECT_HOST = "redirect.test";
    private static final String SOCKS_HOST = "socks.test";

    private HttpServer server;

    @BeforeEach
    public void start() throws Throwable {
        server = new HttpServer();
        server.start();
    }

    @AfterEach
    public void stop() throws Throwable {
        server.close();
    }

    private String getTargetUrl() {
        return server.getHttpUrl() + "/foo/bar";
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void requestWithDnsAddressResolverGroupSucceeds() throws Throwable {
        DnsAddressResolverGroup resolverGroup = new DnsAddressResolverGroup(
                NioDatagramChannel.class,
                DnsServerAddressStreamProviders.platformDefault());

        withClient(config().setAddressResolverGroup(resolverGroup)).run(client ->
                withServer(server).run(server -> {
                    server.enqueueOk();
                    Response response = client.prepareGet(getTargetUrl()).execute().get(3, SECONDS);
                    assertEquals(200, response.getStatusCode());
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void dnsResolverGroupFiresHostnameResolutionEvents() throws Throwable {
        DnsAddressResolverGroup resolverGroup = new DnsAddressResolverGroup(
                NioDatagramChannel.class,
                DnsServerAddressStreamProviders.platformDefault());

        withClient(config().setAddressResolverGroup(resolverGroup)).run(client ->
                withServer(server).run(server -> {
                    server.enqueueOk();
                    Request request = get(getTargetUrl()).build();
                    EventCollectingHandler handler = new EventCollectingHandler();
                    client.executeRequest(request, handler).get(3, SECONDS);
                    handler.waitForCompletion(3, SECONDS);

                    Object[] expectedEvents = {
                            CONNECTION_POOL_EVENT,
                            HOSTNAME_RESOLUTION_EVENT,
                            HOSTNAME_RESOLUTION_SUCCESS_EVENT,
                            CONNECTION_OPEN_EVENT,
                            CONNECTION_SUCCESS_EVENT,
                            REQUEST_SEND_EVENT,
                            HEADERS_WRITTEN_EVENT,
                            STATUS_RECEIVED_EVENT,
                            HEADERS_RECEIVED_EVENT,
                            CONNECTION_OFFER_EVENT,
                            COMPLETED_EVENT};

                    assertArrayEquals(expectedEvents, handler.firedEvents.toArray(),
                            "Got " + Arrays.toString(handler.firedEvents.toArray()));
                }));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void defaultConfigDoesNotSetAddressResolverGroup() {
        DefaultAsyncHttpClientConfig config = config().build();
        assertNull(config.getAddressResolverGroup(),
                "Default config should not have an AddressResolverGroup");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void customNameResolverOnRedirectKeepsEventLoopContext() throws Throwable {
        server.enqueueRedirect(302, "http://" + REDIRECT_HOST + ':' + server.getHttpPort() + "/target");
        server.enqueueOk();

        EventLoopProbeNameResolver resolver = new EventLoopProbeNameResolver();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config().setFollowRedirect(true).build())) {
            resolver.setEventLoopGroup(client.channelManager().getEventLoopGroup());

            Response response = client.executeRequest(get("http://" + INITIAL_HOST + ':' + server.getHttpPort() + "/start")
                            .setNameResolver(resolver)
                            .build())
                    .get(5, SECONDS);

            assertEquals(200, response.getStatusCode());
        }

        assertTrue(resolver.redirectResolutionAttempted.get(), "redirect host should have been resolved");
        assertTrue(resolver.redirectResolutionOnEventLoop.get(), "custom resolver should retain its calling context");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void customSocksProxyResolverKeepsEventLoopContext() throws Throwable {
        EventLoopProbeNameResolver resolver = new EventLoopProbeNameResolver();
        ProxyServer proxy = new ProxyServer.Builder(SOCKS_HOST, 1080)
                .setProxyType(ProxyType.SOCKS_V5)
                .build();

        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config().build())) {
            EventLoopGroup eventLoopGroup = client.channelManager().getEventLoopGroup();
            resolver.setEventLoopGroup(eventLoopGroup);

            CountDownLatch complete = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            eventLoopGroup.next().execute(() -> client.channelManager()
                    .getBootstrap(Uri.create("http://target.test/"), resolver, proxy)
                    .addListener(bootstrap -> {
                        if (!bootstrap.isSuccess()) {
                            failure.set(bootstrap.cause());
                        }
                        complete.countDown();
                    }));

            assertTrue(complete.await(5, SECONDS), "SOCKS bootstrap resolution should complete");
            assertNull(failure.get(), "SOCKS bootstrap should resolve the proxy host");
        }

        assertTrue(resolver.socksResolutionAttempted.get(), "SOCKS proxy host should have been resolved");
        assertTrue(resolver.socksResolutionOnEventLoop.get(), "custom resolver should retain its calling context");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void defaultNameResolverOnRedirectUsesOffloadPool() throws Throwable {
        server.enqueueRedirect(302, "http://localhost:" + server.getHttpPort() + "/target");
        server.enqueueOk();

        String poolName = "ahc-fallback-resolver-enabled";
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setFollowRedirect(true)
                .setThreadPoolName(poolName)
                .setFallbackNameResolverOffloadEnabled(true))) {
            Response response = client.prepareGet("http://127.0.0.1:" + server.getHttpPort() + "/start")
                    .execute()
                    .get(5, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertTrue(hasLiveThreadNamed(poolName + "-resolver"),
                    "redirect fallback DNS should use the resolver offload pool");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void defaultNameResolverOnRedirectKeepsInlineBehavior() throws Throwable {
        server.enqueueRedirect(302, "http://localhost:" + server.getHttpPort() + "/target");
        server.enqueueOk();

        String poolName = "ahc-fallback-resolver-disabled";
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setFollowRedirect(true)
                .setThreadPoolName(poolName))) {
            Response response = client.prepareGet("http://127.0.0.1:" + server.getHttpPort() + "/start")
                    .execute()
                    .get(5, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertFalse(hasLiveThreadNamed(poolName + "-resolver"),
                    "disabled offload must not create a resolver worker");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void unknownHostWithDnsResolverGroupFails() throws Throwable {
        DnsAddressResolverGroup resolverGroup = new DnsAddressResolverGroup(
                NioDatagramChannel.class,
                DnsServerAddressStreamProviders.platformDefault());

        withClient(config().setAddressResolverGroup(resolverGroup)).run(client -> {
            try {
                client.prepareGet("http://nonexistent.invalid/foo").execute().get(10, SECONDS);
                fail("Request to nonexistent host should have thrown an exception");
            } catch (ExecutionException e) {
                assertNotNull(e.getCause(), "Should have a cause for the DNS failure");
            }
        });
    }

    @Tag("external")
    @RepeatedIfExceptionsTest(repeats = 5)
    public void resolveRealDomainWithDnsResolverGroup() throws Throwable {
        assumeTrue(isExternalNetworkAvailable(), "External network not available - skipping test");

        DnsAddressResolverGroup resolverGroup = new DnsAddressResolverGroup(
                NioDatagramChannel.class,
                DnsServerAddressStreamProviders.platformDefault());

        try (AsyncHttpClient client = asyncHttpClient(config().setAddressResolverGroup(resolverGroup))) {
            Response response = client.prepareGet(GOOGLE_URL).execute().get(20, TimeUnit.SECONDS);
            assertNotNull(response);
            assertTrue(response.getStatusCode() >= 200 && response.getStatusCode() < 400,
                    "Expected successful HTTP status but got " + response.getStatusCode());
        }
    }

    @Tag("external")
    @RepeatedIfExceptionsTest(repeats = 5)
    public void resolveMultipleRealDomainsWithDnsResolverGroup() throws Throwable {
        assumeTrue(isExternalNetworkAvailable(), "External network not available - skipping test");

        DnsAddressResolverGroup resolverGroup = new DnsAddressResolverGroup(
                NioDatagramChannel.class,
                DnsServerAddressStreamProviders.platformDefault());

        try (AsyncHttpClient client = asyncHttpClient(config().setAddressResolverGroup(resolverGroup))) {
            Response response1 = client.prepareGet(GOOGLE_URL).execute().get(20, TimeUnit.SECONDS);
            assertNotNull(response1);
            assertTrue(response1.getStatusCode() >= 200 && response1.getStatusCode() < 400,
                    "Expected successful HTTP status for google.com but got " + response1.getStatusCode());

            Response response2 = client.prepareGet(EXAMPLE_URL).execute().get(20, TimeUnit.SECONDS);
            assertNotNull(response2);
            assertTrue(response2.getStatusCode() >= 200 && response2.getStatusCode() < 400,
                    "Expected successful HTTP status for example.com but got " + response2.getStatusCode());
        }
    }

    private static final class EventLoopProbeNameResolver extends InetNameResolver {
        private final AtomicReference<EventLoopGroup> eventLoopGroup = new AtomicReference<>();
        private final AtomicBoolean redirectResolutionAttempted = new AtomicBoolean();
        private final AtomicBoolean redirectResolutionOnEventLoop = new AtomicBoolean();
        private final AtomicBoolean socksResolutionAttempted = new AtomicBoolean();
        private final AtomicBoolean socksResolutionOnEventLoop = new AtomicBoolean();

        EventLoopProbeNameResolver() {
            super(ImmediateEventExecutor.INSTANCE);
        }

        void setEventLoopGroup(EventLoopGroup eventLoopGroup) {
            this.eventLoopGroup.set(eventLoopGroup);
        }

        @Override
        protected void doResolve(String inetHost, Promise<InetAddress> promise) {
            recordResolution(inetHost);
            promise.setSuccess(InetAddress.getLoopbackAddress());
        }

        @Override
        protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
            recordResolution(inetHost);
            promise.setSuccess(Collections.singletonList(InetAddress.getLoopbackAddress()));
        }

        private void recordResolution(String inetHost) {
            boolean onEventLoop = isOnEventLoop();
            if (REDIRECT_HOST.equals(inetHost)) {
                redirectResolutionAttempted.set(true);
                redirectResolutionOnEventLoop.set(onEventLoop);
            } else if (SOCKS_HOST.equals(inetHost)) {
                socksResolutionAttempted.set(true);
                socksResolutionOnEventLoop.set(onEventLoop);
            }
        }

        private boolean isOnEventLoop() {
            EventLoopGroup group = eventLoopGroup.get();
            if (group == null) {
                return false;
            }
            Thread currentThread = Thread.currentThread();
            for (EventExecutor executor : group) {
                if (executor.inEventLoop(currentThread)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean hasLiveThreadNamed(String namePart) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().contains(namePart)) {
                return true;
            }
        }
        return false;
    }
}
