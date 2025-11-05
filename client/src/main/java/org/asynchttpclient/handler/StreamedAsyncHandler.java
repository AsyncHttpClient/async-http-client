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
package org.asynchttpclient.handler;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.reactivestreams.Publisher;

/**
 * An {@link AsyncHandler} that uses reactive streams to handle HTTP response bodies.
 * <p>
 * This interface extends {@link AsyncHandler} to provide streaming capabilities through
 * the Reactive Streams API. Instead of receiving body parts one at a time through
 * {@link AsyncHandler#onBodyPartReceived}, implementations receive a {@link Publisher}
 * that produces body parts, enabling backpressure-aware consumption of the response body.
 * <p>
 * The {@link #onStream} method is invoked once when the response body begins streaming.
 * It may not be called if the response has no body.
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * StreamedAsyncHandler<Response> handler = new StreamedAsyncHandler<Response>() {
 *     @Override
 *     public State onStream(Publisher<HttpResponseBodyPart> publisher) {
 *         publisher.subscribe(new Subscriber<HttpResponseBodyPart>() {
 *             private Subscription subscription;
 *
 *             @Override
 *             public void onSubscribe(Subscription s) {
 *                 this.subscription = s;
 *                 s.request(1); // Request first item
 *             }
 *
 *             @Override
 *             public void onNext(HttpResponseBodyPart bodyPart) {
 *                 // Process body part
 *                 subscription.request(1); // Request next item
 *             }
 *
 *             @Override
 *             public void onError(Throwable t) {
 *                 // Handle error
 *             }
 *
 *             @Override
 *             public void onComplete() {
 *                 // Body streaming complete
 *             }
 *         });
 *         return State.CONTINUE;
 *     }
 *
 *     // Implement other AsyncHandler methods...
 * };
 * }</pre>
 *
 * @param <T> the response type
 */
public interface StreamedAsyncHandler<T> extends AsyncHandler<T> {

  /**
   * Invoked when the response body begins streaming.
   * <p>
   * This method provides a {@link Publisher} of {@link HttpResponseBodyPart} objects
   * that can be consumed using the Reactive Streams API. The publisher will emit body
   * parts as they arrive from the server, allowing for backpressure-aware processing.
   * <p>
   * This method may not be called if the response has no body (e.g., HEAD requests
   * or 204 No Content responses).
   *
   * @param publisher the publisher that will emit response body parts
   * @return {@link State#CONTINUE} to proceed with processing, or {@link State#ABORT} to cancel the request
   */
  State onStream(Publisher<HttpResponseBodyPart> publisher);
}
