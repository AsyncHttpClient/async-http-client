/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.rxjava.single;

import org.asynchttpclient.*;
import org.asynchttpclient.extras.rxjava.UnsubscribedException;
import org.asynchttpclient.handler.ProgressAsyncHandler;
import org.mockito.InOrder;
import org.testng.annotations.Test;
import rx.Single;
import rx.exceptions.CompositeException;
import rx.observers.TestSubscriber;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;

public class AsyncHttpSingleTest {

  @Test(expectedExceptions = {NullPointerException.class})
  public void testFailsOnNullRequest() {
    AsyncHttpSingle.create((BoundRequestBuilder) null);
  }

  @Test(expectedExceptions = {NullPointerException.class})
  public void testFailsOnNullHandlerSupplier() {
    AsyncHttpSingle.create(mock(BoundRequestBuilder.class), null);
  }

  @Test
  public void testSuccessfulCompletion() throws Exception {

    @SuppressWarnings("unchecked") final AsyncHandler<Object> handler = mock(AsyncHandler.class);
    when(handler.onCompleted()).thenReturn(handler);

    final Single<?> underTest = AsyncHttpSingle.create(bridge -> {
      try {
        assertThat(bridge, is(not(instanceOf(ProgressAsyncHandler.class))));

        bridge.onStatusReceived(null);
        verify(handler).onStatusReceived(null);

        bridge.onHeadersReceived(null);
        verify(handler).onHeadersReceived(null);

        bridge.onBodyPartReceived(null);
        verify(handler).onBodyPartReceived(null);

        bridge.onTrailingHeadersReceived(null);
        verify(handler).onTrailingHeadersReceived(null);

        bridge.onCompleted();
        verify(handler).onCompleted();
      } catch (final Throwable t) {
        bridge.onThrowable(t);
      }

      return mock(Future.class);
    }, () -> handler);

    final TestSubscriber<Object> subscriber = new TestSubscriber<>();
    underTest.subscribe(subscriber);

    verifyNoMoreInteractions(handler);

    subscriber.awaitTerminalEvent();
    subscriber.assertTerminalEvent();
    subscriber.assertNoErrors();
    subscriber.assertCompleted();
    subscriber.assertValue(handler);
  }

  @Test
  public void testSuccessfulCompletionWithProgress() throws Exception {

    @SuppressWarnings("unchecked") final ProgressAsyncHandler<Object> handler = mock(ProgressAsyncHandler.class);
    when(handler.onCompleted()).thenReturn(handler);
    final InOrder inOrder = inOrder(handler);

    final Single<?> underTest = AsyncHttpSingle.create(bridge -> {
      try {
        assertThat(bridge, is(instanceOf(ProgressAsyncHandler.class)));

        final ProgressAsyncHandler<?> progressBridge = (ProgressAsyncHandler<?>) bridge;

        progressBridge.onHeadersWritten();
        inOrder.verify(handler).onHeadersWritten();

        progressBridge.onContentWriteProgress(60, 40, 100);
        inOrder.verify(handler).onContentWriteProgress(60, 40, 100);

        progressBridge.onContentWritten();
        inOrder.verify(handler).onContentWritten();

        progressBridge.onStatusReceived(null);
        inOrder.verify(handler).onStatusReceived(null);

        progressBridge.onHeadersReceived(null);
        inOrder.verify(handler).onHeadersReceived(null);

        progressBridge.onBodyPartReceived(null);
        inOrder.verify(handler).onBodyPartReceived(null);

        bridge.onTrailingHeadersReceived(null);
        verify(handler).onTrailingHeadersReceived(null);

        progressBridge.onCompleted();
        inOrder.verify(handler).onCompleted();
      } catch (final Throwable t) {
        bridge.onThrowable(t);
      }

      return mock(Future.class);
    }, () -> handler);

    final TestSubscriber<Object> subscriber = new TestSubscriber<>();
    underTest.subscribe(subscriber);

    inOrder.verifyNoMoreInteractions();

    subscriber.awaitTerminalEvent();
    subscriber.assertTerminalEvent();
    subscriber.assertNoErrors();
    subscriber.assertCompleted();
    subscriber.assertValue(handler);
  }

  @Test
  public void testNewRequestForEachSubscription() {
    final BoundRequestBuilder builder = mock(BoundRequestBuilder.class);

    final Single<?> underTest = AsyncHttpSingle.create(builder);
    underTest.subscribe(new TestSubscriber<>());
    underTest.subscribe(new TestSubscriber<>());

    verify(builder, times(2)).execute(any());
    verifyNoMoreInteractions(builder);
  }

