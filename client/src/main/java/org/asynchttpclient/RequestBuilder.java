/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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

import static org.asynchttpclient.util.HttpConstants.Methods.GET;

/**
 * Builder for constructing {@link Request} instances.
 * <p>
 * <b>Warning:</b> This class is mutable and NOT thread-safe. Do not share instances across threads.
 * </p>
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * Request request = new RequestBuilder()
 *     .setUrl("https://api.example.com/users")
 *     .setMethod("POST")
 *     .setHeader("Content-Type", "application/json")
 *     .setHeader("Authorization", "Bearer token123")
 *     .setBody("{\"name\":\"John Doe\"}")
 *     .setRequestTimeout(5000)
 *     .build();
 *
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 * Future<Response> future = client.executeRequest(request);
 * Response response = future.get();
 * }</pre>
 *
 * @see Request
 * @see BoundRequestBuilder
 */
public class RequestBuilder extends RequestBuilderBase<RequestBuilder> {

  public RequestBuilder() {
    this(GET);
  }

  public RequestBuilder(String method) {
    this(method, false);
  }

  public RequestBuilder(String method, boolean disableUrlEncoding) {
    super(method, disableUrlEncoding);
  }

  public RequestBuilder(String method, boolean disableUrlEncoding, boolean validateHeaders) {
    super(method, disableUrlEncoding, validateHeaders);
  }

  /**
   * @deprecated Use request.toBuilder() instead
   */
  @Deprecated
  public RequestBuilder(Request prototype) {
    super(prototype);
  }

  @Deprecated
  public RequestBuilder(Request prototype, boolean disableUrlEncoding, boolean validateHeaders) {
    super(prototype, disableUrlEncoding, validateHeaders);
  }
}
