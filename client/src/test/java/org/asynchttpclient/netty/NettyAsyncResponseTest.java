/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class NettyAsyncResponseTest {

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testCookieParseExpires() {
        // e.g. "Tue, 27 Oct 2015 12:54:24 GMT";
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

        Date date = new Date(System.currentTimeMillis() + 60000);
        final String cookieDef = String.format("efmembercheck=true; expires=%s; path=/; domain=.eclipse.org", sdf.format(date));

        HttpHeaders responseHeaders = new DefaultHttpHeaders().add(SET_COOKIE, cookieDef);
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), responseHeaders, null);

        List<Cookie> cookies = response.getCookies();
        assertEquals(1, cookies.size());

        Cookie cookie = cookies.get(0);
        assertTrue(cookie.maxAge() >= 58 && cookie.maxAge() <= 60);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testCookieParseMaxAge() {
        final String cookieDef = "efmembercheck=true; max-age=60; path=/; domain=.eclipse.org";

        HttpHeaders responseHeaders = new DefaultHttpHeaders().add(SET_COOKIE, cookieDef);
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), responseHeaders, null);
        List<Cookie> cookies = response.getCookies();
        assertEquals(1, cookies.size());

        Cookie cookie = cookies.get(0);
        assertEquals(60, cookie.maxAge());
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testCookieParseWeirdExpiresValue() {
        final String cookieDef = "efmembercheck=true; expires=60; path=/; domain=.eclipse.org";
        HttpHeaders responseHeaders = new DefaultHttpHeaders().add(SET_COOKIE, cookieDef);
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), responseHeaders, null);

        List<Cookie> cookies = response.getCookies();
        assertEquals(1, cookies.size());

        Cookie cookie = cookies.get(0);
        assertEquals(Long.MIN_VALUE, cookie.maxAge());
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGetResponseBodyAsByteBuffer() {
        List<HttpResponseBodyPart> bodyParts = new LinkedList<>();
        bodyParts.add(new LazyResponseBodyPart(Unpooled.wrappedBuffer("Hello ".getBytes()), false));
        bodyParts.add(new LazyResponseBodyPart(Unpooled.wrappedBuffer("World".getBytes()), true));
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), null, bodyParts);

        ByteBuf body = response.getResponseBodyAsByteBuf();
        assertEquals("Hello World", body.toString(StandardCharsets.UTF_8));
        body.release();
    }

    @Test
    public void testGetResponseBodyDecodesOnePartAndSplitPartsIdentically() {
        byte[] utf8 = {'c', 'a', 'f', (byte) 0xC3, (byte) 0xA9, ' ', (byte) 0xC3, (byte) 0xBC, 'b', 'e', 'r'};
        String expected = new String(utf8, StandardCharsets.UTF_8);
        // 0xC3 0xA9 encodes U+00E9; split between its two bytes so neither half decodes on its own
        int split = 4;

        List<HttpResponseBodyPart> onePart = new LinkedList<>();
        onePart.add(new EagerResponseBodyPart(Unpooled.wrappedBuffer(utf8), true));
        NettyResponse single = new NettyResponse(new NettyResponseStatus(null, null, null), null, onePart);

        List<HttpResponseBodyPart> splitParts = new LinkedList<>();
        splitParts.add(new EagerResponseBodyPart(Unpooled.wrappedBuffer(utf8, 0, split), false));
        splitParts.add(new EagerResponseBodyPart(Unpooled.wrappedBuffer(utf8, split, utf8.length - split), true));
        NettyResponse multiple = new NettyResponse(new NettyResponseStatus(null, null, null), null, splitParts);

        assertEquals(expected, single.getResponseBody(StandardCharsets.UTF_8));
        assertEquals(expected, multiple.getResponseBody(StandardCharsets.UTF_8));
        assertArrayEquals(utf8, single.getResponseBodyAsBytesView());
        assertArrayEquals(utf8, multiple.getResponseBodyAsBytesView());
    }

    @Test
    public void testGetResponseBodyReadsOnlyALazyPartsReadableRegion() throws IOException {
        // A Lazy part's getBodyPartBytes returns just the readable region, not the whole backing array, so a
        // single-part shortcut must go through it rather than reach for getBodyByteBuf().array().
        byte[] backing = "XXXHello WorldYYY".getBytes(StandardCharsets.UTF_8);
        ByteBuf slice = Unpooled.wrappedBuffer(backing).slice(3, 11);
        int readerIndex = slice.readerIndex();
        int writerIndex = slice.writerIndex();
        int refCnt = slice.refCnt();
        try {
            List<HttpResponseBodyPart> bodyParts = new LinkedList<>();
            bodyParts.add(new LazyResponseBodyPart(slice, true));
            NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), null, bodyParts);

            assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), response.getResponseBodyAsBytesView());
            assertEquals("Hello World", response.getResponseBody(StandardCharsets.UTF_8));
            assertEquals("Hello World",
                    new String(response.getResponseBodyAsStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(readerIndex, slice.readerIndex());
            assertEquals(writerIndex, slice.writerIndex());
            assertEquals(refCnt, slice.refCnt());
        } finally {
            slice.release();
        }
    }

    @Test
    public void testGetResponseBodyAsBytesViewReadsDirectLazyPart() {
        byte[] backing = "XXXHello WorldYYY".getBytes(StandardCharsets.UTF_8);
        ByteBuf direct = Unpooled.directBuffer(backing.length);
        direct.writeBytes(backing);
        ByteBuf slice = direct.slice(3, 11);
        int readerIndex = slice.readerIndex();
        int writerIndex = slice.writerIndex();
        int refCnt = slice.refCnt();
        try {
            List<HttpResponseBodyPart> bodyParts = new LinkedList<>();
            bodyParts.add(new LazyResponseBodyPart(slice, true));
            NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), null, bodyParts);

            assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), response.getResponseBodyAsBytesView());
            assertEquals(readerIndex, slice.readerIndex());
            assertEquals(writerIndex, slice.writerIndex());
            assertEquals(refCnt, slice.refCnt());
        } finally {
            direct.release();
        }
    }

    @Test
    public void testGetResponseBodyAsBytesViewSharesOneEagerPart() {
        List<HttpResponseBodyPart> bodyParts = new LinkedList<>();
        bodyParts.add(new EagerResponseBodyPart(Unpooled.wrappedBuffer("Hello World".getBytes(StandardCharsets.UTF_8)), true));
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), null, bodyParts);

        byte[] view = response.getResponseBodyAsBytesView();
        assertSame(bodyParts.get(0).getBodyPartBytes(), view);
        assertSame(view, response.getResponseBodyAsBytesView());
    }

    @Test
    public void testGetResponseBodyAsBytesDoesNotShareTheBodyPartArray() {
        byte[] expected = "Hello World".getBytes(StandardCharsets.UTF_8);
        List<HttpResponseBodyPart> bodyParts = new LinkedList<>();
        bodyParts.add(new EagerResponseBodyPart(Unpooled.wrappedBuffer(expected), true));
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), null, bodyParts);

        // getResponseBody may decode a lone part in place, but getResponseBodyAsBytes hands the array to the
        // caller, so it must keep copying rather than expose the part's own array.
        byte[] firstCopy = response.getResponseBodyAsBytes();
        byte[] secondCopy = response.getResponseBodyAsBytes();
        assertNotSame(firstCopy, secondCopy);
        assertNotSame(bodyParts.get(0).getBodyPartBytes(), firstCopy);

        firstCopy[0] = 'X';
        assertArrayEquals(expected, response.getResponseBodyAsBytes());
        assertArrayEquals(expected, response.getResponseBodyAsBytesView());
    }

    @Test
    public void testGetResponseBodyAsBytesViewReturnsEmptyArray() {
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), null, new LinkedList<>());

        assertArrayEquals(new byte[0], response.getResponseBodyAsBytesView());
    }

    @Test
    public void testGetResponseBodyAsBytesViewDefaultImplementationDelegates() {
        byte[] expected = "Hello World".getBytes(StandardCharsets.UTF_8);
        Response response = mock(Response.class, CALLS_REAL_METHODS);
        doReturn(expected).when(response).getResponseBodyAsBytes();

        assertSame(expected, response.getResponseBodyAsBytesView());
    }

    @Test
    public void testGetResponseBodyAsStreamDoesNotShareTheBodyPartArray() throws IOException {
        List<HttpResponseBodyPart> bodyParts = new LinkedList<>();
        bodyParts.add(new EagerResponseBodyPart(Unpooled.wrappedBuffer("Hello World".getBytes(StandardCharsets.UTF_8)), true));
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), null, bodyParts);

        // On JDK 11 ByteArrayInputStream.transferTo passes its own array to the OutputStream, so a stream over
        // a part's array would put that array in the caller's hands.
        byte[][] handedOut = new byte[1][];
        response.getResponseBodyAsStream().transferTo(new OutputStream() {
            @Override
            public void write(int b) {
            }

            @Override
            public void write(byte[] b, int off, int len) {
                handedOut[0] = b;
            }
        });

        assertNotSame(bodyParts.get(0).getBodyPartBytes(), handedOut[0]);
    }
}
