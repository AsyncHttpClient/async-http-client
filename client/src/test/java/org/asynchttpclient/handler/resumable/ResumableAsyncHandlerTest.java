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

import static org.asynchttpclient.Dsl.get;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.*;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.testng.Assert.*;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;
import org.asynchttpclient.uri.Uri;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.testng.PowerMockTestCase;
import org.testng.annotations.Test;

/**
 * @author Benjamin Hanzelmann
 */
@PrepareForTest({ HttpResponseStatus.class, State.class })
public class ResumableAsyncHandlerTest extends PowerMockTestCase {
    @Test
    public void testAdjustRange() {
        MapResumableProcessor proc = new MapResumableProcessor();

        ResumableAsyncHandler handler = new ResumableAsyncHandler(proc);
        Request request = get("http://test/url").build();
        Request newRequest = handler.adjustRequestRange(request);
        assertEquals(newRequest.getUri(), request.getUri());
        String rangeHeader = newRequest.getHeaders().get(HttpHeaders.Names.RANGE);
        assertNull(rangeHeader);

        proc.put("http://test/url", 5000);
        newRequest = handler.adjustRequestRange(request);
        assertEquals(newRequest.getUri(), request.getUri());
        rangeHeader = newRequest.getHeaders().get(HttpHeaders.Names.RANGE);
        assertEquals(rangeHeader, "bytes=5000-");
    }

    @Test
    public void testOnStatusReceivedOkStatus() throws Exception {
        MapResumableProcessor processor = new MapResumableProcessor();
        ResumableAsyncHandler handler = new ResumableAsyncHandler(processor);
        HttpResponseStatus responseStatus200 = mock(HttpResponseStatus.class);
        when(responseStatus200.getStatusCode()).thenReturn(200);
        when(responseStatus200.getUri()).thenReturn(mock(Uri.class));
        State state = handler.onStatusReceived(responseStatus200);
        assertEquals(state, AsyncHandler.State.CONTINUE, "Status should be CONTINUE for a OK response");
    }
    
    @Test
    public void testOnStatusReceived206Status() throws Exception {
        MapResumableProcessor processor = new MapResumableProcessor();
        ResumableAsyncHandler handler = new ResumableAsyncHandler(processor);
        HttpResponseStatus responseStatus206 = mock(HttpResponseStatus.class);
        when(responseStatus206.getStatusCode()).thenReturn(206);
        when(responseStatus206.getUri()).thenReturn(mock(Uri.class));
        State state = handler.onStatusReceived(responseStatus206);
        assertEquals(state, AsyncHandler.State.CONTINUE, "Status should be CONTINUE for a 'Partial Content' response");
    }
    
    @Test
    public void testOnStatusReceivedOkStatusWithDecoratedAsyncHandler() throws Exception {
        HttpResponseStatus mockResponseStatus = mock(HttpResponseStatus.class);
        when(mockResponseStatus.getStatusCode()).thenReturn(200);
        when(mockResponseStatus.getUri()).thenReturn(mock(Uri.class));

        @SuppressWarnings("unchecked")
        AsyncHandler<Response> decoratedAsyncHandler = mock(AsyncHandler.class);
        State mockState = mock(State.class);
        when(decoratedAsyncHandler.onStatusReceived(mockResponseStatus)).thenReturn(mockState);

        ResumableAsyncHandler handler = new ResumableAsyncHandler(decoratedAsyncHandler);

        State state = handler.onStatusReceived(mockResponseStatus);
        verify(decoratedAsyncHandler).onStatusReceived(mockResponseStatus);
        assertEquals(state, mockState, "State returned should be equal to the one returned from decoratedAsyncHandler");
    }
    
    @Test
    public void testOnStatusReceived500Status() throws Exception{
        MapResumableProcessor processor = new MapResumableProcessor();
        ResumableAsyncHandler handler = new ResumableAsyncHandler(processor);
        HttpResponseStatus mockResponseStatus = mock(HttpResponseStatus.class);
        when(mockResponseStatus.getStatusCode()).thenReturn(500);
        when(mockResponseStatus.getUri()).thenReturn(mock(Uri.class));
        State state = handler.onStatusReceived(mockResponseStatus);
        assertEquals(state, AsyncHandler.State.ABORT, "State should be ABORT for Internal Server Error status");
    }
    