  @Test
  public void testErrorPropagation() throws Exception {

    final RuntimeException expectedException = new RuntimeException("expected");
    @SuppressWarnings("unchecked") final AsyncHandler<Object> handler = mock(AsyncHandler.class);
    when(handler.onCompleted()).thenReturn(handler);
    final InOrder inOrder = inOrder(handler);

    final Single<?> underTest = AsyncHttpSingle.create(bridge -> {
      try {
        bridge.onStatusReceived(null);
        inOrder.verify(handler).onStatusReceived(null);

        bridge.onHeadersReceived(null);
        inOrder.verify(handler).onHeadersReceived(null);

        bridge.onBodyPartReceived(null);
        inOrder.verify(handler).onBodyPartReceived(null);

        bridge.onThrowable(expectedException);
        inOrder.verify(handler).onThrowable(expectedException);

        // test that no further events are invoked after terminal events
        bridge.onCompleted();
        inOrder.verify(handler, never()).onCompleted();
      } catch (final Throwable t) {
        bridge.onThrowable(t);
      }

      return mock(Future.class);
    }, () -> handler);

    final TestSubscriber<Object> subscriber = new TestSubscriber<>();
    underTest.subscribe(subscriber);

    inOrder.verifyNoMoreInteractions();

    subscriber.awaitTerminalEvent();
    subscriber.assertTerminalEvent();
    subscriber.assertNoValues();
    subscriber.assertError(expectedException);
  }

  @Test
  public void testErrorInOnCompletedPropagation() throws Exception {

    final RuntimeException expectedException = new RuntimeException("expected");
    @SuppressWarnings("unchecked") final AsyncHandler<Object> handler = mock(AsyncHandler.class);
    when(handler.onCompleted()).thenThrow(expectedException);

    final Single<?> underTest = AsyncHttpSingle.create(bridge -> {
      try {
        bridge.onCompleted();
        return mock(Future.class);
      } catch (final Throwable t) {
        throw new AssertionError(t);
      }
    }, () -> handler);

    final TestSubscriber<Object> subscriber = new TestSubscriber<>();
    underTest.subscribe(subscriber);

    verify(handler).onCompleted();
    verifyNoMoreInteractions(handler);

    subscriber.awaitTerminalEvent();
    subscriber.assertTerminalEvent();
    subscriber.assertNoValues();
    subscriber.assertError(expectedException);
  }

  @Test
  public void testErrorInOnThrowablePropagation() {

    final RuntimeException processingException = new RuntimeException("processing");
    final RuntimeException thrownException = new RuntimeException("thrown");
    @SuppressWarnings("unchecked") final AsyncHandler<Object> handler = mock(AsyncHandler.class);
    doThrow(thrownException).when(handler).onThrowable(processingException);

    final Single<?> underTest = AsyncHttpSingle.create(bridge -> {
      try {
        bridge.onThrowable(processingException);
        return mock(Future.class);
      } catch (final Throwable t) {
        throw new AssertionError(t);
      }
    }, () -> handler);

    final TestSubscriber<Object> subscriber = new TestSubscriber<>();
    underTest.subscribe(subscriber);

    verify(handler).onThrowable(processingException);
    verifyNoMoreInteractions(handler);

    subscriber.awaitTerminalEvent();
    subscriber.assertTerminalEvent();
    subscriber.assertNoValues();

    final List<Throwable> errorEvents = subscriber.getOnErrorEvents();
    assertEquals(errorEvents.size(), 1);
    assertThat(errorEvents.get(0), is(instanceOf(CompositeException.class)));
    final CompositeException error = (CompositeException) errorEvents.get(0);
    assertEquals(error.getExceptions(), Arrays.asList(processingException, thrownException));
  }

  @Test
  public void testAbort() throws Exception {
    final TestSubscriber<Response> subscriber = new TestSubscriber<>();

    try (AsyncHttpClient client = asyncHttpClient()) {
      final Single<Response> underTest = AsyncHttpSingle.create(client.prepareGet("http://gatling.io"),
              () -> new AsyncCompletionHandlerBase() {
                @Override
                public State onStatusReceived(HttpResponseStatus status) {
                  return State.ABORT;
                }
              });

      underTest.subscribe(subscriber);
      subscriber.awaitTerminalEvent();
    }

    subscriber.assertTerminalEvent();
    subscriber.assertNoErrors();
    subscriber.assertCompleted();
    subscriber.assertValue(null);
  }

  @Test
  public void testUnsubscribe() throws Exception {
    @SuppressWarnings("unchecked") final AsyncHandler<Object> handler = mock(AsyncHandler.class);
    final Future<?> future = mock(Future.class);
    final AtomicReference<AsyncHandler<?>> bridgeRef = new AtomicReference<>();

    final Single<?> underTest = AsyncHttpSingle.create(bridge -> {
      bridgeRef.set(bridge);
      return future;
    }, () -> handler);

    underTest.subscribe().unsubscribe();
    verify(future).cancel(true);
    verifyZeroInteractions(handler);

    assertThat(bridgeRef.get().onStatusReceived(null), is(AsyncHandler.State.ABORT));
    verify(handler).onThrowable(isA(UnsubscribedException.class));
    verifyNoMoreInteractions(handler);
  }
}
