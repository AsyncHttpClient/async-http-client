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
package org.asynchttpclient.netty.request;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.testserver.SocksProxy;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.UPGRADE;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.post;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proxy custom headers are scoped to the proxy, so they belong only on a request the proxy itself reads.
 */
public class ProxyCustomHeaderScopeTest extends AbstractBasicTest {

    private static final String SECRET_HEADER = "X-Proxy-Secret";
    private static final String SECRET_VALUE = "s3cr3t-token";
    private static final String RECEIVED_SECRET = "received-secret";
    private static final String RECEIVED_PORT = "received-port";

    @Override
    public AbstractHandler configureHandler() {
        return new EchoHeaderHandler();
    }

    @Test
    void customHeadersNeverReachTheOriginOverSocks() throws Exception {
        SocksProxy socksProxy = new SocksProxy(60000);
        new Thread(() -> {
            try {
                socksProxy.run();
            } catch (IOException e) {
                logger.error("Failed to establish SocksProxy", e);
            }
        }).start();

        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet("http://localhost:" + port1 + '/')
                    .setProxyServer(proxyServer("localhost", socksProxy.getPort())
                            .setProxyType(ProxyType.SOCKS_V4)
                            .setCustomHeaders(request -> new DefaultHttpHeaders().add(SECRET_HEADER, SECRET_VALUE)))
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertNull(response.getHeader(RECEIVED_SECRET),
                    "a SOCKS proxy tunnels at the transport layer, so this request is read by the origin alone");
        } finally {
            socksProxy.stop();
        }
    }

    @Test
    void anHttpProxyGetsThemOnEveryRequestOfAConnection() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true))) {
            Request request = get("http://localhost:1234/")
                    .setProxyServer(proxyServer("localhost", port1)
                            .setCustomHeaders(req -> new DefaultHttpHeaders().add(SECRET_HEADER, SECRET_VALUE)))
                    .build();

            Response first = client.executeRequest(request).get(TIMEOUT, TimeUnit.SECONDS);
            Response second = client.executeRequest(request).get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(SECRET_VALUE, first.getHeader(RECEIVED_SECRET));
            assertEquals(first.getHeader(RECEIVED_PORT), second.getHeader(RECEIVED_PORT),
                    "the second request has to reuse the connection, or it says nothing about reuse");
            assertEquals(SECRET_VALUE, second.getHeader(RECEIVED_SECRET),
                    "attached once per connection, they are missing from every request after the first");
        }
    }

    @Test
    void theGateFollowsWhoReadsTheRequest() {
        assertTrue(headersOf("http://origin.example/", ProxyType.HTTP, false).contains(SECRET_HEADER),
                "an absolute-form request is delivered to the proxy");
        assertTrue(headersOf("https://origin.example/", ProxyType.HTTP, true).contains(SECRET_HEADER),
                "the CONNECT opens the tunnel and is read by the proxy");
        assertTrue(headersOf("https://origin.example/", ProxyType.HTTPS, true).contains(SECRET_HEADER),
                "an HTTPS proxy reads its CONNECT the same way");
        assertFalse(headersOf("https://origin.example/", ProxyType.HTTP, false).contains(SECRET_HEADER),
                "the tunneled request goes through to the origin");
        assertFalse(headersOf("ws://origin.example/", ProxyType.HTTP, false).contains(SECRET_HEADER),
                "ws:// is tunneled through CONNECT the same way wss:// is");

        for (ProxyType socks : new ProxyType[]{ProxyType.SOCKS_V4, ProxyType.SOCKS_V5}) {
            assertFalse(headersOf("http://origin.example/", socks, false).contains(SECRET_HEADER),
                    socks + " never reads the HTTP request it carries");
            assertFalse(headersOf("https://origin.example/", socks, false).contains(SECRET_HEADER),
                    socks + " never reads the HTTP request it carries");
        }
    }

    /**
     * Callers do put {@code Proxy-Authorization} in the custom set, and a realm still decides it.
     */
    @Test
    void aRealmStillDecidesProxyAuthorization() {
        Realm proxyRealm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.BASIC)
                .setUsePreemptiveAuth(true)
                .build();
        HttpHeaders headers = headersOf("http://origin.example/", ProxyType.HTTP, false, proxyRealm,
                new DefaultHttpHeaders().add(PROXY_AUTHORIZATION, "Bearer caller-token"));

        assertEquals(1, headers.getAll(PROXY_AUTHORIZATION).size());
        assertTrue(headers.get(PROXY_AUTHORIZATION).startsWith("Basic "),
                "the generated header must survive a caller-supplied one");
    }

    @Test
    void aCustomHostReplacesTheGeneratedOneInsteadOfDoublingIt() {
        HttpHeaders headers = headersOf("http://origin.example/", ProxyType.HTTP, false, null,
                new DefaultHttpHeaders().add(HOST, "proxy-vhost.example"));

        assertEquals(Collections.singletonList("proxy-vhost.example"), headers.getAll(HOST),
                "a second Host line is a 400 from a conforming recipient");
    }

    @Test
    void oneCustomNameKeepsAllItsValues() {
        HttpHeaders headers = headersOf("http://origin.example/", ProxyType.HTTP, false, null,
                new DefaultHttpHeaders().add(SECRET_HEADER, "one").add(SECRET_HEADER, "two"));

        assertEquals(Arrays.asList("one", "two"), headers.getAll(SECRET_HEADER));
    }

    /**
     * A CONNECT carries no content, and a Transfer-Encoding left on it makes Netty write a chunk terminator
     * into the tunnel.
     */
    @Test
    void theFieldsAhcHasToOwnAreDropped() {
        HttpHeaders headers = headersOf("https://origin.example/", ProxyType.HTTP, true, null,
                new DefaultHttpHeaders().add(CONTENT_LENGTH, "56")
                        .add(TRANSFER_ENCODING, "chunked")
                        .add(UPGRADE, "websocket")
                        .add(SECRET_HEADER, SECRET_VALUE));

        assertFalse(headers.contains(CONTENT_LENGTH));
        assertFalse(headers.contains(TRANSFER_ENCODING));
        assertFalse(headers.contains(UPGRADE));
        assertTrue(headers.contains(SECRET_HEADER), "the rest of the set still travels");
    }

    @Test
    void aCustomConnectionTokenJoinsAhcsOwnRatherThanDeletingIt() {
        HttpHeaders headers = headersOf(get("http://origin.example/").build(), ProxyType.HTTP, false, null,
                new DefaultHttpHeaders().add(CONNECTION, SECRET_HEADER), config().setKeepAlive(false).build());

        assertTrue(headers.getAll(CONNECTION).contains(SECRET_HEADER),
                "naming a header here is how a caller keeps it off the origin");
        assertTrue(headers.getAll(CONNECTION).stream().anyMatch("close"::equalsIgnoreCase),
                "and keepAlive=false still asked for this connection to close");
    }

    @Test
    void aProxyAuthorizationSetOnTheRequestIsNotDoubledByTheCustomSet() {
        Request request = get("http://origin.example/")
                .setHeader(PROXY_AUTHORIZATION, "Bearer from-request")
                .build();
        HttpHeaders headers = headersOf(request, ProxyType.HTTP, false, null,
                new DefaultHttpHeaders().add(PROXY_AUTHORIZATION, "Bearer from-custom-headers"));

        assertEquals(Collections.singletonList("Bearer from-custom-headers"), headers.getAll(PROXY_AUTHORIZATION),
                "Proxy-Authorization takes a single credential, so two field lines are malformed");
    }

    /**
     * The body is retained into the request before the headers are built, and nothing owns that request until
     * the factory returns it.
     */
    @Test
    void aThrowingCustomHeadersFunctionDoesNotStrandTheBody() {
        ByteBuf body = Unpooled.buffer().writeBytes("payload".getBytes(StandardCharsets.UTF_8));
        Request request = post("http://origin.example/").setBody(body).build();

        try {
            assertThrows(IllegalStateException.class,
                    () -> throwingFactory().newNettyRequest(request, false, throwingProxy(), null, null));
            assertEquals(1, body.refCnt(), "the retained duplicate has to go back with the failed build");
        } finally {
            body.release();
        }
    }

    /**
     * The drop and the replace fold case the way Netty's header store does, or a name in another case would
     * sit beside the generated one instead of being caught.
     */
    @Test
    void aNameInAnotherCaseIsMatchedJustTheSame() {
        Request request = post("http://origin.example/").setBody("payload").build();
        HttpHeaders headers = headersOf(request, ProxyType.HTTP, false, null,
                new DefaultHttpHeaders().add("CONTENT-LENGTH", "0").add("HOST", "proxy-vhost.example"));

        assertEquals(Collections.singletonList("7"), headers.getAll(CONTENT_LENGTH),
                "the message keeps the length of the body it carries");
        assertEquals(Collections.singletonList("proxy-vhost.example"), headers.getAll(HOST));
    }

    /**
     * A generated body holds what AHC opened for it, and only AHC can close it.
     */
    @Test
    void aThrowingCustomHeadersFunctionClosesAGeneratedBody() {
        AtomicBoolean closed = new AtomicBoolean();
        Request request = post("http://origin.example/")
                .setBody(() -> new Body() {
                    @Override
                    public long getContentLength() {
                        return 0;
                    }

                    @Override
                    public BodyState transferTo(ByteBuf target) {
                        return BodyState.STOP;
                    }

                    @Override
                    public void close() {
                        closed.set(true);
                    }
                })
                .build();

        assertThrows(IllegalStateException.class,
                () -> throwingFactory().newNettyRequest(request, false, throwingProxy(), null, null));
        assertTrue(closed.get(), "nothing else will ever see this body");
    }

    private static ProxyServer throwingProxy() {
        return proxyServer("proxy.example", 8080)
                .setCustomHeaders(req -> {
                    throw new IllegalStateException("token service down");
                })
                .build();
    }

    private static NettyRequestFactory throwingFactory() {
        return new NettyRequestFactory(config().build());
    }

    private static HttpHeaders headersOf(String url, ProxyType proxyType, boolean connect) {
        return headersOf(url, proxyType, connect, null, new DefaultHttpHeaders().add(SECRET_HEADER, SECRET_VALUE));
    }

    private static HttpHeaders headersOf(String url, ProxyType proxyType, boolean connect, Realm proxyRealm,
                                         HttpHeaders customHeaders) {
        return headersOf(get(url).build(), proxyType, connect, proxyRealm, customHeaders);
    }

    private static HttpHeaders headersOf(Request request, ProxyType proxyType, boolean connect, Realm proxyRealm,
                                         HttpHeaders customHeaders) {
        return headersOf(request, proxyType, connect, proxyRealm, customHeaders, config().build());
    }

    private static HttpHeaders headersOf(Request request, ProxyType proxyType, boolean connect, Realm proxyRealm,
                                         HttpHeaders customHeaders, AsyncHttpClientConfig config) {
        ProxyServer proxy = proxyServer("proxy.example", 8080)
                .setProxyType(proxyType)
                .setCustomHeaders(req -> customHeaders)
                .build();
        NettyRequestFactory factory = new NettyRequestFactory(config);
        return factory.newNettyRequest(request, connect, proxy, null, proxyRealm)
                .getHttpRequest().headers();
    }

    private static final class EchoHeaderHandler extends AbstractHandler {

        @Override
        public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {
            String secret = String.join(",", Collections.list(request.getHeaders(SECRET_HEADER)));
            if (!secret.isEmpty()) {
                response.addHeader(RECEIVED_SECRET, secret);
            }
            response.addHeader(RECEIVED_PORT, String.valueOf(request.getRemotePort()));
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
        }
    }
}
