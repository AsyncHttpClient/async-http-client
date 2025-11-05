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

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func0;
import rx.subjects.ReplaySubject;

/**
 * Provides RxJava support for executing HTTP requests as Observables.
 * <p>
 * This class enables integration between AsyncHttpClient and RxJava, allowing
 * HTTP requests to be observed and manipulated using reactive programming patterns.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = asyncHttpClient();
 *
 * // Cold Observable - executes when subscribed
 * Observable<Response> coldObservable = AsyncHttpObservable.toObservable(
 *     () -> client.prepareGet("http://www.example.com")
 * );
 * coldObservable.subscribe(response -> {
 *     System.out.println("Status: " + response.getStatusCode());
 * });
 *
 * // Hot Observable - executes immediately
 * Observable<Response> hotObservable = AsyncHttpObservable.observe(
 *     () -> client.prepareGet("http://www.example.com")
 * );
 * // Request starts executing immediately, even before subscription
 * hotObservable.subscribe(response -> {
 *     System.out.println("Status: " + response.getStatusCode());
 * });
 * }</pre>
 *
 * @see <a href="https://github.com/ReactiveX/RxJava">https://github.com/ReactiveX/RxJava</a>
 */
public class AsyncHttpObservable {

  /**
   * Creates a cold Observable that executes the HTTP request when subscribed.
   * <p>
   * Each subscription triggers a new HTTP request execution. The request is not
   * executed until the Observable is subscribed to.
   *
   * @param supplier a function that provides the BoundRequestBuilder for the HTTP request
   * @return a cold Observable that emits the HTTP response when subscribed
   */
  public static Observable<Response> toObservable(final Func0<BoundRequestBuilder> supplier) {

    //Get the builder from the function
    final BoundRequestBuilder builder = supplier.call();

    //create the observable from scratch
    return Observable.unsafeCreate(new Observable.OnSubscribe<Response>() {

      @Override
      public void call(final Subscriber<? super Response> subscriber) {
        try {
          AsyncCompletionHandler<Void> handler = new AsyncCompletionHandler<Void>() {

            @Override
            public Void onCompleted(Response response) throws Exception {
              subscriber.onNext(response);
              subscriber.onCompleted();
              return null;
            }

            @Override
            public void onThrowable(Throwable t) {
              subscriber.onError(t);
            }
          };
          //execute the request
          builder.execute(handler);
        } catch (Throwable t) {
          subscriber.onError(t);
        }
      }
    });
  }

  /**
   * Creates a hot Observable that executes the HTTP request immediately.
   * <p>
   * The request begins execution as soon as this method is called, regardless of
   * whether any observers have subscribed. Multiple subscribers will receive the
   * same response via a ReplaySubject.
   *
   * @param supplier a function that provides the BoundRequestBuilder for the HTTP request
   * @return a hot Observable that emits the HTTP response (request executes eagerly)
   */
  public static Observable<Response> observe(final Func0<BoundRequestBuilder> supplier) {
    //use a ReplaySubject to buffer the eagerly subscribed-to Observable
    ReplaySubject<Response> subject = ReplaySubject.create();
    //eagerly kick off subscription
    toObservable(supplier).subscribe(subject);
    //return the subject that can be subscribed to later while the execution has already started
    return subject;
  }
}
