/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.rxjava2;

import io.reactivex.Maybe;
import io.reactivex.observers.TestObserver;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.handler.ProgressAsyncHandler;
import org.mockito.*;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.function.Supplier;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

public class DefaultRxHttpClientTest {

  @Mock
  private AsyncHttpClient asyncHttpClient;

  @Mock
  private Request request;

  @Mock
  private Supplier<AsyncHandler<Object>> handlerSupplier;

  @Mock
  private AsyncHandler<Object> handler;

  @Mock
  private ProgressAsyncHandler<Object> progressHandler;

  @Captor
  private ArgumentCaptor<AsyncHandler<Object>> handlerCaptor;

  @Mock
  private ListenableFuture<Object> resposeFuture;

  @InjectMocks
  private DefaultRxHttpClient underTest;

  @BeforeMethod
  public void initializeTest() {
    underTest = null; // we want a fresh instance for each test
    MockitoAnnotations.initMocks(this);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void rejectsNullClient() {
    new DefaultRxHttpClient(null);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void rejectsNullRequest() {
    underTest.prepare(null, handlerSupplier);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void rejectsNullHandlerSupplier() {
    underTest.prepare(request, null);
  }

  @Test
  public void emitsNullPointerExceptionWhenNullHandlerIsSupplied() {
    // given
    given(handlerSupplier.get()).willReturn(null);
    final TestObserver<Object> subscriber = new TestObserver<>();

    // when
    underTest.prepare(request, handlerSupplier).subscribe(subscriber);

    // then
    subscriber.assertTerminated();
    subscriber.assertNoValues();
    subscriber.assertError(NullPointerException.class);
    then(handlerSupplier).should().get();
    verifyNoMoreInteractions(handlerSupplier);
  }

  @Test
  public void usesVanillaAsyncHandler() {
    // given
    given(handlerSupplier.get()).willReturn(handler);

    // when
    underTest.prepare(request, handlerSupplier).subscribe();

    // then
    then(asyncHttpClient).should().executeRequest(eq(request), handlerCaptor.capture());
    final AsyncHandler<Object> bridge = handlerCaptor.getValue();
    assertThat(bridge, is(not(instanceOf(ProgressAsyncHandler.class))));
  }

  @Test
  public void usesProgressAsyncHandler() {
    given(handlerSupplier.get()).willReturn(progressHandler);

    // when
    underTest.prepare(request, handlerSupplier).subscribe();

    // then
    then(asyncHttpClient).should().executeRequest(eq(request), handlerCaptor.capture());
    final AsyncHandler<Object> bridge = handlerCaptor.getValue();
    assertThat(bridge, is(instanceOf(ProgressAsyncHandler.class)));
  }

  @Test
  public void callsSupplierForEachSubscription() {
    // given
    given(handlerSupplier.get()).willReturn(handler);
    final Maybe<Object> prepared = underTest.prepare(request, handlerSupplier);

    // when
    prepared.subscribe();
    prepared.subscribe();

    // then
    then(handlerSupplier).should(times(2)).get();
  }

  @Test
  public void cancelsResponseFutureOnDispose() throws Exception {
    given(handlerSupplier.get()).willReturn(handler);
    given(asyncHttpClient.executeRequest(eq(request), any())).willReturn(resposeFuture);

    /* when */
    underTest.prepare(request, handlerSupplier).subscribe().dispose();

    // then
    then(asyncHttpClient).should().executeRequest(eq(request), handlerCaptor.capture());
    final AsyncHandler<Object> bridge = handlerCaptor.getValue();
    then(resposeFuture).should().cancel(true);
    verifyZeroInteractions(handler);
    assertThat(bridge.onStatusReceived(null), is(AsyncHandler.State.ABORT));
    verify(handler).onThrowable(isA(DisposedException.class));
    verifyNoMoreInteractions(handler);
  }
}
