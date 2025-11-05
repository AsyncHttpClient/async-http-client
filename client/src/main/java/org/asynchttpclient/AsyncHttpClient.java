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
 *
 */
package org.asynchttpclient;

import java.io.Closeable;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * This class support asynchronous and synchronous HTTP requests.
 * <br>
 * To execute a synchronous HTTP request, you just need to do
 * <blockquote><pre>
 *    AsyncHttpClient c = new AsyncHttpClient();
 *    Future&lt;Response&gt; f = c.prepareGet(TARGET_URL).execute();
 * </pre></blockquote>
 * <br>
 * The code above will block until the response is fully received. To execute an asynchronous HTTP request, you
 * create an {@link AsyncHandler} or its abstract implementation, {@link AsyncCompletionHandler}
 * <br>
 * <blockquote><pre>
 *       AsyncHttpClient c = new AsyncHttpClient();
 *       Future&lt;Response&gt; f = c.prepareGet(TARGET_URL).execute(new AsyncCompletionHandler&lt;Response&gt;() &#123;
 *
 *          &#64;Override
 *          public Response onCompleted(Response response) throws IOException &#123;
 *               // Do something
 *              return response;
 *          &#125;
 *
 *          &#64;Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 *      Response response = f.get();
 *
 *      // We are just interested in retrieving the status code.
 *     Future&lt;Integer&gt; f = c.prepareGet(TARGET_URL).execute(new AsyncCompletionHandler&lt;Integer&gt;() &#123;
 *
 *          &#64;Override
 *          public Integer onCompleted(Response response) throws IOException &#123;
 *               // Do something
 *              return response.getStatusCode();
 *          &#125;
 *
 *          &#64;Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 *      Integer statusCode = f.get();
 * </pre></blockquote>
 * The {@link AsyncCompletionHandler#onCompleted(Response)} method will be invoked once the http response has been fully read.
 * The {@link Response} object includes the http headers and the response body. Note that the entire response will be buffered in memory.
 * <br>
 * You can also have more control about the how the response is asynchronously processed by using an {@link AsyncHandler}
 * <blockquote><pre>
 *      AsyncHttpClient c = new AsyncHttpClient();
 *      Future&lt;String&gt; f = c.prepareGet(TARGET_URL).execute(new AsyncHandler&lt;String&gt;() &#123;
 *          private StringBuilder builder = new StringBuilder();
 *
 *          &#64;Override
 *          public STATE onStatusReceived(HttpResponseStatus s) throws Exception &#123;
 *               // return STATE.CONTINUE or STATE.ABORT
 *               return STATE.CONTINUE
 *          }
 *
 *          &#64;Override
 *          public STATE onHeadersReceived(HttpResponseHeaders bodyPart) throws Exception &#123;
 *               // return STATE.CONTINUE or STATE.ABORT
 *               return STATE.CONTINUE
 *
 *          }
 *          &#64;Override
 *
 *          public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception &#123;
 *               builder.append(new String(bodyPart));
 *               // return STATE.CONTINUE or STATE.ABORT
 *               return STATE.CONTINUE
 *          &#125;
 *
 *          &#64;Override
 *          public String onCompleted() throws Exception &#123;
 *               // Will be invoked once the response has been fully read or a ResponseComplete exception
 *               // has been thrown.
 *               return builder.toString();
 *          &#125;
 *
 *          &#64;Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 *
 *      String bodyResponse = f.get();
 * </pre></blockquote>
 * You can asynchronously process the response status, headers and body and decide when to
 * stop processing the response by returning a new {@link AsyncHandler.State#ABORT} at any moment.
 *
 * This class can also be used without the need of {@link AsyncHandler}.
 * <br>
 * <blockquote><pre>
 *      AsyncHttpClient c = new AsyncHttpClient();
 *      Future&lt;Response&gt; f = c.prepareGet(TARGET_URL).execute();
 *      Response r = f.get();
 * </pre></blockquote>
 *
 * Finally, you can configure the AsyncHttpClient using an {@link DefaultAsyncHttpClientConfig} instance.
 * <br>
 * <blockquote><pre>
 *      AsyncHttpClient c = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(...).build());
 *      Future&lt;Response&gt; f = c.prepareGet(TARGET_URL).execute();
 *      Response r = f.get();
 * </pre></blockquote>
 * <br>
 * An instance of this class will cache every HTTP 1.1 connection and close them when the {@link DefaultAsyncHttpClientConfig#getReadTimeout()}
 * expires. This object can hold many persistent connections to different hosts.
 */
public interface AsyncHttpClient extends Closeable {

  /**
   * Checks if this client has been closed.
   *
   * @return true if the client has been closed, false otherwise
   */
  boolean isClosed();

  /**
   * Sets the default signature calculator to use for all requests built by this client instance.
   * The signature calculator is used to sign requests for authentication purposes.
   *
   * @param signatureCalculator the signature calculator to use for request signing
   * @return this AsyncHttpClient instance for method chaining
   */
  AsyncHttpClient setSignatureCalculator(SignatureCalculator signatureCalculator);

  /**
   * Prepares an HTTP request with the specified method and URL.
   *
   * @param method the HTTP request method (e.g., "GET", "POST"). MUST be in uppercase
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder prepare(String method, String url);


  /**
   * Prepares an HTTP GET request for the specified URL.
   *
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder prepareGet(String url);

  /**
   * Prepares an HTTP CONNECT request for the specified URL.
   *
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder prepareConnect(String url);

  /**
   * Prepares an HTTP OPTIONS request for the specified URL.
   *
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder prepareOptions(String url);

  /**
   * Prepares an HTTP HEAD request for the specified URL.
   *
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder prepareHead(String url);

  /**
   * Prepares an HTTP POST request for the specified URL.
   *
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder preparePost(String url);

  /**
   * Prepares an HTTP PUT request for the specified URL.
   *
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder preparePut(String url);

  /**
   * Prepares an HTTP DELETE request for the specified URL.
   *
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder prepareDelete(String url);

  /**
   * Prepares an HTTP PATCH request for the specified URL.
   *
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder preparePatch(String url);

  /**
   * Prepares an HTTP TRACE request for the specified URL.
   *
   * @param url a well-formed URL string
   * @return a {@link BoundRequestBuilder} for configuring and executing the request
   */
  BoundRequestBuilder prepareTrace(String url);

  /**
   * Prepares a request using an existing {@link Request} instance as a template.
   * This allows modification of an existing request before execution.
   *
   * @param request the request to use as a template
   * @return a {@link BoundRequestBuilder} for further configuring and executing the request
   */
  BoundRequestBuilder prepareRequest(Request request);

  /**
   * Prepares a request using an existing {@link RequestBuilder} instance.
   * This allows binding a request builder to this client for execution.
   *
   * @param requestBuilder the request builder containing the request configuration
   * @return a {@link BoundRequestBuilder} for further configuring and executing the request
   */
  BoundRequestBuilder prepareRequest(RequestBuilder requestBuilder);

  /**
   * Executes an HTTP request asynchronously with a custom response handler.
   * The handler processes the response as it arrives (status, headers, body parts).
   *
   * @param request the HTTP request to execute
   * @param handler the async handler to process the response
   * @param <T>     the type of value returned by the handler's onCompleted method
   * @return a {@link ListenableFuture} that will contain the result of type T
   */
  <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler);

  /**
   * Executes an HTTP request asynchronously with a custom response handler.
   * The request is built from the provided RequestBuilder before execution.
   *
   * @param requestBuilder the request builder containing the request configuration
   * @param handler        the async handler to process the response
   * @param <T>            the type of value returned by the handler's onCompleted method
   * @return a {@link ListenableFuture} that will contain the result of type T
   */
  <T> ListenableFuture<T> executeRequest(RequestBuilder requestBuilder, AsyncHandler<T> handler);

  /**
   * Executes an HTTP request asynchronously and returns the complete response.
   * The entire response body will be buffered in memory.
   *
   * @param request the HTTP request to execute
   * @return a {@link ListenableFuture} containing the complete {@link Response}
   */
  ListenableFuture<Response> executeRequest(Request request);

  /**
   * Executes an HTTP request asynchronously and returns the complete response.
   * The request is built from the provided RequestBuilder before execution.
   * The entire response body will be buffered in memory.
   *
   * @param requestBuilder the request builder containing the request configuration
   * @return a {@link ListenableFuture} containing the complete {@link Response}
   */
  ListenableFuture<Response> executeRequest(RequestBuilder requestBuilder);

  /**
   * Returns statistics about the pooled connections managed by this client.
   * This includes information about open connections, idle connections, and connection counts.
   *
   * @return a {@link ClientStats} object containing connection pool statistics
   */
  ClientStats getClientStats();

  /**
   * Flushes (removes) channel pool partitions that match the given predicate.
   * This is useful for selectively clearing connections based on custom criteria.
   *
   * @param predicate the predicate to test each partition key; partitions matching will be flushed
   */
  void flushChannelPoolPartitions(Predicate<Object> predicate);

  /**
   * Returns the configuration associated with this client.
   * The configuration contains settings such as timeouts, connection pool parameters,
   * and other behavioral options.
   *
   * @return the {@link AsyncHttpClientConfig} used by this client
   */
  AsyncHttpClientConfig getConfig();
}
