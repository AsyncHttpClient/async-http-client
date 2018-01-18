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
package org.asynchttpclient.extras.rxjava2.maybe;

import io.netty.handler.codec.http.HttpHeaders;
import io.reactivex.MaybeEmitter;
import io.reactivex.exceptions.CompositeException;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.extras.rxjava2.DisposedException;
import org.mockito.*;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.BDDMockito.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class AbstractMaybeAsyncHandlerBridgeTest {

  @Mock
  MaybeEmitter<Object> emitter;

  @Mock
  AsyncHandler<Object> delegate;

  @Mock
  private HttpResponseStatus status;

  @Mock
  private HttpHeaders headers;

  @Mock
  private HttpResponseBodyPart bodyPart;

  @Captor
  private ArgumentCaptor<Throwable> throwable;

  private AbstractMaybeAsyncHandlerBridge<Object> underTest;

  private static <T> Callable<T> named(String name, Callable<T> callable) {
    return new Callable<T>() {
      @Override
      public String toString() {
        return name;
      }

      @Override
      public T call() throws Exception {
        return callable.call();
      }
    };
  }

  @BeforeMethod
  public void initializeTest() {
    MockitoAnnotations.initMocks(this);
    underTest = new UnderTest();
  }

  @Test
  public void forwardsEvents() throws Exception {
    given(delegate.onCompleted()).willReturn(this);

    /* when */
    underTest.onStatusReceived(status);
    then(delegate).should().onStatusReceived(status);

    /* when */
    underTest.onHeadersReceived(headers);
    then(delegate).should().onHeadersReceived(headers);

    /* when */
    underTest.onBodyPartReceived(bodyPart);
    /* when */
    underTest.onBodyPartReceived(bodyPart);
    then(delegate).should(times(2)).onBodyPartReceived(bodyPart);

    /* when */
    underTest.onTrailingHeadersReceived(headers);
    then(delegate).should().onTrailingHeadersReceived(headers);

    /* when */
    underTest.onCompleted();
    then(delegate).should().onCompleted();
    then(emitter).should().onSuccess(this);
    /* then */
    verifyNoMoreInteractions(delegate);
  }

  @Test
  public void wontCallOnCompleteTwice() {
    InOrder inOrder = Mockito.inOrder(emitter);

    /* when */
    underTest.onCompleted();
    /* then */
    inOrder.verify(emitter).onComplete();

    /* when */
    underTest.onCompleted();
    /* then */
    inOrder.verify(emitter, never()).onComplete();
  }

  @Test
  public void wontCallOnErrorTwice() {
    InOrder inOrder = Mockito.inOrder(emitter);

    /* when */
    underTest.onThrowable(null);
    /* then */
    inOrder.verify(emitter).onError(null);

    /* when */
    underTest.onThrowable(new RuntimeException("unwanted"));
    /* then */
    inOrder.verify(emitter, never()).onError(any());
  }

  @Test
  public void wontCallOnErrorAfterOnComplete() {
    /* when */
    underTest.onCompleted();
    then(emitter).should().onComplete();

    /* when */
    underTest.onThrowable(null);
    then(emitter).should(never()).onError(any());
  }

  @Test
  public void wontCallOnCompleteAfterOnError() {
    /* when */
    underTest.onThrowable(null);
    then(emitter).should().onError(null);

    /* when */
    underTest.onCompleted();
    then(emitter).should(never()).onComplete();
  }

  @Test
  public void wontCallOnCompleteAfterDisposal() {
    given(emitter.isDisposed()).willReturn(true);
    /* when */
    underTest.onCompleted();
    /* then */
    verify(emitter, never()).onComplete();
  }

  @Test
  public void wontCallOnErrorAfterDisposal() {
    given(emitter.isDisposed()).willReturn(true);
    /* when */
    underTest.onThrowable(new RuntimeException("ignored"));
    /* then */
    verify(emitter, never()).onError(any());
  }

  @Test
  public void handlesExceptionsWhileCompleting() throws Exception {
    /* given */
    final Throwable x = new RuntimeException("mocked error in delegate onCompleted()");
    given(delegate.onCompleted()).willThrow(x);
    /* when */
    underTest.onCompleted();
    then(emitter).should().onError(x);
  }

  @Test
  public void handlesExceptionsWhileFailing() {
    // given
    final Throwable initial = new RuntimeException("mocked error for onThrowable()");
    final Throwable followup = new RuntimeException("mocked error in delegate onThrowable()");
    willThrow(followup).given(delegate).onThrowable(initial);

    /* when */
    underTest.onThrowable(initial);

    // then
    then(emitter).should().onError(throwable.capture());
    final Throwable thrown = throwable.getValue();
    assertThat(thrown, is(instanceOf(CompositeException.class)));
    assertThat(((CompositeException) thrown).getExceptions(), is(Arrays.asList(initial, followup)));
  }

  @Test
  public void cachesDisposedException() {
    // when
    new UnderTest().disposed();
    new UnderTest().disposed();

    // then
    then(delegate).should(times(2)).onThrowable(throwable.capture());
    final List<Throwable> errors = throwable.getAllValues();
    final Throwable firstError = errors.get(0), secondError = errors.get(1);
    assertThat(secondError, is(sameInstance(firstError)));
    final StackTraceElement[] stackTrace = firstError.getStackTrace();
    assertThat(stackTrace.length, is(1));
    assertThat(stackTrace[0].getClassName(), is(AbstractMaybeAsyncHandlerBridge.class.getName()));
    assertThat(stackTrace[0].getMethodName(), is("disposed"));
  }

  @DataProvider
  public Object[][] httpEvents() {
    return new Object[][]{
            {named("onStatusReceived", () -> underTest.onStatusReceived(status))},
            {named("onHeadersReceived", () -> underTest.onHeadersReceived(headers))},
            {named("onBodyPartReceived", () -> underTest.onBodyPartReceived(bodyPart))},
            {named("onTrailingHeadersReceived", () -> underTest.onTrailingHeadersReceived(headers))},
    };
  }

  @Test(dataProvider = "httpEvents")
  public void httpEventCallbacksCheckDisposal(Callable<AsyncHandler.State> httpEvent) throws Exception {
    given(emitter.isDisposed()).willReturn(true);

    /* when */
    final AsyncHandler.State firstState = httpEvent.call();
    /* then */
    assertThat(firstState, is(State.ABORT));
    then(delegate).should(only()).onThrowable(isA(DisposedException.class));

    /* when */
    final AsyncHandler.State secondState = httpEvent.call();
    /* then */
    assertThat(secondState, is(State.ABORT));
    /* then */
    verifyNoMoreInteractions(delegate);
  }

  private final class UnderTest extends AbstractMaybeAsyncHandlerBridge<Object> {
    UnderTest() {
      super(AbstractMaybeAsyncHandlerBridgeTest.this.emitter);
    }

    @Override
    protected AsyncHandler<?> delegate() {
      return delegate;
    }
  }
}
