/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Extended {@link Future}
 * @param <V> Type of the value that will be returned.
 */
public interface FutureImpl<V> extends Future<V> {

    /**
     * Execute a {@link Callable}  and if there is no exception, mark this Future as done and release the internal lock.
     * @param callable
     */
    void done(Callable callable);

    /**
     * Abort the current processing, and propagate the {@link Throwable} to the {@link AsyncHandler} or {@link Future}
     * @param t
     */
    void abort(Throwable t);

    /**
     * Set the content that will be returned by this instance
     * @param v the content that will be returned by this instance
     */
    void content(V v);

    /**
     * Touch the current instance to prevent external service to times out.
     */
    void touch();
   
}
