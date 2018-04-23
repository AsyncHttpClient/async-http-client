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

import org.asynchttpclient.handler.ProgressAsyncHandler;
import rx.SingleSubscriber;

import static java.util.Objects.requireNonNull;

final class ProgressAsyncSingleSubscriberBridge<T> extends AbstractProgressSingleSubscriberBridge<T> {

  private final ProgressAsyncHandler<? extends T> delegate;

  public ProgressAsyncSingleSubscriberBridge(SingleSubscriber<T> subscriber, ProgressAsyncHandler<? extends T> delegate) {
    super(subscriber);
    this.delegate = requireNonNull(delegate);
  }

  @Override
  protected ProgressAsyncHandler<? extends T> delegate() {
    return delegate;
  }

}
