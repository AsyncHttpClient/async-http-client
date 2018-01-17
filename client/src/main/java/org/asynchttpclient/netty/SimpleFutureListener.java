/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

public abstract class SimpleFutureListener<V> implements FutureListener<V> {

  @Override
  public final void operationComplete(Future<V> future) throws Exception {
    if (future.isSuccess()) {
      onSuccess(future.getNow());
    } else {
      onFailure(future.cause());
    }
  }

  protected abstract void onSuccess(V value) throws Exception;

  protected abstract void onFailure(Throwable t) throws Exception;
}
