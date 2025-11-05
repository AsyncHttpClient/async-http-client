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

/**
 * A {@link RequestBuilder} that is bound to an {@link AsyncHttpClient} instance.
 * <p>
 * This builder combines request configuration with immediate execution capabilities.
 * Unlike {@link RequestBuilder}, this class is tied to a specific client and can
 * execute requests directly via the {@link #execute()} and {@link #execute(AsyncHandler)} methods.
 * </p>
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 *
 * // Direct execution with default handler
 * Future<Response> future = client.prepareGet("http://example.com")
 *     .setHeader("Accept", "application/json")
 *     .execute();
 * Response response = future.get();
 *
 * // Execution with custom handler
 * Future<String> bodyFuture = client.preparePost("http://example.com/api")
 *     .setBody("{\"key\":\"value\"}")
 *     .execute(new AsyncCompletionHandler<String>() {
 *         @Override
 *         public String onCompleted(Response response) throws Exception {
 *             return response.getResponseBody();
 *         }
 *     });
 * }</pre>
 *
 * @see AsyncHttpClient
 * @see RequestBuilder
 */
public class BoundRequestBuilder extends RequestBuilderBase<BoundRequestBuilder> {

  private final AsyncHttpClient client;

  /**
   * Constructs a BoundRequestBuilder with URL encoding and header validation options.
   *
   * @param client the client instance this builder is bound to
   * @param method the HTTP method
   * @param isDisableUrlEncoding whether to disable URL encoding
   * @param validateHeaders whether to validate header names and values
   */
  public BoundRequestBuilder(AsyncHttpClient client, String method, boolean isDisableUrlEncoding, boolean validateHeaders) {
    super(method, isDisableUrlEncoding, validateHeaders);
    this.client = client;
  }

  /**
   * Constructs a BoundRequestBuilder with URL encoding option.
   *
   * @param client the client instance this builder is bound to
   * @param method the HTTP method
   * @param isDisableUrlEncoding whether to disable URL encoding
   */
  public BoundRequestBuilder(AsyncHttpClient client, String method, boolean isDisableUrlEncoding) {
    super(method, isDisableUrlEncoding);
    this.client = client;
  }

  /**
   * Constructs a BoundRequestBuilder from an existing request.
   *
   * @param client the client instance this builder is bound to
   * @param prototype the request to use as a template
   */
  public BoundRequestBuilder(AsyncHttpClient client, Request prototype) {
    super(prototype);
    this.client = client;
  }

  /**
   * Executes the request asynchronously with a custom response handler.
   *
   * @param handler the async handler to process the response
   * @param <T> the type of value returned by the handler
   * @return a {@link ListenableFuture} that will contain the result
   */
  public <T> ListenableFuture<T> execute(AsyncHandler<T> handler) {
    return client.executeRequest(build(), handler);
  }

  /**
   * Executes the request asynchronously and returns the complete response.
   * The response body will be fully buffered in memory.
   *
   * @return a {@link ListenableFuture} containing the complete {@link Response}
   */
  public ListenableFuture<Response> execute() {
    return client.executeRequest(build(), new AsyncCompletionHandlerBase());
  }
}