    @Test
    public void testOnBodyPartReceived() throws Exception {
        ResumableAsyncHandler handler = new ResumableAsyncHandler();
        HttpResponseBodyPart bodyPart = PowerMockito.mock(HttpResponseBodyPart.class);
        when(bodyPart.getBodyPartBytes()).thenReturn(new byte[0]);
        ByteBuffer buffer = ByteBuffer.allocate(0);
        when(bodyPart.getBodyByteBuffer()).thenReturn(buffer);
        State state = handler.onBodyPartReceived(bodyPart);
        assertEquals(state, AsyncHandler.State.CONTINUE, "State should be CONTINUE for a successful onBodyPartReceived");
    }
    
    @Test
    public void testOnBodyPartReceivedWithResumableListenerThrowsException() throws Exception {
        ResumableAsyncHandler handler = new ResumableAsyncHandler();

        ResumableListener resumableListener = PowerMockito.mock(ResumableListener.class);
        doThrow(new IOException()).when(resumableListener).onBytesReceived(anyObject());
        handler.setResumableListener(resumableListener);

        HttpResponseBodyPart bodyPart = PowerMockito.mock(HttpResponseBodyPart.class);
        State state = handler.onBodyPartReceived(bodyPart);
        assertEquals(state, AsyncHandler.State.ABORT,
                "State should be ABORT if the resumableListener threw an exception in onBodyPartReceived");
    }
    
    @Test
    public void testOnBodyPartReceivedWithDecoratedAsyncHandler() throws Exception {
        HttpResponseBodyPart bodyPart = PowerMockito.mock(HttpResponseBodyPart.class);
        when(bodyPart.getBodyPartBytes()).thenReturn(new byte[0]);
        ByteBuffer buffer = ByteBuffer.allocate(0);
        when(bodyPart.getBodyByteBuffer()).thenReturn(buffer);

        @SuppressWarnings("unchecked")
        AsyncHandler<Response> decoratedAsyncHandler = mock(AsyncHandler.class);
        State mockState = mock(State.class);
        when(decoratedAsyncHandler.onBodyPartReceived(bodyPart)).thenReturn(mockState);

        // following is needed to set the url variable
        HttpResponseStatus mockResponseStatus = mock(HttpResponseStatus.class);
        when(mockResponseStatus.getStatusCode()).thenReturn(200);
        Uri mockUri = mock(Uri.class);
        when(mockUri.toUrl()).thenReturn("http://non.null");
        when(mockResponseStatus.getUri()).thenReturn(mockUri);

        ResumableAsyncHandler handler = new ResumableAsyncHandler(decoratedAsyncHandler);
        handler.onStatusReceived(mockResponseStatus);

        State state = handler.onBodyPartReceived(bodyPart);
        assertEquals(state, mockState, "State should be equal to the state returned from decoratedAsyncHandler");

    }
    
    @Test
    public void testOnHeadersReceived() throws Exception {
        ResumableAsyncHandler handler = new ResumableAsyncHandler();
        HttpHeaders responseHeaders = new DefaultHttpHeaders();
        HttpResponseHeaders headers = new HttpResponseHeaders(responseHeaders);
        State status = handler.onHeadersReceived(headers);
        assertEquals(status, AsyncHandler.State.CONTINUE, "State should be CONTINUE for a successful onHeadersReceived");
    }
    
    @Test
    public void testOnHeadersReceivedWithDecoratedAsyncHandler() throws Exception {
        HttpHeaders responseHeaders = new DefaultHttpHeaders();
        HttpResponseHeaders headers = new HttpResponseHeaders(responseHeaders);

        @SuppressWarnings("unchecked")
        AsyncHandler<Response> decoratedAsyncHandler = mock(AsyncHandler.class);
        State mockState = mock(State.class);
        when(decoratedAsyncHandler.onHeadersReceived(headers)).thenReturn(mockState);

        ResumableAsyncHandler handler = new ResumableAsyncHandler(decoratedAsyncHandler);
        State status = handler.onHeadersReceived(headers);
        assertEquals(status, mockState, "State should be equal to the state returned from decoratedAsyncHandler");
    }
    
    @Test
    public void testOnHeadersReceivedContentLengthMinus() throws Exception {
        ResumableAsyncHandler handler = new ResumableAsyncHandler();
        HttpHeaders responseHeaders = new DefaultHttpHeaders();
        responseHeaders.add(HttpHeaders.Names.CONTENT_LENGTH, -1);
        HttpResponseHeaders headers = new HttpResponseHeaders(responseHeaders);
        State status = handler.onHeadersReceived(headers);
        assertEquals(status, AsyncHandler.State.ABORT, "State should be ABORT for content length -1");
    }
}
