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

import org.asynchttpclient.AsyncHandler;
import rx.SingleSubscriber;

import static java.util.Objects.requireNonNull;

final class AsyncSingleSubscriberBridge<T> extends AbstractSingleSubscriberBridge<T> {

  private final AsyncHandler<? extends T> delegate;

  public AsyncSingleSubscriberBridge(SingleSubscriber<T> subscriber, AsyncHandler<? extends T> delegate) {
    super(subscriber);
    this.delegate = requireNonNull(delegate);
  }

  @Override
  protected AsyncHandler<? extends T> delegate() {
    return delegate;
  }

}
