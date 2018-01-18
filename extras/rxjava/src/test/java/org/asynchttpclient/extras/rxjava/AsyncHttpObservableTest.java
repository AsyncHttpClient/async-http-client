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
package org.asynchttpclient.extras.rxjava;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.testng.annotations.Test;
import rx.Observable;
import rx.observers.TestSubscriber;

import java.util.List;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class AsyncHttpObservableTest {

  @Test
  public void testToObservableNoError() {
    final TestSubscriber<Response> tester = new TestSubscriber<>();

    try (AsyncHttpClient client = asyncHttpClient()) {
      Observable<Response> o1 = AsyncHttpObservable.toObservable(() -> client.prepareGet("https://gatling.io"));
      o1.subscribe(tester);
      tester.awaitTerminalEvent();
      tester.assertTerminalEvent();
      tester.assertNoErrors();
      tester.assertCompleted();
      List<Response> responses = tester.getOnNextEvents();
      assertNotNull(responses);
      assertEquals(responses.size(), 1);
      assertEquals(responses.get(0).getStatusCode(), 200);
    } catch (Exception e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  public void testToObservableError() {
    final TestSubscriber<Response> tester = new TestSubscriber<>();

    try (AsyncHttpClient client = asyncHttpClient()) {
      Observable<Response> o1 = AsyncHttpObservable.toObservable(() -> client.prepareGet("https://gatling.io/ttfn"));
      o1.subscribe(tester);
      tester.awaitTerminalEvent();
      tester.assertTerminalEvent();
      tester.assertNoErrors();
      tester.assertCompleted();
      List<Response> responses = tester.getOnNextEvents();
      assertNotNull(responses);
      assertEquals(responses.size(), 1);
      assertEquals(responses.get(0).getStatusCode(), 404);
    } catch (Exception e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  public void testObserveNoError() {
    final TestSubscriber<Response> tester = new TestSubscriber<>();

    try (AsyncHttpClient client = asyncHttpClient()) {
      Observable<Response> o1 = AsyncHttpObservable.observe(() -> client.prepareGet("https://gatling.io"));
      o1.subscribe(tester);
      tester.awaitTerminalEvent();
      tester.assertTerminalEvent();
      tester.assertNoErrors();
      tester.assertCompleted();
      List<Response> responses = tester.getOnNextEvents();
      assertNotNull(responses);
      assertEquals(responses.size(), 1);
      assertEquals(responses.get(0).getStatusCode(), 200);
    } catch (Exception e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  public void testObserveError() {
    final TestSubscriber<Response> tester = new TestSubscriber<>();

    try (AsyncHttpClient client = asyncHttpClient()) {
      Observable<Response> o1 = AsyncHttpObservable.observe(() -> client.prepareGet("https://gatling.io/ttfn"));
      o1.subscribe(tester);
      tester.awaitTerminalEvent();
      tester.assertTerminalEvent();
      tester.assertNoErrors();
      tester.assertCompleted();
      List<Response> responses = tester.getOnNextEvents();
      assertNotNull(responses);
      assertEquals(responses.size(), 1);
      assertEquals(responses.get(0).getStatusCode(), 404);
    } catch (Exception e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  public void testObserveMultiple() {
    final TestSubscriber<Response> tester = new TestSubscriber<>();

    try (AsyncHttpClient client = asyncHttpClient()) {
      Observable<Response> o1 = AsyncHttpObservable.observe(() -> client.prepareGet("https://gatling.io"));
      Observable<Response> o2 = AsyncHttpObservable.observe(() -> client.prepareGet("http://www.wisc.edu").setFollowRedirect(true));
      Observable<Response> o3 = AsyncHttpObservable.observe(() -> client.prepareGet("http://www.umn.edu").setFollowRedirect(true));
      Observable<Response> all = Observable.merge(o1, o2, o3);
      all.subscribe(tester);
      tester.awaitTerminalEvent();
      tester.assertTerminalEvent();
      tester.assertNoErrors();
      tester.assertCompleted();
      List<Response> responses = tester.getOnNextEvents();
      assertNotNull(responses);
      assertEquals(responses.size(), 3);
      for (Response response : responses) {
        assertEquals(response.getStatusCode(), 200);
      }
    } catch (Exception e) {
      Thread.currentThread().interrupt();
    }
  }
}
