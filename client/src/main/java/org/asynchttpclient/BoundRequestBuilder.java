/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

public class BoundRequestBuilder extends RequestBuilderBase<BoundRequestBuilder> {

    private final AsyncHttpClient client;

    public BoundRequestBuilder(AsyncHttpClient client, String method, boolean isDisableUrlEncoding, boolean validateHeaders) {
        super(method, isDisableUrlEncoding, validateHeaders);
        this.client = client;
    }

    public BoundRequestBuilder(AsyncHttpClient client, String method, boolean isDisableUrlEncoding) {
        super(method, isDisableUrlEncoding);
        this.client = client;
    }

    public BoundRequestBuilder(AsyncHttpClient client, Request prototype) {
        super(prototype);
        this.client = client;
    }

    public <T> ListenableFuture<T> execute(AsyncHandler<T> handler) {
        return client.executeRequest(build(), handler);
    }

    public ListenableFuture<Response> execute() {
        return client.executeRequest(build(), new AsyncCompletionHandlerBase());
    }
}
