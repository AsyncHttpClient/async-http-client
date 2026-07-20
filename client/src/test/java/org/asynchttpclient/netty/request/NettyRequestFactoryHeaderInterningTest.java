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

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link NettyRequestFactory#copyInternedHeaders(HttpHeaders, HttpHeaders)}: standard header names
 * supplied in known lowercase or Train-Case spelling must be interned to a shared {@link AsciiString} with
 * the same bytes (so the encoder bulk-copies), while odd casing and custom names must be emitted verbatim so
 * on-wire bytes are never altered. Order, values and multi-value headers must be preserved.
 */
public class NettyRequestFactoryHeaderInterningTest {

    @Test
    public void internsCanonicalLowercaseKnownNameToAsciiStringConstant() {
        HttpHeaders source = new DefaultHttpHeaders();
        source.add("content-type", "application/json");

        HttpHeaders target = new DefaultHttpHeaders();
        NettyRequestFactory.copyInternedHeaders(source, target);

        CharSequence name = firstNameCharSequence(target);
        assertSame(HttpHeaderNames.CONTENT_TYPE, name,
                "a canonical-lowercase known name must become the shared AsciiString constant");
        assertEquals("application/json", target.get("content-type"));
    }

    @Test
    public void internsTrainCaseKnownNameToAsciiStringPreservingCasing() {
        HttpHeaders source = new DefaultHttpHeaders();
        source.add("Content-Type", "application/json");

        HttpHeaders target = new DefaultHttpHeaders();
        NettyRequestFactory.copyInternedHeaders(source, target);

        CharSequence name = firstNameCharSequence(target);
        assertEquals("Content-Type", name.toString(), "wire casing must be preserved exactly");
        assertTrue(name instanceof AsciiString, "known Train-Case name must be interned for fast encoding");
    }

    @Test
    public void leavesOddCasingUntouched() {
        HttpHeaders source = new DefaultHttpHeaders();
        source.add("CONTENT-TYPE", "application/json");

        HttpHeaders target = new DefaultHttpHeaders();
        NettyRequestFactory.copyInternedHeaders(source, target);

        CharSequence name = firstNameCharSequence(target);
        assertEquals("CONTENT-TYPE", name.toString(), "wire casing must be preserved exactly");
        assertTrue(name instanceof String, "unknown casing must remain the original String, not be interned");
    }

    @Test
    public void leavesCustomNameUntouched() {
        HttpHeaders source = new DefaultHttpHeaders();
        source.add("X-Custom-Header", "v1");

        HttpHeaders target = new DefaultHttpHeaders();
        NettyRequestFactory.copyInternedHeaders(source, target);

        CharSequence name = firstNameCharSequence(target);
        assertEquals("X-Custom-Header", name.toString());
        assertTrue(name instanceof String, "a custom name must pass through unchanged");
    }

    @Test
    public void preservesValuesOrderAndMultiValueHeaders() {
        HttpHeaders source = new DefaultHttpHeaders();
        source.add("host", "www.example.com");
        source.add("X-Multi", "a");
        source.add("X-Multi", "b");
        source.add("accept", "*/*");

        HttpHeaders target = new DefaultHttpHeaders();
        NettyRequestFactory.copyInternedHeaders(source, target);

        // Same name->values content as a plain copy.
        HttpHeaders plain = new DefaultHttpHeaders();
        plain.set(source);
        assertEquals(plain.get("host"), target.get("host"));
        assertEquals(plain.getAll("X-Multi"), target.getAll("X-Multi"));
        assertEquals(plain.get("accept"), target.get("accept"));
        assertEquals(List.of("a", "b"), target.getAll("X-Multi"), "multi-value order preserved");

        // Iteration order preserved (host, X-Multi, X-Multi, accept).
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> e : target) {
            names.add(e.getKey());
        }
        assertEquals(List.of("host", "X-Multi", "X-Multi", "accept"), names);

        // The two known names are interned, the custom one is not.
        boolean hostInterned = false;
        boolean acceptInterned = false;
        java.util.Iterator<Map.Entry<CharSequence, CharSequence>> it = target.iteratorCharSequence();
        while (it.hasNext()) {
            Map.Entry<CharSequence, CharSequence> e = it.next();
            if (e.getKey() == HttpHeaderNames.HOST) {
                hostInterned = true;
            }
            if (e.getKey() == HttpHeaderNames.ACCEPT) {
                acceptInterned = true;
            }
        }
        assertTrue(hostInterned, "host should be interned to the AsciiString constant");
        assertTrue(acceptInterned, "accept should be interned to the AsciiString constant");
    }

    private static CharSequence firstNameCharSequence(HttpHeaders headers) {
        return headers.iteratorCharSequence().next().getKey();
    }
}
