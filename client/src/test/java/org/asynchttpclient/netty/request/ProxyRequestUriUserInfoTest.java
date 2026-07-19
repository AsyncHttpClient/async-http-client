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

import org.asynchttpclient.Request;
import org.asynchttpclient.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A plaintext request routed through an HTTP proxy carries an absolute-form request target, which the proxy
 * sees and logs in the clear. RFC 9110 §4.2.4 forbids the userinfo subcomponent in a generated request
 * target, so the URL credentials must not appear on that request line.
 */
public class ProxyRequestUriUserInfoTest {

    private static NettyRequestFactory factory() {
        return new NettyRequestFactory(config().build());
    }

    @Test
    public void proxiedRequestTargetDropsUserInfo() {
        Request request = get("http://user:secret@origin.example.com/resource?a=b").build();
        ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

        NettyRequest proxied = factory().newNettyRequest(request, false, proxy, null, null);
        String requestTarget = proxied.getHttpRequest().uri();

        assertFalse(requestTarget.contains("secret"),
                "the absolute-form request target must not expose the URL credentials to the proxy");
        assertEquals("http://origin.example.com/resource?a=b", requestTarget);
    }

    @Test
    public void proxiedRequestTargetWithoutUserInfoIsUnchanged() {
        Request request = get("http://origin.example.com/resource?a=b").build();
        ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

        NettyRequest proxied = factory().newNettyRequest(request, false, proxy, null, null);

        assertEquals("http://origin.example.com/resource?a=b", proxied.getHttpRequest().uri());
    }

    @Test
    public void directRequestTargetStaysRelative() {
        Request request = get("http://user:secret@origin.example.com/resource?a=b").build();

        NettyRequest direct = factory().newNettyRequest(request, false, null, null, null);

        assertEquals("/resource?a=b", direct.getHttpRequest().uri());
    }
}
