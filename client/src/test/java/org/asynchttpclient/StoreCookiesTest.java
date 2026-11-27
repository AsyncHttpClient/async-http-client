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

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StoreCookiesTest {

    private static final String A = "http://a.example/";
    private static final String B = "http://b.example/";

    private static Cookie decode(String setCookie) {
        return ClientCookieDecoder.STRICT.decode(setCookie);
    }

    private static CookieStore store(String url, String... setCookies) {
        CookieStore store = new ThreadSafeCookieStore();
        for (String setCookie : setCookies) {
            store.add(Uri.create(url), decode(setCookie));
        }
        return store;
    }

    private static List<String> pairs(Request request) {
        return request.getCookies().stream().map(c -> c.name() + '=' + c.value()).collect(Collectors.toList());
    }

    @Test
    public void aMarkedCookieReadsComparesAndPrintsAsTheStoresOwn() {
        Cookie stored = decode("SID=v; Domain=example.com; Path=/p; Max-Age=60; Secure; HttpOnly");
        Cookie marked = StoreCookies.mark(stored);

        assertTrue(StoreCookies.isMarked(marked));
        assertFalse(StoreCookies.isMarked(stored));
        assertSame(marked, StoreCookies.mark(marked));
        assertEquals(stored.name(), marked.name());
        assertEquals(stored.value(), marked.value());
        assertEquals(stored.wrap(), marked.wrap());
        assertEquals(stored.domain(), marked.domain());
        assertEquals(stored.path(), marked.path());
        assertEquals(stored.maxAge(), marked.maxAge());
        assertEquals(stored.isSecure(), marked.isSecure());
        assertEquals(stored.isHttpOnly(), marked.isHttpOnly());
        assertEquals(stored, marked);
        assertEquals(marked, stored);
        assertEquals(StoreCookies.mark(stored), marked);
        assertEquals(stored.hashCode(), marked.hashCode());
        assertEquals(0, marked.compareTo(stored));
        assertEquals(0, stored.compareTo(marked));
        assertEquals(0, marked.compareTo(StoreCookies.mark(stored)));
        assertEquals(stored.toString(), marked.toString());
    }

    @Test
    public void aMarkedCookieWritesThroughToTheStoresOwn() {
        Cookie stored = decode("SID=v");
        Cookie marked = StoreCookies.mark(stored);

        marked.setValue("w");
        marked.setWrap(true);
        marked.setDomain("example.com");
        marked.setPath("/p");
        marked.setMaxAge(60);
        marked.setSecure(true);
        marked.setHttpOnly(true);

        assertEquals("w", stored.value());
        assertTrue(stored.wrap());
        assertEquals("example.com", stored.domain());
        assertEquals("/p", stored.path());
        assertEquals(60, stored.maxAge());
        assertTrue(stored.isSecure());
        assertTrue(stored.isHttpOnly());
    }

    @Test
    public void aMarkedCookieIsADefaultCookieWithTheStoresSameSiteAndPartitioned() {
        DefaultCookie stored = (DefaultCookie) decode("SID=v; Path=/; Secure; SameSite=Strict; Partitioned");
        DefaultCookie marked = assertInstanceOf(DefaultCookie.class, StoreCookies.mark(stored));

        assertEquals(SameSite.Strict, marked.sameSite());
        assertTrue(marked.isPartitioned());

        marked.setSameSite(SameSite.Lax);
        marked.setPartitioned(false);

        assertEquals(SameSite.Lax, stored.sameSite());
        assertFalse(stored.isPartitioned());
    }

    @Test
    public void aMarkOfACookieOfAnotherTypeHoldsSameSiteAndPartitionedItself() {
        DefaultCookie target = new DefaultCookie("SID", "v");
        AtomicInteger valueWrites = new AtomicInteger();
        Cookie stored = (Cookie) Proxy.newProxyInstance(Cookie.class.getClassLoader(), new Class<?>[]{Cookie.class},
                (proxy, method, args) -> {
                    if ("setValue".equals(method.getName())) {
                        valueWrites.incrementAndGet();
                    }
                    return method.invoke(target, args);
                });
        DefaultCookie marked = assertInstanceOf(DefaultCookie.class, StoreCookies.mark(stored));

        assertEquals("v", marked.value());
        assertEquals(0, valueWrites.get(), "marking wrote the value back");
        assertNull(marked.sameSite());
        assertFalse(marked.isPartitioned());

        marked.setSameSite(SameSite.Lax);
        marked.setPartitioned(true);
        marked.setValue("w");
        marked.setSecure(true);

        assertEquals(SameSite.Lax, marked.sameSite());
        assertTrue(marked.isPartitioned());
        assertNull(target.sameSite());
        assertFalse(target.isPartitioned());
        assertEquals("w", target.value());
        assertEquals(1, valueWrites.get());
        assertTrue(target.isSecure());
    }

    @Test
    public void twoMarksOfACookieThatOnlyEqualsItsOwnTypeAreEqual() {
        Cookie stored = new OwnTypeOnlyCookie("SID", "v");

        assertEquals(StoreCookies.mark(stored), StoreCookies.mark(stored));
        assertEquals(0, StoreCookies.mark(stored).compareTo(StoreCookies.mark(stored)));
    }

    /** As a store of its own may hand out. */
    private static final class OwnTypeOnlyCookie extends DefaultCookie {

        OwnTypeOnlyCookie(String name, String value) {
            super(name, value);
        }

        @Override
        public boolean equals(Object other) {
            return other != null && other.getClass() == OwnTypeOnlyCookie.class && super.equals(other);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public int compareTo(Cookie other) {
            return super.compareTo((OwnTypeOnlyCookie) other);
        }
    }

    @Test
    public void toAnotherOriginOnlyTheStoresCookiesAreReplaced() {
        Request request = new RequestBuilder().setUrl(B + "x")
                .addCookie(StoreCookies.mark(decode("A=1; Path=/")))
                .addCookie(new DefaultCookie("CALLER", "c"))
                .build();

        Request sent = new RequestBuilder().setUrl(A).addCookie(request.getCookies().get(0)).build();

        Request rebound = StoreCookies.rebind(request, sent, store(B, "B=1; Path=/"));

        assertEquals(List.of("CALLER=c", "B=1"), pairs(rebound));
        assertFalse(StoreCookies.isMarked(rebound.getCookies().get(0)));
        assertTrue(StoreCookies.isMarked(rebound.getCookies().get(1)));
        assertEquals(request.getUrl(), rebound.getUrl());
    }

    @Test
    public void toAnotherOriginACookieTheRequestSetOutranksTheStoresOfThatName() {
        Request request = new RequestBuilder().setUrl(B).addCookie(new DefaultCookie("B", "mine")).build();

        assertEquals(List.of("B=mine"), pairs(StoreCookies.rebind(request, null, store(B, "B=1; Path=/"))));
    }

    @Test
    public void toAnotherOriginARequestWithoutCookiesTakesTheStoresForItsUri() {
        Request built = new RequestBuilder().setUrl(B).build();
        // The public constructor lets a request carry null.
        Request request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(),
                built.getLocalAddress(), built.getHeaders(), null, built.getByteData(), built.getCompositeByteData(),
                built.getStringData(), built.getByteBufferData(), built.getByteBufData(), built.getStreamData(),
                built.getBodyGenerator(), built.getFormParams(), built.getBodyParts(), built.getVirtualHost(),
                built.getProxyServer(), built.getRealm(), built.getFile(), built.getFollowRedirect(),
                built.getRequestTimeout(), built.getReadTimeout(), built.getRangeOffset(), built.getCharset(),
                built.getChannelPoolPartitioning(), built.getNameResolver());

        assertEquals(List.of("B=1"), pairs(StoreCookies.rebind(request, null, store(B, "B=1; Path=/"))));
    }

    @Test
    public void aRequestOfAnotherTypeIsRebuiltWithItsOtherFieldsKept() {
        Request built = new RequestBuilder("POST").setUrl(B + "x?q=1").setHeader("X-Custom", "v").setBody("body")
                .addCookie(StoreCookies.mark(decode("A=1; Path=/")))
                .build();
        Request request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(),
                built.getLocalAddress(), built.getHeaders(), built.getCookies(), built.getByteData(),
                built.getCompositeByteData(), built.getStringData(), built.getByteBufferData(), built.getByteBufData(),
                built.getStreamData(), built.getBodyGenerator(), built.getFormParams(), built.getBodyParts(),
                built.getVirtualHost(), built.getProxyServer(), built.getRealm(), built.getFile(),
                built.getFollowRedirect(), built.getRequestTimeout(), built.getReadTimeout(), built.getRangeOffset(),
                built.getCharset(), built.getChannelPoolPartitioning(), built.getNameResolver()) {
        };

        Request rebound = StoreCookies.rebind(request, null, store(B, "B=1; Path=/"));

        assertEquals(List.of("B=1"), pairs(rebound));
        assertEquals("POST", rebound.getMethod());
        assertEquals(B + "x?q=1", rebound.getUrl());
        assertEquals("v", rebound.getHeaders().get("X-Custom"));
        assertEquals("body", rebound.getStringData());
    }

    @Test
    public void nothingChangesWithoutCookiesFromTheStoreOnTheRequestOrInIt() {
        Request request = new RequestBuilder().setUrl(B).addCookie(new DefaultCookie("CALLER", "c")).build();

        assertSame(request, StoreCookies.rebind(request, null, new ThreadSafeCookieStore()));
        assertSame(request, StoreCookies.rebind(request, null, null));
    }

    @Test
    public void withoutAStoreTheStoresCookiesAreOnlyRemoved() {
        Request request = new RequestBuilder().setUrl(B)
                .addCookie(StoreCookies.mark(decode("A=1; Path=/")))
                .addCookie(new DefaultCookie("CALLER", "c"))
                .build();

        assertEquals(List.of("CALLER=c"), pairs(StoreCookies.rebind(request, null, null)));
    }

    @Test
    public void toTheSameOriginTheStoresCookiesTakeTheirCurrentValuesAndThoseItGained() {
        CookieStore store = store(B, "SID=new; Path=/", "GAINED=1; Path=/");
        Request sent = new RequestBuilder().setUrl(B + "first")
                .addCookie(new DefaultCookie("CALLER", "c"))
                .addCookie(StoreCookies.mark(decode("SID=old; Path=/")))
                .addCookie(StoreCookies.mark(decode("GONE=1; Path=/")))
                .build();
        Request replay = sent.toBuilder().setUrl(B + "retry").build();

        Request rebound = StoreCookies.rebind(replay, sent, store);

        // The store's own come after the caller's, in its order.
        assertEquals("CALLER=c", pairs(rebound).get(0));
        assertFalse(StoreCookies.isMarked(rebound.getCookies().get(0)));
        assertEquals(Set.of("SID=new", "GAINED=1"), Set.copyOf(pairs(rebound).subList(1, 3)));
        assertTrue(StoreCookies.isMarked(rebound.getCookies().get(1)));
        assertTrue(StoreCookies.isMarked(rebound.getCookies().get(2)));
        assertEquals(pairs(rebound), pairs(StoreCookies.rebind(sent, sent, store)));
    }

    @Test
    public void toTheSameOriginAStoredCookieTheRequestNoLongerCarriesIsNotAddedBack() {
        CookieStore store = store(B, "SID=1; Path=/", "OTHER=1; Path=/");
        Request sent = new RequestBuilder().setUrl(B)
                .addCookie(StoreCookies.mark(decode("SID=1; Path=/")))
                .addCookie(StoreCookies.mark(decode("OTHER=1; Path=/")))
                .build();
        Request replay = new RequestBuilder().setUrl(B).addCookie(new DefaultCookie("CALLER", "c"))
                .addCookie(sent.getCookies().get(1))
                .build();

        assertEquals(List.of("CALLER=c", "OTHER=1"), pairs(StoreCookies.rebind(replay, sent, store)));
    }

    @Test
    public void toTheSameOriginARemovedCookieIsNotAddedBackUnderAnotherCase() {
        CookieStore upper = store(B, "SID=old; Path=/");
        Request sentUpper = new RequestBuilder().setUrl(B).addCookie(StoreCookies.mark(upper.get(Uri.create(B)).get(0)))
                .build();
        upper.add(Uri.create(B), decode("sid=new; Path=/"));
        CookieStore lower = store(B, "sid=old; Path=/");
        Request sentLower = new RequestBuilder().setUrl(B).addCookie(StoreCookies.mark(lower.get(Uri.create(B)).get(0)))
                .build();
        lower.add(Uri.create(B), decode("SID=new; Path=/"));
        Request replay = new RequestBuilder().setUrl(B).build();

        assertEquals(List.of(), pairs(StoreCookies.rebind(replay, sentUpper, upper)));
        assertEquals(List.of(), pairs(StoreCookies.rebind(replay, sentLower, lower)));
        assertEquals(List.of("sid=new"), pairs(StoreCookies.rebind(sentUpper, sentUpper, upper)));
        assertEquals(List.of("SID=new"), pairs(StoreCookies.rebind(sentLower, sentLower, lower)));
    }

    @Test
    public void toAnotherOriginEveryStoredCookieForItIsAdded() {
        Request sent = new RequestBuilder().setUrl(A).addCookie(StoreCookies.mark(decode("B=1; Path=/"))).build();
        Request request = new RequestBuilder().setUrl(B).build();

        assertEquals(List.of("B=1"), pairs(StoreCookies.rebind(request, sent, store(B, "B=1; Path=/"))));
    }

    @Test
    public void aSchemeChangeOnTheSameHostIsAnotherOrigin() {
        CookieStore store = store("https://b.example/", "S=1; Path=/; Secure", "P=1; Path=/");
        Request request = new RequestBuilder().setUrl(B)
                .addCookie(StoreCookies.mark(decode("S=1; Path=/; Secure")))
                .build();

        Request sent = new RequestBuilder().setUrl("https://b.example/")
                .addCookie(request.getCookies().get(0))
                .addCookie(StoreCookies.mark(decode("P=1; Path=/")))
                .build();

        Request rebound = StoreCookies.rebind(request, sent, store);

        assertNotSame(request, rebound);
        assertEquals(List.of("P=1"), pairs(rebound));
    }
}
