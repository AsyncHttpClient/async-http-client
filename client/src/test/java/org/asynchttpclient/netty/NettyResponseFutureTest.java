/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty;

import org.asynchttpclient.AsyncHandler;
import org.testng.annotations.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class NettyResponseFutureTest {

  @Test
  public void testCancel() {
    AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
    NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
    boolean result = nettyResponseFuture.cancel(false);
    verify(asyncHandler).onThrowable(anyObject());
    assertTrue(result, "Cancel should return true if the Future was cancelled successfully");
    assertTrue(nettyResponseFuture.isCancelled(), "isCancelled should return true for a cancelled Future");
  }

  @Test
  public void testCancelOnAlreadyCancelled() {
    AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
    NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
    nettyResponseFuture.cancel(false);
    boolean result = nettyResponseFuture.cancel(false);
    assertFalse(result, "cancel should return false for an already cancelled Future");
    assertTrue(nettyResponseFuture.isCancelled(), "isCancelled should return true for a cancelled Future");
  }

  @Test(expectedExceptions = CancellationException.class)
  public void testGetContentThrowsCancellationExceptionIfCancelled() throws InterruptedException, ExecutionException {
    AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
    NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
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
    NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
    nettyResponseFuture.done();
    Object result = nettyResponseFuture.get();
    assertEquals(result, value, "The Future should return the value given by asyncHandler#onCompleted");
  }

  @Test(expectedExceptions = ExecutionException.class)
  public void testGetThrowsExceptionThrownByAsyncHandler() throws Exception {
    AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
    when(asyncHandler.onCompleted()).thenThrow(new RuntimeException());
    NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
    nettyResponseFuture.done();
    nettyResponseFuture.get();
    fail("An ExecutionException must have occurred by now as asyncHandler threw an exception in 'onCompleted'");
  }

  @Test(expectedExceptions = ExecutionException.class)
  public void testGetThrowsExceptionOnAbort() throws InterruptedException, ExecutionException {
    AsyncHandler<?> asyncHandler = mock(AsyncHandler.class);
    NettyResponseFuture<?> nettyResponseFuture = new NettyResponseFuture<>(null, asyncHandler, null, 3, null, null, null);
    nettyResponseFuture.abort(new RuntimeException());
    nettyResponseFuture.get();
    fail("An ExecutionException must have occurred by now as 'abort' was called before 'get'");
  }
}
