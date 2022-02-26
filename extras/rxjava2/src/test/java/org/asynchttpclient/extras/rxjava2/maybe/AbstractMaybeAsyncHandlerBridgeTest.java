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

import io.netty.channel.Channel;
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

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.BDDMockito.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.isA;

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

  private final String hostname = "service:8080";

  @Mock
  private InetSocketAddress remoteAddress;

  @Mock
  private Channel channel;

  @Mock
  private SSLSession sslSession;

  @Mock
  private Throwable error;

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

  private static Runnable named(String name, Runnable runnable) {
    return new Runnable() {
      @Override
      public String toString() {
        return name;
      }

      @Override
      public void run() {
        runnable.run();
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
    underTest.onHostnameResolutionAttempt(hostname);
    then(delegate).should().onHostnameResolutionAttempt(hostname);

    /* when */
    List<InetSocketAddress> remoteAddresses = Collections.singletonList(remoteAddress);
    underTest.onHostnameResolutionSuccess(hostname, remoteAddresses);
    then(delegate).should().onHostnameResolutionSuccess(hostname, remoteAddresses);

    /* when */
    underTest.onHostnameResolutionFailure(hostname, error);
    then(delegate).should().onHostnameResolutionFailure(hostname, error);

    /* when */
    underTest.onTcpConnectAttempt(remoteAddress);
    then(delegate).should().onTcpConnectAttempt(remoteAddress);

    /* when */
    underTest.onTcpConnectSuccess(remoteAddress, channel);
    then(delegate).should().onTcpConnectSuccess(remoteAddress, channel);

    /* when */
    underTest.onTcpConnectFailure(remoteAddress, error);
    then(delegate).should().onTcpConnectFailure(remoteAddress, error);

    /* when */
    underTest.onTlsHandshakeAttempt();
    then(delegate).should().onTlsHandshakeAttempt();

    /* when */
    underTest.onTlsHandshakeSuccess(sslSession);
    then(delegate).should().onTlsHandshakeSuccess(sslSession);

    /* when */
    underTest.onTlsHandshakeFailure(error);
    then(delegate).should().onTlsHandshakeFailure(error);

    /* when */
    underTest.onConnectionPoolAttempt();
    then(delegate).should().onConnectionPoolAttempt();

    /* when */
    underTest.onConnectionPooled(channel);
    then(delegate).should().onConnectionPooled(channel);

    /* when */
    underTest.onConnectionOffer(channel);
    then(delegate).should().onConnectionOffer(channel);

    /* when */
    underTest.onRequestSend(null);
    then(delegate).should().onRequestSend(null);

    /* when */
    underTest.onRetry();
    then(delegate).should().onRetry();

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

  @DataProvider
  public Object[][] variousEvents() {
    return new Object[][]{
            {named("onHostnameResolutionAttempt", () -> underTest.onHostnameResolutionAttempt("service:8080"))},
            {named("onHostnameResolutionSuccess", () -> underTest.onHostnameResolutionSuccess("service:8080",
                    Collections.singletonList(remoteAddress)))},
            {named("onHostnameResolutionFailure", () -> underTest.onHostnameResolutionFailure("service:8080", error))},
            {named("onTcpConnectAttempt", () -> underTest.onTcpConnectAttempt(remoteAddress))},
            {named("onTcpConnectSuccess", () -> underTest.onTcpConnectSuccess(remoteAddress, channel))},
            {named("onTcpConnectFailure", () -> underTest.onTcpConnectFailure(remoteAddress, error))},
            {named("onTlsHandshakeAttempt", () -> underTest.onTlsHandshakeAttempt())},
            {named("onTlsHandshakeSuccess", () -> underTest.onTlsHandshakeSuccess(sslSession))},
            {named("onTlsHandshakeFailure", () -> underTest.onTlsHandshakeFailure(error))},
            {named("onConnectionPoolAttempt", () -> underTest.onConnectionPoolAttempt())},
            {named("onConnectionPooled", () -> underTest.onConnectionPooled(channel))},
            {named("onConnectionOffer", () -> underTest.onConnectionOffer(channel))},
            {named("onRequestSend", () -> underTest.onRequestSend(null))},
            {named("onRetry", () -> underTest.onRetry())},
    };
  }

  @Test(dataProvider = "variousEvents")
  public void variousEventCallbacksCheckDisposal(Runnable event) {
    given(emitter.isDisposed()).willReturn(true);

    /* when */
    event.run();
    /* then */
    then(delegate).should(only()).onThrowable(isA(DisposedException.class));

    /* when */
    event.run();
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
