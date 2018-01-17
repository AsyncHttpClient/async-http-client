/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
