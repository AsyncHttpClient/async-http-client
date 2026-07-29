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

import io.netty.handler.codec.http.HttpHeaderNames;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ws:// request through an HTTP proxy is tunnelled with CONNECT, so its upgrade request travels through the
 * established tunnel to the origin. It must therefore be treated like a wss:// tunnel: the proxy credentials
 * belong only on the CONNECT (which the proxy actually receives), not on the tunnelled upgrade request where
 * they would be exposed to the origin, and the request target must be origin-form.
 */
public class WebSocketProxyAuthorizationTest {

    private static NettyRequestFactory factory() {
        return new NettyRequestFactory(config().build());
    }

    @Test
    public void connectRequestForWsCarriesProxyAuthorization() {
        Request request = get("ws://origin.example.com/socket").build();
        Realm proxyRealm = basicAuthRealm("puser", "psecret").setUsePreemptiveAuth(true).build();
        ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

        NettyRequest connect = factory().newNettyRequest(request, true, proxy, null, proxyRealm);

        assertTrue(connect.getHttpRequest().headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION),
                "the CONNECT request must authenticate to the proxy");
    }

    @Test
    public void tunneledWsUpgradeDoesNotCarryProxyAuthorization() {
        Request request = get("ws://origin.example.com/socket").build();
        Realm proxyRealm = basicAuthRealm("puser", "psecret").setUsePreemptiveAuth(true).build();
        ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

        NettyRequest tunneled = factory().newNettyRequest(request, false, proxy, null, proxyRealm);

        assertFalse(tunneled.getHttpRequest().headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION),
                "the tunnelled ws:// upgrade reaches the origin, so it must not expose the proxy credentials");
        assertEquals("/socket", tunneled.getHttpRequest().uri(),
                "the tunnelled ws:// upgrade must use an origin-form request target");
    }

    @Test
    public void plainHttpRequestToProxyKeepsProxyAuthorization() {
        Request request = get("http://origin.example.com/resource").build();
        Realm proxyRealm = basicAuthRealm("puser", "psecret").setUsePreemptiveAuth(true).build();
        ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

        NettyRequest direct = factory().newNettyRequest(request, false, proxy, null, proxyRealm);

        assertTrue(direct.getHttpRequest().headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION),
                "a plaintext http:// request sent directly to the proxy still authenticates to it");
    }
}
