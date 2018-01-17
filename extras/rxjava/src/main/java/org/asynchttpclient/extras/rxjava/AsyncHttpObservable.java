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
 * Provide RxJava support for executing requests. Request can be subscribed to and manipulated as needed.
 *
 * @see <a href="https://github.com/ReactiveX/RxJava">https://github.com/ReactiveX/RxJava</a>
 */
public class AsyncHttpObservable {

  /**
   * Observe a request execution and emit the response to the observer.
   *
   * @param supplier the supplier
   * @return The cold observable (must be subscribed to in order to execute).
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
   * Observe a request execution and emit the response to the observer.
   *
   * @param supplier teh supplier
   * @return The hot observable (eagerly executes).
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
