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

/**
 * Abstract bridge implementation that adapts ProgressAsyncHandler to RxJava SingleSubscriber.
 * <p>
 * This bridge extends the base subscriber bridge to support progress tracking callbacks
 * for content write operations during HTTP request execution.
 */
abstract class AbstractProgressSingleSubscriberBridge<T> extends AbstractSingleSubscriberBridge<T> implements ProgressAsyncHandler<Void> {

  /**
   * Creates a new progress-aware subscriber bridge.
   *
   * @param subscriber the RxJava SingleSubscriber to bridge to
   */
  protected AbstractProgressSingleSubscriberBridge(SingleSubscriber<T> subscriber) {
    super(subscriber);
  }

  /**
   * Called when request headers have been written.
   * <p>
   * Aborts the request if the subscriber has unsubscribed, otherwise delegates
   * to the underlying handler.
   *
   * @return the handler state indicating whether to continue or abort
   */
  @Override
  public State onHeadersWritten() {
    return subscriber.isUnsubscribed() ? abort() : delegate().onHeadersWritten();
  }

  /**
   * Called when request content has been written.
   * <p>
   * Aborts the request if the subscriber has unsubscribed, otherwise delegates
   * to the underlying handler.
   *
   * @return the handler state indicating whether to continue or abort
   */
  @Override
  public State onContentWritten() {
    return subscriber.isUnsubscribed() ? abort() : delegate().onContentWritten();
  }

  /**
   * Called to report progress of content write operations.
   * <p>
   * Aborts the request if the subscriber has unsubscribed, otherwise delegates
   * to the underlying handler.
   *
   * @param amount the amount of bytes written in this update
   * @param current the total number of bytes written so far
   * @param total the total number of bytes to write
   * @return the handler state indicating whether to continue or abort
   */
  @Override
  public State onContentWriteProgress(long amount, long current, long total) {
    return subscriber.isUnsubscribed() ? abort() : delegate().onContentWriteProgress(amount, current, total);
  }

  /**
   * Returns the underlying ProgressAsyncHandler to which operations are delegated.
   *
   * @return the delegate handler
   */
  @Override
  protected abstract ProgressAsyncHandler<? extends T> delegate();

}
