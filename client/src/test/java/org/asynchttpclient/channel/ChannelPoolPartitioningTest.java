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
package org.asynchttpclient.channel;

import org.asynchttpclient.channel.ChannelPoolPartitioning.PerHostChannelPoolPartitioning;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class ChannelPoolPartitioningTest {

    private static final ProxyServer HTTP_PROXY = new ProxyServer.Builder("proxy.example", 3128).setSecuredPort(3129).build();

    private static Object key(String url, String virtualHost, ProxyServer proxy) {
        return PerHostChannelPoolPartitioning.INSTANCE.getPartitionKey(Uri.create(url), virtualHost, proxy);
    }

    private static void assertSameKey(Object expected, Object actual) {
        assertEquals(expected, actual);
        assertEquals(actual, expected);
        assertEquals(expected.hashCode(), actual.hashCode(), "equal keys must hash alike");
    }

    @Test
    public void keysFromEquivalentRequestsAreEqual() {
        assertSameKey(key("http://a.example/x", "vhost", null), key("http://a.example:80/y?q", "vhost", null));
        assertSameKey(key("https://a.example/x", null, HTTP_PROXY), key("https://a.example:443/y", null, HTTP_PROXY));
        assertSameKey(key("http://a.example/x", "vhost", HTTP_PROXY),
                key("http://a.example/z", "vhost", new ProxyServer.Builder("proxy.example", 3128).build()));
    }

    @Test
    public void everyComponentSeparatesKeys() {
        Object base = key("http://a.example:8080/", "vhost", HTTP_PROXY);
        assertNotEquals(base, key("https://a.example:8080/", "vhost", HTTP_PROXY), "scheme");
        assertNotEquals(base, key("http://b.example:8080/", "vhost", HTTP_PROXY), "host");
        assertNotEquals(base, key("http://a.example:8081/", "vhost", HTTP_PROXY), "port");
        assertNotEquals(base, key("http://a.example:8080/", "other", HTTP_PROXY), "virtual host");
        assertNotEquals(base, key("http://a.example:8080/", null, HTTP_PROXY), "no virtual host");
        assertNotEquals(base, key("http://a.example:8080/", "vhost", null), "no proxy");
        assertNotEquals(base, key("http://a.example:8080/", "vhost",
                new ProxyServer.Builder("other.example", 3128).build()), "proxy host");
        assertNotEquals(base, key("http://a.example:8080/", "vhost",
                new ProxyServer.Builder("proxy.example", 3130).build()), "proxy port");
        assertNotEquals(base, key("http://a.example:8080/", "vhost",
                new ProxyServer.Builder("proxy.example", 3128).setProxyType(ProxyType.SOCKS_V5).build()), "proxy type");
    }

    @Test
    public void securedTargetThroughAnHttpProxyUsesTheSecuredPort() {
        Object viaSecuredPort = key("https://a.example/", null, HTTP_PROXY);
        Object viaPlainPort = key("https://a.example/", null, new ProxyServer.Builder("proxy.example", 3128).build());
        assertNotEquals(viaSecuredPort, viaPlainPort);
        assertSameKey(viaSecuredPort, key("https://a.example/", null,
                new ProxyServer.Builder("proxy.example", 3000).setSecuredPort(3129).build()));
    }

    @Test
    public void defaultPortIsExplicitInTheKey() {
        assertSameKey(key("http://a.example/", "vhost", null), key("http://a.example:80/", "vhost", null));
        assertNotEquals(key("http://a.example/", "vhost", null), key("http://a.example:443/", "vhost", null));
    }

    @Test
    public void toStringStillShowsTheTargetBaseUrl() {
        assertEquals("CompositePartitionKey(targetHostBaseUrl=https://a.example:443, virtualHost=vhost, "
                        + "proxyHost=proxy.example, proxyPort=3129, proxyType=HTTP)",
                key("https://a.example/path", "vhost", HTTP_PROXY).toString());
    }
}
