/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.handler.resumable;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.ReadOnlyHttpHeaders;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.DefaultRequest;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.RANGE;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Benjamin Hanzelmann
 */
public class ResumableAsyncHandlerTest {

    public static final byte[] T = new byte[0];

    @Test
    public void testAdjustRange() {
        MapResumableProcessor processor = new MapResumableProcessor();

        ResumableAsyncHandler handler = new ResumableAsyncHandler(processor);
        Request request = get("http://test/url").build();
        Request newRequest = handler.adjustRequestRange(request);
        assertEquals(request.getUri(), newRequest.getUri());
        String rangeHeader = newRequest.getHeaders().get(RANGE);
        assertNull(rangeHeader);

        processor.put("http://test/url", 5000);
        newRequest = handler.adjustRequestRange(request);
        assertEquals(request.getUri(), newRequest.getUri());
        rangeHeader = newRequest.getHeaders().get(RANGE);
        assertEquals("bytes=5000-", rangeHeader);
    }

    @Test
    public void testAdjustRangeKeepsRawUrlAsBuilt() {
        String url = "http://localhost/a|b?q={x}";
        Request request = new RequestBuilder(GET, true).setUrl(url).build();
        assertEquals(url, request.getUrl());
        assertNotEquals(url, new RequestBuilder(GET).setUrl(url).build().getUrl(), "the URL must be one encoding would change");
        MapResumableProcessor processor = new MapResumableProcessor();
        processor.put(url, 5000);

        Request newRequest = new ResumableAsyncHandler(processor).adjustRequestRange(request);

        assertEquals(url, newRequest.getUrl());
        assertEquals("bytes=5000-", newRequest.getHeaders().get(RANGE));
        assertNull(request.getHeaders().get(RANGE));
    }

