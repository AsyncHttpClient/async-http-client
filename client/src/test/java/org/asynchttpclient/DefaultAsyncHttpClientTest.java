/*
 *    Copyright (c) 2023 AsyncHttpClient Project. All rights reserved.
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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.util.Timer;
import org.asynchttpclient.cookie.CookieEvictionTask;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.exception.FilterException;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_16;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.test.TestUtils.createSslEngineFactory;
import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class DefaultAsyncHttpClientTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    public void testNativeTransportWithEpollOnly() throws Exception {
        AsyncHttpClientConfig config = config().setUseNativeTransport(true).setUseOnlyEpollNativeTransport(true)
                .setSslEngineFactory(createSslEngineFactory()).build();
        assertRequestSucceedsAndEventLoopGroupIs(config, EpollEventLoopGroup.class);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    public void testNativeTransportWithoutEpollOnly() throws Exception {
        AsyncHttpClientConfig config = config().setUseNativeTransport(true).setUseOnlyEpollNativeTransport(false)
                .setSslEngineFactory(createSslEngineFactory()).build();
        assertRequestSucceedsAndEventLoopGroupIs(config, MultiThreadIoEventLoopGroup.class);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    public void testNativeTransportKQueueOnMacOs() throws Exception {
        AsyncHttpClientConfig config = config().setUseNativeTransport(true)
                .setSslEngineFactory(createSslEngineFactory()).build();
        assertRequestSucceedsAndEventLoopGroupIs(config, KQueueEventLoopGroup.class);
    }

    @Test
    public void testExternalNioEventLoopGroup() throws Exception {
        assertRequestSucceedsWith(new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()));
    }

    @Test
    public void testExternalDeprecatedNioEventLoopGroup() throws Exception {
        assertRequestSucceedsWith(new NioEventLoopGroup(1));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    public void testExternalEpollEventLoopGroup() throws Exception {
        assumeTrue(Epoll.isAvailable(), "epoll is not available");
        assertRequestSucceedsWith(new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    public void testExternalIoUringEventLoopGroup() throws Exception {
        assumeTrue(IoUring.isAvailable(), "io_uring is not available");
        assertRequestSucceedsWith(new MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory()));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    public void testExternalKQueueEventLoopGroup() throws Exception {
        assumeTrue(KQueue.isAvailable(), "kqueue is not available");
        assertRequestSucceedsWith(new MultiThreadIoEventLoopGroup(1, KQueueIoHandler.newFactory()));
    }

    @Test
    public void testExternalEventLoopGroupOfUnknownTransportIsRejected() throws Exception {
        EventLoopGroup eventLoopGroup = new DefaultEventLoopGroup(1);
        try {
            AsyncHttpClientConfig config = config().setEventLoopGroup(eventLoopGroup).build();
            assertThrows(IllegalArgumentException.class, () -> asyncHttpClient(config));
        } finally {
            eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).await();
        }
    }

    private static void assertRequestSucceedsWith(EventLoopGroup eventLoopGroup) throws Exception {
        try (HttpServer server = new HttpServer()) {
            server.start();
            server.enqueueOk();
            AsyncHttpClientConfig config = config().setEventLoopGroup(eventLoopGroup).build();
            try (DefaultAsyncHttpClient client = (DefaultAsyncHttpClient) asyncHttpClient(config)) {
                assertDoesNotThrow(() -> client.prepareGet(server.getHttpUrl()).execute().get());
                assertSame(eventLoopGroup, client.channelManager().getEventLoopGroup());
            }
        } finally {
            eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).await();
        }
    }

    private static void assertRequestSucceedsAndEventLoopGroupIs(AsyncHttpClientConfig config, Class<?> expectedEventLoopGroupType) throws Exception {
        try (HttpServer server = new HttpServer();
             DefaultAsyncHttpClient client = (DefaultAsyncHttpClient) asyncHttpClient(config)) {
            server.start();
            server.enqueueOk();
            assertDoesNotThrow(() -> client.prepareGet(server.getHttpsUrl()).execute().get());
            assertInstanceOf(expectedEventLoopGroupType, client.channelManager().getEventLoopGroup());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    public void testNativeTransportFallsBackToNioWhenNativeUnavailable() throws IOException {
        // Requesting native transport must never fail client construction: when no native transport is
        // available (Windows, a minimal image without the native libs, or native libs forced off via
        // -Dio.netty.transport.noNative=true) the selector degrades gracefully to NIO instead of throwing.
        // This assertion is meaningful in the force-native-disabled soak run; on a host where native IS
        // available it documents that native is still selected (no regression).
        AsyncHttpClientConfig config = config().setUseNativeTransport(true).build();
        try (DefaultAsyncHttpClient client = (DefaultAsyncHttpClient) asyncHttpClient(config)) {
            EventLoopGroup group = client.channelManager().getEventLoopGroup();
            boolean nativeAvailable = Epoll.isAvailable() || IoUring.isAvailable();
            if (nativeAvailable) {
                assertFalse(group instanceof NioEventLoopGroup, "native transport available -> expected a native event loop group");
            } else {
                assertInstanceOf(NioEventLoopGroup.class, group, "no native transport available -> expected graceful NIO fallback");
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    public void testAutoSelectsNativeTransportByDefaultWhenAvailable() throws IOException {
        AsyncHttpClientConfig config = config().build();
        try (DefaultAsyncHttpClient client = (DefaultAsyncHttpClient) asyncHttpClient(config)) {
            EventLoopGroup group = client.channelManager().getEventLoopGroup();
            boolean nativeAvailable = Epoll.isAvailable() || IoUring.isAvailable();
            if (nativeAvailable) {
                assertFalse(group instanceof NioEventLoopGroup, "default config must auto-select native transport when available");
            } else {
                assertInstanceOf(NioEventLoopGroup.class, group, "no native transport available -> NIO");
            }
        }
    }

    @Test
    public void testUseOnlyEpollNativeTransportButNativeTransportIsDisabled() {
        assertThrows(IllegalArgumentException.class, () -> config().setUseNativeTransport(false).setUseOnlyEpollNativeTransport(true).build());
    }

    @Test
    public void testUseOnlyEpollNativeTransportAndNativeTransportIsEnabled() {
        assertDoesNotThrow(() -> config().setUseNativeTransport(true).setUseOnlyEpollNativeTransport(true).build());
    }

    @Test
    public void testWithSharedNettyTimerShouldScheduleCookieEvictionOnlyOnce() throws IOException {
        Timer nettyTimerMock = mock(Timer.class);
        CookieStore cookieStore = new ThreadSafeCookieStore();
        AsyncHttpClientConfig config = config().setNettyTimer(nettyTimerMock).setCookieStore(cookieStore).build();

        try (AsyncHttpClient client1 = asyncHttpClient(config)) {
            try (AsyncHttpClient client2 = asyncHttpClient(config)) {
                assertEquals(cookieStore.count(), 2);
                verify(nettyTimerMock, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
            }
        }
    }

    @Test
    public void testWitDefaultConfigShouldScheduleCookieEvictionForEachAHC() throws IOException {
        AsyncHttpClientConfig config1 = config().build();
        try (AsyncHttpClient client1 = asyncHttpClient(config1)) {
            AsyncHttpClientConfig config2 = config().build();
            try (AsyncHttpClient client2 = asyncHttpClient(config2)) {
                assertEquals(config1.getCookieStore().count(), 1);
                assertEquals(config2.getCookieStore().count(), 1);
            }
        }
    }

    @Test
    public void testWithSharedCookieStoreButNonSharedTimerShouldScheduleCookieEvictionForFirstAHC() throws IOException {
        CookieStore cookieStore = new ThreadSafeCookieStore();
        Timer nettyTimerMock1 = mock(Timer.class);
        AsyncHttpClientConfig config1 = config()
                .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock1).build();

        try (AsyncHttpClient client1 = asyncHttpClient(config1)) {
            Timer nettyTimerMock2 = mock(Timer.class);
            AsyncHttpClientConfig config2 = config()
                    .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock2).build();
            try (AsyncHttpClient client2 = asyncHttpClient(config2)) {
                assertEquals(config1.getCookieStore().count(), 2);
                verify(nettyTimerMock1, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
                verify(nettyTimerMock2, never()).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
            }
        }

        Timer nettyTimerMock3 = mock(Timer.class);
        AsyncHttpClientConfig config3 = config()
                .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock3).build();

        try (AsyncHttpClient client2 = asyncHttpClient(config3)) {
            assertEquals(config1.getCookieStore().count(), 1);
            verify(nettyTimerMock3, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
        }
    }

    @Test
    public void testWithSharedCookieStoreButNonSharedTimerShouldReScheduleCookieEvictionWhenFirstInstanceGetClosed() throws IOException {
        CookieStore cookieStore = new ThreadSafeCookieStore();
        Timer nettyTimerMock1 = mock(Timer.class);
        AsyncHttpClientConfig config1 = config()
                .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock1).build();

        try (AsyncHttpClient client1 = asyncHttpClient(config1)) {
            assertEquals(config1.getCookieStore().count(), 1);
            verify(nettyTimerMock1, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
        }

        assertEquals(config1.getCookieStore().count(), 0);

        Timer nettyTimerMock2 = mock(Timer.class);
        AsyncHttpClientConfig config2 = config()
                .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock2).build();

        try (AsyncHttpClient client2 = asyncHttpClient(config2)) {
            assertEquals(config1.getCookieStore().count(), 1);
            verify(nettyTimerMock2, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
        }
    }

    @Test
    public void testDisablingCookieStore() throws IOException {
        AsyncHttpClientConfig config = config()
                .setCookieStore(null).build();
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            //
        }
    }

    @Test
    public void testStoredCookiesKeepRawUrlAsBuilt() throws IOException {
        String url = "http://localhost/a|b?q={x}";
        Request request = new RequestBuilder(GET, true).setUrl(url).build();
        assertEquals(url, request.getUrl());
        assertNotEquals(url, new RequestBuilder(GET).setUrl(url).build().getUrl(), "the URL must be one encoding would change");

        Request sent = sendWithStoredCookies(request, cookie("session", "s"));

        assertEquals(url, sent.getUrl());
        assertEquals(List.of("session=s"), cookieStrings(sent));
    }

    @Test
    public void testStoredCookiesDoNotReplaceRequestCookies() throws IOException {
        Request request = get("http://localhost/").addCookie(cookie("a", "request")).build();

        Request sent = sendWithStoredCookies(request, cookie("a", "store"), cookie("b", "store"));

        assertEquals(List.of("a=request", "b=store"), cookieStrings(sent));
        assertEquals(List.of("a=request"), cookieStrings(request));
    }

    @Test
    public void testStoredCookiesAlreadySetLeaveRequestAsIs() throws IOException {
        Request request = get("http://localhost/").addCookie(cookie("a", "request")).build();

        Request sent = sendWithStoredCookies(request, cookie("a", "store"));

        assertSame(request, sent);
    }

    @Test
    public void testStoredCookiesKeepFirstStoredCookieOfAName() throws IOException {
        Request request = get("http://localhost/x/y").build();
        DefaultCookie longerPath = new DefaultCookie("a", "longer-path");
        longerPath.setPath("/x");

        Request sent = sendWithStoredCookies(request, cookie("a", "root-path"), longerPath);

        assertEquals(List.of("a=longer-path"), cookieStrings(sent));
    }

    @Test
    public void testStoredCookiesMatchNamesCaseSensitively() throws IOException {
        Request request = get("http://localhost/").addCookie(cookie("SID", "request")).build();

        Request sent = sendWithStoredCookies(request, cookie("sid", "store"));

        assertEquals(List.of("SID=request", "sid=store"), cookieStrings(sent));
    }

    @Test
    public void testStoredCookiesKeepOtherRequestFields() throws Exception {
        // Across the three requests each Boolean flag takes both values and each pair of flags differs in one of them.
        ByteBuf byteBuf = Unpooled.wrappedBuffer(new byte[]{4});
        try {
            for (DefaultRequest request : List.of(
                    requestWithEveryField(byteBuf, true, false, false, true),
                    requestWithEveryField(byteBuf, false, true, false, true),
                    requestWithEveryField(byteBuf, false, false, true, false))) {
                Request sent = sendWithStoredCookies(request, cookie("session", "s"));

                assertNotSame(request, sent);
                assertEquals(List.of("a=request", "session=s"), cookieStrings(sent));
                for (Field field : DefaultRequest.class.getDeclaredFields()) {
                    String name = field.getName();
                    // queryParams is computed from the URI on first use.
                    if (Modifier.isStatic(field.getModifiers()) || "cookies".equals(name) || "queryParams".equals(name)) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(request);
                    assertNotNull(value, name);
                    if (field.getType().isPrimitive()) {
                        assertEquals(value, field.get(sent), name);
                    } else {
                        assertSame(value, field.get(sent), name);
                    }
                }
            }
        } finally {
            byteBuf.release();
        }
    }

    /** A request with every field set, each field other than the Boolean flags to a value no other field holds. */
    private static DefaultRequest requestWithEveryField(ByteBuf byteBuf, Boolean followRedirect, Boolean useAbsoluteRequestDeadline,
                                                        Boolean refuseSchemeDowngradeOnRedirect,
                                                        Boolean refuseCrossOriginBodyOnRedirect) throws IOException {
        Request defaults = get("http://localhost/").build();
        return new DefaultRequest("POST", Uri.create("http://localhost/path?x=1"),
                InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1}), InetAddress.getByAddress(new byte[]{127, 0, 0, 2}),
                new DefaultHttpHeaders().add("X-Custom", "v"), List.of(cookie("a", "request")), new byte[]{1},
                List.of(new byte[]{2}), "body", ByteBuffer.wrap(new byte[]{3}), byteBuf, new ByteArrayInputStream(new byte[]{5}),
                new ByteArrayBodyGenerator(new byte[]{6}), List.of(new Param("p", "v")), List.of(new StringPart("part", "v")),
                "vhost", proxyServer("proxy", 8080).build(), basicAuthRealm("user", "pass").build(), new File("body.txt"),
                followRedirect, Duration.ofSeconds(3), Duration.ofSeconds(2), 7, UTF_16, defaults.getChannelPoolPartitioning(),
                defaults.getNameResolver(), useAbsoluteRequestDeadline, refuseSchemeDowngradeOnRedirect,
                refuseCrossOriginBodyOnRedirect);
    }

    @Test
    public void testStoredCookiesGoThroughToBuilderForRequestSubclass() throws IOException {
        Request built = get("http://localhost/").addCookie(cookie("a", "request")).build();
        AtomicInteger toBuilderCalls = new AtomicInteger();
        Request request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(), built.getLocalAddress(),
                built.getHeaders(), built.getCookies(), built.getByteData(), built.getCompositeByteData(), built.getStringData(),
                built.getByteBufferData(), built.getByteBufData(), built.getStreamData(), built.getBodyGenerator(),
                built.getFormParams(), built.getBodyParts(), built.getVirtualHost(), built.getProxyServer(), built.getRealm(),
                built.getFile(), built.getFollowRedirect(), built.getRequestTimeout(), built.getReadTimeout(),
                built.getRangeOffset(), built.getCharset(), built.getChannelPoolPartitioning(), built.getNameResolver()) {
            @Override
            public RequestBuilder toBuilder() {
                toBuilderCalls.incrementAndGet();
                return super.toBuilder();
            }
        };

        Request sent = sendWithStoredCookies(request, cookie("a", "store"), cookie("b", "store"));

        assertEquals(1, toBuilderCalls.get());
        assertEquals(List.of("a=request", "b=store"), cookieStrings(sent));
    }

    @Test
    public void testStoredCookiesAddedToRequestWithNullCookieList() throws IOException {
        Request built = get("http://localhost/").build();
        Request request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(), built.getLocalAddress(),
                built.getHeaders(), null, built.getByteData(), built.getCompositeByteData(), built.getStringData(),
                built.getByteBufferData(), built.getByteBufData(), built.getStreamData(), built.getBodyGenerator(),
                built.getFormParams(), built.getBodyParts(), built.getVirtualHost(), built.getProxyServer(), built.getRealm(),
                built.getFile(), built.getFollowRedirect(), built.getRequestTimeout(), built.getReadTimeout(),
                built.getRangeOffset(), built.getCharset(), built.getChannelPoolPartitioning(), built.getNameResolver());

        Request sent = sendWithStoredCookies(request, cookie("session", "s"));

        assertEquals(List.of("session=s"), cookieStrings(sent));
    }

    /** Executes the request on a client whose store holds these cookies for localhost and returns what reached the filters. */
    private static Request sendWithStoredCookies(Request request, Cookie... storedCookies) throws IOException {
        CookieStore cookieStore = new ThreadSafeCookieStore();
        for (Cookie cookie : storedCookies) {
            cookieStore.add(Uri.create("http://localhost/"), cookie);
        }
        AtomicReference<Request> sent = new AtomicReference<>();
        RequestFilter capture = new RequestFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException {
                sent.set(ctx.getRequest());
                throw new FilterException("captured");
            }
        };
        AsyncHttpClientConfig config = config().setCookieStore(cookieStore).addRequestFilter(capture).build();
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            ListenableFuture<Response> future = client.executeRequest(request);
            assertThrows(ExecutionException.class, future::get);
        }
        assertNotNull(sent.get());
        return sent.get();
    }

    private static Cookie cookie(String name, String value) {
        DefaultCookie cookie = new DefaultCookie(name, value);
        cookie.setPath("/");
        return cookie;
    }

    private static List<String> cookieStrings(Request request) {
        return request.getCookies().stream().map(c -> c.name() + '=' + c.value()).collect(Collectors.toList());
    }
}
