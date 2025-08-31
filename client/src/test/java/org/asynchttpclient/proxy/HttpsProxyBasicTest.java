/*
 *    Copyright (c) 2025 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.proxy;

import io.github.artsok.RepeatedIfExceptionsTest;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.uri.Uri;

import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic tests for HTTPS proxy type functionality without network calls.
 */
public class HttpsProxyBasicTest {

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testHttpsProxyTypeConfiguration() throws Exception {
        // Test that HTTPS proxy type can be configured correctly
        ProxyServer.Builder builder = proxyServer("proxy.example.com", 8080)
            .setSecuredPort(8443)
            .setProxyType(ProxyType.HTTPS);
        
        ProxyServer proxy = builder.build();
        
        assertEquals(ProxyType.HTTPS, proxy.getProxyType());
        assertEquals(true, proxy.getProxyType().isHttp());
        assertEquals(8443, proxy.getSecuredPort());
        assertEquals(8080, proxy.getPort());
        assertEquals("proxy.example.com", proxy.getHost());
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testHttpsProxyTypeDefaultSecuredPort() {
        // Test HTTPS proxy type with default secured port
        ProxyServer proxy = proxyServer("proxy.example.com", 8080)
            .setProxyType(ProxyType.HTTPS)
            .build();
        
        assertEquals(ProxyType.HTTPS, proxy.getProxyType());
        assertEquals(true, proxy.getProxyType().isHttp());
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testChannelPoolPartitioningWithHttpsProxy() {
        // Test that HTTPS proxy creates correct partition keys for connection pooling
        ProxyServer httpsProxy = proxyServer("proxy.example.com", 8080)
            .setSecuredPort(8443)
            .setProxyType(ProxyType.HTTPS)
            .build();
        
        Uri targetUri = Uri.create("https://target.example.com/test");
        ChannelPoolPartitioning partitioning = ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE;
        
        Object partitionKey = partitioning.getPartitionKey(targetUri, null, httpsProxy);
        
        assertNotNull(partitionKey);
        // The partition key should include the secured port for HTTPS proxy with HTTPS target
        assertTrue(partitionKey.toString().contains("8443"));
        assertTrue(partitionKey.toString().contains("HTTPS"));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testChannelPoolPartitioningHttpsProxyHttpTarget() {
        // Test HTTPS proxy with HTTP target - should use normal port
        ProxyServer httpsProxy = proxyServer("proxy.example.com", 8080)
            .setSecuredPort(8443)
            .setProxyType(ProxyType.HTTPS)
            .build();
        
        Uri targetUri = Uri.create("http://target.example.com/test");
        ChannelPoolPartitioning partitioning = ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE;
        
        Object partitionKey = partitioning.getPartitionKey(targetUri, null, httpsProxy);
        
        assertNotNull(partitionKey);
        // For HTTP target, should use normal proxy port
        assertTrue(partitionKey.toString().contains("8080"));
        assertTrue(partitionKey.toString().contains("HTTPS"));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testChannelPoolPartitioningWithHttpProxy() {
        // Test that HTTP proxy creates correct partition keys for connection pooling
        ProxyServer httpProxy = proxyServer("proxy.example.com", 8080)
            .setSecuredPort(8443)
            .setProxyType(ProxyType.HTTP)
            .build();
        
        Uri targetUri = Uri.create("https://target.example.com/test");
        ChannelPoolPartitioning partitioning = ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE;
        
        Object partitionKey = partitioning.getPartitionKey(targetUri, null, httpProxy);
        
        assertNotNull(partitionKey);
        // For HTTP proxy with secured target, should use secured port
        assertTrue(partitionKey.toString().contains("8443"));
        assertTrue(partitionKey.toString().contains("HTTP"));
    }
}
