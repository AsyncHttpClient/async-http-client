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
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.RANGE;
import static org.asynchttpclient.Dsl.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
