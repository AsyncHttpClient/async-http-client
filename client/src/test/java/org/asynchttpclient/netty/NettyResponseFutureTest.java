/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty;

import io.github.artsok.RepeatedIfExceptionsTest;
import org.asynchttpclient.AsyncHandler;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NettyResponseFutureTest {

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testCancel() {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
        boolean result = nettyResponseFuture.cancel(false);
        verify(asyncHandler).onThrowable(any());
        assertTrue(result, "Cancel should return true if the Future was cancelled successfully");
        assertTrue(nettyResponseFuture.isCancelled(), "isCancelled should return true for a cancelled Future");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testCancelOnAlreadyCancelled() {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
        nettyResponseFuture.cancel(false);
        boolean result = nettyResponseFuture.cancel(false);
        assertFalse(result, "cancel should return false for an already cancelled Future");
        assertTrue(nettyResponseFuture.isCancelled(), "isCancelled should return true for a cancelled Future");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testGetContentThrowsCancellationExceptionIfCancelled() throws Exception {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
        nettyResponseFuture.cancel(false);
        assertThrows(CancellationException.class, () -> nettyResponseFuture.get(), "A CancellationException must have occurred by now as 'cancel' was called before 'get'");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testGet() throws Exception {
        @SuppressWarnings("unchecked")
        AsyncHandler<Object> asyncHandler = mock(AsyncHandler.class);
        Object value = new Object();
        when(asyncHandler.onCompleted()).thenReturn(value);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
        nettyResponseFuture.done();
        Object result = nettyResponseFuture.get();
        assertEquals(value, result, "The Future should return the value given by asyncHandler#onCompleted");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testGetThrowsExceptionThrownByAsyncHandler() throws Exception {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        when(asyncHandler.onCompleted()).thenThrow(new RuntimeException());
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
        nettyResponseFuture.done();
        assertThrows(ExecutionException.class, () -> nettyResponseFuture.get(),
                "An ExecutionException must have occurred by now as asyncHandler threw an exception in 'onCompleted'");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testGetThrowsExceptionOnAbort() throws Exception {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
        nettyResponseFuture.abort(new RuntimeException());
        assertThrows(ExecutionException.class, () -> nettyResponseFuture.get(),
                "An ExecutionException must have occurred by now as 'abort' was called before 'get'");
    }
}
