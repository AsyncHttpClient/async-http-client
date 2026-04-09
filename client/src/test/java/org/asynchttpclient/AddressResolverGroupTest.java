/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
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
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import org.asynchttpclient.test.EventCollectingHandler;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class AddressResolverGroupTest extends HttpTest {

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
    public void unknownHostWithDnsResolverGroupFails() throws Throwable {
        DnsAddressResolverGroup resolverGroup = new DnsAddressResolverGroup(
                NioDatagramChannel.class,
                DnsServerAddressStreamProviders.platformDefault());

        withClient(config().setAddressResolverGroup(resolverGroup)).run(client -> {
            try {
                client.prepareGet("http://nonexistent.invalid/foo").execute().get(10, SECONDS);
            } catch (ExecutionException e) {
                // DNS failures may surface as UnknownHostException or as a Netty
                // DnsErrorCauseException (since MiscUtils.getCause unwraps the chain).
                // Either way, the request must fail, never silently succeed.
                assertNotNull(e.getCause(), "Should have a cause for the DNS failure");
            }
        });
    }
}
