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

import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.uring.IoUringDatagramChannel;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsErrorCauseException;
import io.netty.resolver.dns.SingletonDnsServerAddressStreamProvider;
import org.asynchttpclient.test.EventCollectingHandler;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.asynchttpclient.testserver.StubDnsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

public class AddressResolverGroupTest extends HttpTest {

    private static Class<? extends DatagramChannel> datagramChannelClass;

    private HttpServer server;
    private StubDnsServer dns;

    // The resolver channel has to match whichever transport the client picks.
    @BeforeAll
    public static void probeTransport() {
        try (DefaultAsyncHttpClient probe = new DefaultAsyncHttpClient()) {
            IoEventLoopGroup group = (IoEventLoopGroup) probe.getEventLoopGroup();
            if (group.isIoType(EpollIoHandler.class)) {
                datagramChannelClass = EpollDatagramChannel.class;
            } else if (group.isIoType(KQueueIoHandler.class)) {
                datagramChannelClass = KQueueDatagramChannel.class;
            } else if (group.isIoType(IoUringIoHandler.class)) {
                datagramChannelClass = IoUringDatagramChannel.class;
            } else {
                datagramChannelClass = NioDatagramChannel.class;
            }
        }
    }

    @BeforeEach
    public void start() throws Throwable {
        server = new HttpServer();
        server.start();
        dns = new StubDnsServer();
    }

    @AfterEach
    public void stop() throws Throwable {
        if (dns != null) {
            dns.close();
        }
        server.close();
    }

    private String getTargetUrl() {
        return server.getHttpUrl() + "/foo/bar";
    }

    // A name the hosts file cannot answer, so the resolver has to query the stub DNS server.
    private String getTargetUrl(String host) {
        return "http://" + host + ":" + server.getHttpPort() + "/foo/bar";
    }

    private DnsAddressResolverGroup newDnsResolverGroup() {
        return new DnsAddressResolverGroup(datagramChannelClass, new SingletonDnsServerAddressStreamProvider(dns.getAddress()));
    }

    @Test
    public void requestWithDnsAddressResolverGroupSucceeds() throws Throwable {
        DnsAddressResolverGroup resolverGroup = newDnsResolverGroup();

        withClient(config().setAddressResolverGroup(resolverGroup)).run(client ->
                withServer(server).run(server -> {
                    server.enqueueOk();
                    Response response = client.prepareGet(getTargetUrl()).execute().get(3, SECONDS);
                    assertEquals(200, response.getStatusCode());
                }));
    }

    @Test
    public void dnsResolverGroupFiresHostnameResolutionEvents() throws Throwable {
        DnsAddressResolverGroup resolverGroup = newDnsResolverGroup();

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

    @Test
    public void defaultConfigDoesNotSetAddressResolverGroup() {
        DefaultAsyncHttpClientConfig config = config().build();
        assertNull(config.getAddressResolverGroup(),
                "Default config should not have an AddressResolverGroup");
    }

    @Test
    public void unknownHostWithDnsResolverGroupFails() throws Throwable {
        DnsAddressResolverGroup resolverGroup = newDnsResolverGroup();

        withClient(config().setAddressResolverGroup(resolverGroup)).run(client -> {
            try {
                client.prepareGet(getTargetUrl(StubDnsServer.UNKNOWN_HOST)).execute().get(10, SECONDS);
                fail("Request to nonexistent host should have thrown an exception");
            } catch (ExecutionException e) {
                // The client surfaces the root cause, not Netty's UnknownHostException wrapper.
                DnsErrorCauseException cause = assertInstanceOf(DnsErrorCauseException.class, e.getCause(),
                        "Should fail with a DNS failure");
                assertEquals(DnsResponseCode.NXDOMAIN, cause.getCode());
            }
        });
    }

    @Test
    public void resolveHostWithDnsQuery() throws Throwable {
        DnsAddressResolverGroup resolverGroup = newDnsResolverGroup();

        withClient(config().setAddressResolverGroup(resolverGroup)).run(client ->
                withServer(server).run(server -> {
                    server.enqueueOk();
                    Response response = client.prepareGet(getTargetUrl("first.invalid")).execute().get(10, SECONDS);
                    assertEquals(200, response.getStatusCode());
                }));
    }

    @Test
    public void resolveMultipleHostsWithDnsQueries() throws Throwable {
        DnsAddressResolverGroup resolverGroup = newDnsResolverGroup();

        withClient(config().setAddressResolverGroup(resolverGroup)).run(client ->
                withServer(server).run(server -> {
                    server.enqueueOk();
                    Response response1 = client.prepareGet(getTargetUrl("first.invalid")).execute().get(10, SECONDS);
                    assertEquals(200, response1.getStatusCode());

                    server.enqueueOk();
                    Response response2 = client.prepareGet(getTargetUrl("second.invalid")).execute().get(10, SECONDS);
                    assertEquals(200, response2.getStatusCode());
                }));
    }
}
