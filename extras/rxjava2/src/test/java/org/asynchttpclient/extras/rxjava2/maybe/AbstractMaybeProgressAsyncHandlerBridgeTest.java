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

import io.reactivex.MaybeEmitter;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.extras.rxjava2.DisposedException;
import org.asynchttpclient.handler.ProgressAsyncHandler;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.concurrent.Callable;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class AbstractMaybeProgressAsyncHandlerBridgeTest {

  @Mock
  MaybeEmitter<Object> emitter;

  @Mock
  ProgressAsyncHandler<? extends Object> delegate;

  private AbstractMaybeProgressAsyncHandlerBridge<Object> underTest;

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
  public void forwardsEvents() {
    /* when */
    underTest.onHeadersWritten();
    then(delegate).should().onHeadersWritten();

    /* when */
    underTest.onContentWriteProgress(40, 60, 100);
    then(delegate).should().onContentWriteProgress(40, 60, 100);

    /* when */
    underTest.onContentWritten();
    then(delegate).should().onContentWritten();
  }

  @DataProvider
  public Object[][] httpEvents() {
    return new Object[][]{
            {named("onHeadersWritten", () -> underTest.onHeadersWritten())},
            {named("onContentWriteProgress", () -> underTest.onContentWriteProgress(40, 60, 100))},
            {named("onContentWritten", () -> underTest.onContentWritten())},
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

  private final class UnderTest extends AbstractMaybeProgressAsyncHandlerBridge<Object> {
    UnderTest() {
      super(AbstractMaybeProgressAsyncHandlerBridgeTest.this.emitter);
    }

    @Override
    protected ProgressAsyncHandler<?> delegate() {
      return delegate;
    }
  }
}
