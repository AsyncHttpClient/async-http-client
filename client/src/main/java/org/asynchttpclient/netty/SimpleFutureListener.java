/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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
