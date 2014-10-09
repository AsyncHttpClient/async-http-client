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

/**
 * Interface to be used when implementing custom asynchronous I/O HTTP client.
 * By default, the {@link com.ning.http.client.providers.netty.NettyAsyncHttpProvider} is used.
 */
public interface AsyncHttpProvider {

    /**
     * Execute the request and invoke the {@link AsyncHandler} when the response arrive.
     *
     * @param handler an instance of {@link AsyncHandler}
     * @return a {@link ListenableFuture} of Type T.
     */
    <T> ListenableFuture<T> execute(Request request, AsyncHandler<T> handler);

    /**
     * Close the current underlying TCP/HTTP connection.
     */
    void close();
}