    @Test
    public void testAdjustRangeKeepsOtherRequestFields() throws Exception {
        Request request = new RequestBuilder("POST")
                .setUrl("http://test/url?x=1")
                .setAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))
                .setLocalAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 2}))
                .setHeader("X-Custom", "v")
                .addCookie(new DefaultCookie("a", "b"))
                .setBody("body")
                .setVirtualHost("vhost")
                .setProxyServer(proxyServer("proxy", 8080))
                .setRealm(basicAuthRealm("user", "pass"))
                .setFollowRedirect(true)
                .setUseAbsoluteRequestDeadline(false)
                .setRefuseSchemeDowngradeOnRedirect(true)
                .setRefuseCrossOriginBodyOnRedirect(false)
                .setRequestTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(2))
                .setRangeOffset(7)
                .setCharset(UTF_16)
                .build();
        MapResumableProcessor processor = new MapResumableProcessor();
        processor.put(request.getUrl(), 5000);
        Request expected = request.toBuilder().setHeader(RANGE, "bytes=5000-").build();

        Request newRequest = new ResumableAsyncHandler(processor).adjustRequestRange(request);

        assertEquals(DefaultRequest.class, newRequest.getClass());
        assertEquals(expected.getHeaders().entries().toString(), newRequest.getHeaders().entries().toString());
        for (Field field : DefaultRequest.class.getDeclaredFields()) {
            String name = field.getName();
            // queryParams is computed from the URI on first use.
            if (Modifier.isStatic(field.getModifiers()) || "headers".equals(name) || "queryParams".equals(name)) {
                continue;
            }
            field.setAccessible(true);
            assertEquals(field.get(expected), field.get(newRequest), name);
        }
    }

    @Test
    public void testAdjustRangeDoesNotValidateRequestHeaders() {
        Request built = get("http://test/url").build();
        Request request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(), built.getLocalAddress(),
                new ReadOnlyHttpHeaders(false, "X-Raw", "a\u0001b", "X Bad", "v"), built.getCookies(), built.getByteData(),
                built.getCompositeByteData(), built.getStringData(), built.getByteBufferData(), built.getByteBufData(),
                built.getStreamData(), built.getBodyGenerator(), built.getFormParams(), built.getBodyParts(),
                built.getVirtualHost(), built.getProxyServer(), built.getRealm(), built.getFile(), built.getFollowRedirect(),
                built.getRequestTimeout(), built.getReadTimeout(), built.getRangeOffset(), built.getCharset(),
                built.getChannelPoolPartitioning(), built.getNameResolver());
        MapResumableProcessor processor = new MapResumableProcessor();
        processor.put("http://test/url", 5000);

        Request newRequest = new ResumableAsyncHandler(processor).adjustRequestRange(request);

        assertEquals("bytes=5000-", newRequest.getHeaders().get(RANGE));
        assertEquals("a\u0001b", newRequest.getHeaders().get("X-Raw"));
        assertEquals("v", newRequest.getHeaders().get("X Bad"));
    }

    @Test
    public void testAdjustRangeFillsInFieldsOfHandBuiltRequest() {
        String url = "http://localhost/a|b?q={x}";
        Request built = new RequestBuilder(GET, true).setUrl(url).build();
        Request request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(), built.getLocalAddress(),
                built.getHeaders(), null, built.getByteData(), built.getCompositeByteData(), built.getStringData(),
                built.getByteBufferData(), built.getByteBufData(), built.getStreamData(), built.getBodyGenerator(), null,
                null, built.getVirtualHost(), built.getProxyServer(), built.getRealm(), built.getFile(),
                built.getFollowRedirect(), built.getRequestTimeout(), built.getReadTimeout(), built.getRangeOffset(), null,
                built.getChannelPoolPartitioning(), built.getNameResolver());

        Request withoutRange = new ResumableAsyncHandler(new MapResumableProcessor()).adjustRequestRange(request);
        MapResumableProcessor processor = new MapResumableProcessor();
        processor.put(url, 5000);
        Request withRange = new ResumableAsyncHandler(processor).adjustRequestRange(request);

        assertNull(withoutRange.getHeaders().get(RANGE));
        assertEquals("bytes=5000-", withRange.getHeaders().get(RANGE));
        for (Request adjusted : List.of(withoutRange, withRange)) {
            assertEquals(url, adjusted.getUrl());
            assertEquals(UTF_8, adjusted.getCharset());
            assertEquals(List.of(), adjusted.getCookies());
            assertEquals(List.of(), adjusted.getFormParams());
            assertEquals(List.of(), adjusted.getBodyParts());
        }
    }

    @Test
    public void testAdjustRangeRebuildsHandBuiltRequestWithAnyNullField() {
        Request built = get("http://test/url").build();
        ResumableAsyncHandler handler = new ResumableAsyncHandler(new MapResumableProcessor());
        for (int nullField = 0; nullField < 4; nullField++) {
            Request request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(), built.getLocalAddress(),
                    built.getHeaders(), nullField == 0 ? null : built.getCookies(), built.getByteData(),
                    built.getCompositeByteData(), built.getStringData(), built.getByteBufferData(), built.getByteBufData(),
                    built.getStreamData(), built.getBodyGenerator(), nullField == 1 ? null : built.getFormParams(),
                    nullField == 2 ? null : built.getBodyParts(), built.getVirtualHost(), built.getProxyServer(),
                    built.getRealm(), built.getFile(), built.getFollowRedirect(), built.getRequestTimeout(),
                    built.getReadTimeout(), built.getRangeOffset(), nullField == 3 ? null : built.getCharset(),
                    built.getChannelPoolPartitioning(), built.getNameResolver());

            Request adjusted = handler.adjustRequestRange(request);

            String field = "null field " + nullField;
            assertNotSame(request, adjusted, field);
            assertNotNull(adjusted.getCookies(), field);
            assertNotNull(adjusted.getFormParams(), field);
            assertNotNull(adjusted.getBodyParts(), field);
            assertNotNull(adjusted.getCharset(), field);
        }
    }

    @Test
    public void testAdjustRangeKeepsExistingRangeHeaderOfRebuiltRequest() {
        Request built = get("http://test/url").setHeader(RANGE, "bytes=10-").build();
        Request request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(), built.getLocalAddress(),
                built.getHeaders(), null, built.getByteData(), built.getCompositeByteData(), built.getStringData(),
                built.getByteBufferData(), built.getByteBufData(), built.getStreamData(), built.getBodyGenerator(),
                built.getFormParams(), built.getBodyParts(), built.getVirtualHost(), built.getProxyServer(), built.getRealm(),
                built.getFile(), built.getFollowRedirect(), built.getRequestTimeout(), built.getReadTimeout(),
                built.getRangeOffset(), built.getCharset(), built.getChannelPoolPartitioning(), built.getNameResolver());
        MapResumableProcessor processor = new MapResumableProcessor();
        processor.put("http://test/url", 5000);

        Request adjusted = new ResumableAsyncHandler(processor).adjustRequestRange(request);

        assertNotSame(request, adjusted);
        assertEquals(List.of("bytes=10-"), adjusted.getHeaders().getAll(RANGE));
    }

    @Test
    public void testAdjustRangeRebuildsRequestSubclassWithNullCharsetThroughToBuilder() {
        Request built = get("http://test/url").build();
        AtomicInteger toBuilderCalls = new AtomicInteger();
        Request request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(), built.getLocalAddress(),
                built.getHeaders(), built.getCookies(), built.getByteData(), built.getCompositeByteData(), built.getStringData(),
                built.getByteBufferData(), built.getByteBufData(), built.getStreamData(), built.getBodyGenerator(),
                built.getFormParams(), built.getBodyParts(), built.getVirtualHost(), built.getProxyServer(), built.getRealm(),
                built.getFile(), built.getFollowRedirect(), built.getRequestTimeout(), built.getReadTimeout(),
                built.getRangeOffset(), null, built.getChannelPoolPartitioning(), built.getNameResolver()) {
            @Override
            public RequestBuilder toBuilder() {
                toBuilderCalls.incrementAndGet();
                return super.toBuilder();
            }
        };

        Request adjusted = new ResumableAsyncHandler(new MapResumableProcessor()).adjustRequestRange(request);

        assertEquals(1, toBuilderCalls.get());
        assertNotSame(request, adjusted);
        assertEquals(UTF_8, adjusted.getCharset());
        assertNull(adjusted.getHeaders().get(RANGE));
    }

    @Test
    public void testAdjustRangeValidatesSchemeOfRebuiltRequest() {
        Request built = get("http://test/url").build();
        Request request = new DefaultRequest(built.getMethod(), Uri.create("ftp://test/url"), built.getAddress(),
                built.getLocalAddress(), built.getHeaders(), null, built.getByteData(), built.getCompositeByteData(),
                built.getStringData(), built.getByteBufferData(), built.getByteBufData(), built.getStreamData(),
                built.getBodyGenerator(), built.getFormParams(), built.getBodyParts(), built.getVirtualHost(),
                built.getProxyServer(), built.getRealm(), built.getFile(), built.getFollowRedirect(),
                built.getRequestTimeout(), built.getReadTimeout(), built.getRangeOffset(), built.getCharset(),
                built.getChannelPoolPartitioning(), built.getNameResolver());
        ResumableAsyncHandler handler = new ResumableAsyncHandler(new MapResumableProcessor());

        assertThrows(IllegalArgumentException.class, () -> handler.adjustRequestRange(request));
    }

    @Test
    public void testAdjustRangeWithoutOffsetLeavesRequestAsIs() {
        Request request = get("http://test/url").build();

        assertSame(request, new ResumableAsyncHandler(new MapResumableProcessor()).adjustRequestRange(request));
    }

    @Test
    public void testAdjustRangeKeepsExistingRangeHeader() {
        Request request = get("http://test/url").setHeader(RANGE, "bytes=10-").build();
        MapResumableProcessor processor = new MapResumableProcessor();
        processor.put("http://test/url", 5000);

        Request newRequest = new ResumableAsyncHandler(processor).adjustRequestRange(request);

        assertSame(request, newRequest);
        assertEquals("bytes=10-", newRequest.getHeaders().get(RANGE));
    }

    @Test
    public void testAdjustRangeGoesThroughToBuilderForRequestSubclass() {
        Request built = get("http://test/url").build();
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
        MapResumableProcessor processor = new MapResumableProcessor();
        processor.put("http://test/url", 5000);

        Request newRequest = new ResumableAsyncHandler(processor).adjustRequestRange(request);

        assertEquals(1, toBuilderCalls.get());
        assertEquals("bytes=5000-", newRequest.getHeaders().get(RANGE));
    }

    @Test
    public void testOnStatusReceivedOkStatus() throws Exception {
        MapResumableProcessor processor = new MapResumableProcessor();
        ResumableAsyncHandler handler = new ResumableAsyncHandler(processor);
        HttpResponseStatus responseStatus200 = mock(HttpResponseStatus.class);
        when(responseStatus200.getStatusCode()).thenReturn(200);
        when(responseStatus200.getUri()).thenReturn(mock(Uri.class));
        State state = handler.onStatusReceived(responseStatus200);
        assertEquals(AsyncHandler.State.CONTINUE, state, "Status should be CONTINUE for a OK response");
    }

    @Test
    public void testOnStatusReceived206Status() throws Exception {
        MapResumableProcessor processor = new MapResumableProcessor();
        ResumableAsyncHandler handler = new ResumableAsyncHandler(processor);
        HttpResponseStatus responseStatus206 = mock(HttpResponseStatus.class);
        when(responseStatus206.getStatusCode()).thenReturn(206);
        when(responseStatus206.getUri()).thenReturn(mock(Uri.class));
        State state = handler.onStatusReceived(responseStatus206);
        assertEquals(AsyncHandler.State.CONTINUE, state, "Status should be CONTINUE for a 'Partial Content' response");
    }

    @Test
    public void testOnStatusReceivedOkStatusWithDecoratedAsyncHandler() throws Exception {
        HttpResponseStatus mockResponseStatus = mock(HttpResponseStatus.class);
        when(mockResponseStatus.getStatusCode()).thenReturn(200);
        when(mockResponseStatus.getUri()).thenReturn(mock(Uri.class));

        @SuppressWarnings("unchecked")
        AsyncHandler<Response> decoratedAsyncHandler = mock(AsyncHandler.class);
        when(decoratedAsyncHandler.onStatusReceived(mockResponseStatus)).thenReturn(State.CONTINUE);

        ResumableAsyncHandler handler = new ResumableAsyncHandler(decoratedAsyncHandler);

        State state = handler.onStatusReceived(mockResponseStatus);
        verify(decoratedAsyncHandler).onStatusReceived(mockResponseStatus);
        assertEquals(State.CONTINUE, state, "State returned should be equal to the one returned from decoratedAsyncHandler");
    }

    @Test
    public void testOnStatusReceived500Status() throws Exception {
        MapResumableProcessor processor = new MapResumableProcessor();
        ResumableAsyncHandler handler = new ResumableAsyncHandler(processor);
        HttpResponseStatus mockResponseStatus = mock(HttpResponseStatus.class);
        when(mockResponseStatus.getStatusCode()).thenReturn(500);
        when(mockResponseStatus.getUri()).thenReturn(mock(Uri.class));
        State state = handler.onStatusReceived(mockResponseStatus);
        assertEquals(AsyncHandler.State.ABORT, state, "State should be ABORT for Internal Server Error status");
    }

    @Test
    public void testOnBodyPartReceived() throws Exception {
        ResumableAsyncHandler handler = new ResumableAsyncHandler();
        HttpResponseBodyPart bodyPart = mock(HttpResponseBodyPart.class);
        when(bodyPart.getBodyPartBytes()).thenReturn(T);
        ByteBuffer buffer = ByteBuffer.allocate(0);
        when(bodyPart.getBodyByteBuffer()).thenReturn(buffer);
        State state = handler.onBodyPartReceived(bodyPart);
        assertEquals(AsyncHandler.State.CONTINUE, state, "State should be CONTINUE for a successful onBodyPartReceived");
    }

    @Test
    public void testOnBodyPartReceivedWithResumableListenerThrowsException() throws Exception {
        ResumableAsyncHandler handler = new ResumableAsyncHandler();

        ResumableListener resumableListener = mock(ResumableListener.class);
        doThrow(new IOException()).when(resumableListener).onBytesReceived(any());
        handler.setResumableListener(resumableListener);

        HttpResponseBodyPart bodyPart = mock(HttpResponseBodyPart.class);
        State state = handler.onBodyPartReceived(bodyPart);
        assertEquals(AsyncHandler.State.ABORT, state,
                "State should be ABORT if the resumableListener threw an exception in onBodyPartReceived");
    }

    @Test
    public void testOnBodyPartReceivedWithDecoratedAsyncHandler() throws Exception {
        HttpResponseBodyPart bodyPart = mock(HttpResponseBodyPart.class);
        when(bodyPart.getBodyPartBytes()).thenReturn(new byte[0]);
        ByteBuffer buffer = ByteBuffer.allocate(0);
        when(bodyPart.getBodyByteBuffer()).thenReturn(buffer);

        @SuppressWarnings("unchecked")
        AsyncHandler<Response> decoratedAsyncHandler = mock(AsyncHandler.class);
        when(decoratedAsyncHandler.onBodyPartReceived(bodyPart)).thenReturn(State.CONTINUE);

        // following is needed to set the url variable
        HttpResponseStatus mockResponseStatus = mock(HttpResponseStatus.class);
        when(mockResponseStatus.getStatusCode()).thenReturn(200);
        Uri uri = Uri.create("http://non.null");
        when(mockResponseStatus.getUri()).thenReturn(uri);

        ResumableAsyncHandler handler = new ResumableAsyncHandler(decoratedAsyncHandler);
        handler.onStatusReceived(mockResponseStatus);

        State state = handler.onBodyPartReceived(bodyPart);
        assertEquals(State.CONTINUE, state, "State should be equal to the state returned from decoratedAsyncHandler");
    }

    @Test
    public void testOnHeadersReceived() throws Exception {
        ResumableAsyncHandler handler = new ResumableAsyncHandler();
        HttpHeaders responseHeaders = new DefaultHttpHeaders();
        State status = handler.onHeadersReceived(responseHeaders);
        assertEquals(AsyncHandler.State.CONTINUE, status, "State should be CONTINUE for a successful onHeadersReceived");
    }

    @Test
    public void testOnHeadersReceivedWithDecoratedAsyncHandler() throws Exception {
        HttpHeaders responseHeaders = new DefaultHttpHeaders();

        @SuppressWarnings("unchecked")
        AsyncHandler<Response> decoratedAsyncHandler = mock(AsyncHandler.class);
        when(decoratedAsyncHandler.onHeadersReceived(responseHeaders)).thenReturn(State.CONTINUE);

        ResumableAsyncHandler handler = new ResumableAsyncHandler(decoratedAsyncHandler);
        State status = handler.onHeadersReceived(responseHeaders);
        assertEquals(State.CONTINUE, status, "State should be equal to the state returned from decoratedAsyncHandler");
    }

    @Test
    public void testOnHeadersReceivedContentLengthMinus() throws Exception {
        ResumableAsyncHandler handler = new ResumableAsyncHandler();
        HttpHeaders responseHeaders = new DefaultHttpHeaders();
        responseHeaders.add(CONTENT_LENGTH, -1);
        State status = handler.onHeadersReceived(responseHeaders);
        assertEquals(AsyncHandler.State.ABORT, status, "State should be ABORT for content length -1");
    }
}
