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
package org.asynchttpclient;

import java.io.Closeable;
import java.io.IOException;

/**
 * Interface to be used when implementing custom asynchronous I/O HTTP client.
 */
public interface AsyncHttpProvider extends Closeable {

    /**
     * Execute the request and invoke the {@link AsyncHandler} when the response arrive.
     *
     * @param handler an instance of {@link AsyncHandler}
     * @return a {@link ListenableFuture} of Type T.
     * @throws IOException
     */
    <T> ListenableFuture<T> execute(Request request, AsyncHandler<T> handler) throws IOException;

    /**
     * Close the current underlying TCP/HTTP connection.
     */
    void close();
}
