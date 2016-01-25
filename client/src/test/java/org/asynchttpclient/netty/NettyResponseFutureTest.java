package org.asynchttpclient.netty;

import static org.testng.Assert.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static org.mockito.Mockito.*;

import org.asynchttpclient.AsyncHandler;
import org.testng.annotations.Test;

public class NettyResponseFutureTest {

    @Test
    public void testCancel() {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null);
        boolean result = nettyResponseFuture.cancel(false);
        verify(asyncHandler).onThrowable(anyObject());
        assertTrue(result, "Cancel should return true if the Future was cancelled successfully");
        assertTrue(nettyResponseFuture.isCancelled(), "isCancelled should return true for a cancelled Future");
    }

    @Test
    public void testCancelOnAlreadyCancelled() {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null);
        nettyResponseFuture.cancel(false);
        boolean result = nettyResponseFuture.cancel(false);
        assertFalse(result, "cancel should return false for an already cancelled Future");
        assertTrue(nettyResponseFuture.isCancelled(), "isCancelled should return true for a cancelled Future");
    }

    @Test(expectedExceptions = CancellationException.class)
    public void testGetContentThrowsCancellationExceptionIfCancelled() throws InterruptedException, ExecutionException {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null);
        nettyResponseFuture.cancel(false);
        nettyResponseFuture.get();
        fail("A CancellationException must have occurred by now as 'cancel' was called before 'get'");
    }

    @Test
    public void testGet() throws Exception {
        @SuppressWarnings("unchecked")
        AsyncHandler<Object> asyncHandler = mock(AsyncHandler.class);
        Object value = new Object();
        when(asyncHandler.onCompleted()).thenReturn(value);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null);
        nettyResponseFuture.done();
        Object result = nettyResponseFuture.get();
        assertEquals(result, value, "The Future should return the value given by asyncHandler#onCompleted");
    }

    @Test(expectedExceptions = ExecutionException.class)
    public void testGetThrowsExceptionThrownByAsyncHandler() throws Exception {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        when(asyncHandler.onCompleted()).thenThrow(new RuntimeException());
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null);
        nettyResponseFuture.done();
        nettyResponseFuture.get();
        fail("An ExecutionException must have occurred by now as asyncHandler threw an exception in 'onCompleted'");
    }

    @Test(expectedExceptions = ExecutionException.class)
    public void testGetThrowsExceptionOnAbort() throws InterruptedException, ExecutionException {
        AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
        NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null);
        nettyResponseFuture.abort(new RuntimeException());
        nettyResponseFuture.get();
        fail("An ExecutionException must have occurred by now as 'abort' was called before 'get'");
    }
}
