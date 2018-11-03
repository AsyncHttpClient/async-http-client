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
   * Return true if closed
   *
   * @return true if closed
   */
  boolean isClosed();

  /**
   * Set default signature calculator to use for requests built by this client instance
   *
   * @param signatureCalculator a signature calculator
   * @return {@link RequestBuilder}
   */
  AsyncHttpClient setSignatureCalculator(SignatureCalculator signatureCalculator);

  /**
   * Prepare an HTTP client request.
   *
   * @param method HTTP request method type. MUST BE in upper case
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder prepare(String method, String url);


  /**
   * Prepare an HTTP client GET request.
   *
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder prepareGet(String url);

  /**
   * Prepare an HTTP client CONNECT request.
   *
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder prepareConnect(String url);

  /**
   * Prepare an HTTP client OPTIONS request.
   *
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder prepareOptions(String url);

  /**
   * Prepare an HTTP client HEAD request.
   *
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder prepareHead(String url);

  /**
   * Prepare an HTTP client POST request.
   *
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder preparePost(String url);

  /**
   * Prepare an HTTP client PUT request.
   *
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder preparePut(String url);

  /**
   * Prepare an HTTP client DELETE request.
   *
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder prepareDelete(String url);

  /**
   * Prepare an HTTP client PATCH request.
   *
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder preparePatch(String url);

  /**
   * Prepare an HTTP client TRACE request.
   *
   * @param url A well formed URL.
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder prepareTrace(String url);

  /**
   * Construct a {@link RequestBuilder} using a {@link Request}
   *
   * @param request a {@link Request}
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder prepareRequest(Request request);

  /**
   * Construct a {@link RequestBuilder} using a {@link RequestBuilder}
   *
   * @param requestBuilder a {@link RequestBuilder}
   * @return {@link RequestBuilder}
   */
  BoundRequestBuilder prepareRequest(RequestBuilder requestBuilder);

  /**
   * Execute an HTTP request.
   *
   * @param request {@link Request}
   * @param handler an instance of {@link AsyncHandler}
   * @param <T>     Type of the value that will be returned by the associated {@link java.util.concurrent.Future}
   * @return a {@link Future} of type T
   */
  <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler);

  /**
   * Execute an HTTP request.
   *
   * @param requestBuilder {@link RequestBuilder}
   * @param handler        an instance of {@link AsyncHandler}
   * @param <T>            Type of the value that will be returned by the associated {@link java.util.concurrent.Future}
   * @return a {@link Future} of type T
   */
  <T> ListenableFuture<T> executeRequest(RequestBuilder requestBuilder, AsyncHandler<T> handler);

  /**
   * Execute an HTTP request.
   *
   * @param request {@link Request}
   * @return a {@link Future} of type Response
   */
  ListenableFuture<Response> executeRequest(Request request);

  /**
   * Execute an HTTP request.
   *
   * @param requestBuilder {@link RequestBuilder}
   * @return a {@link Future} of type Response
   */
  ListenableFuture<Response> executeRequest(RequestBuilder requestBuilder);

  /***
   * Return details about pooled connections.
   *
   * @return a {@link ClientStats}
   */
  ClientStats getClientStats();

  /**
   * Flush ChannelPool partitions based on a predicate
   *
   * @param predicate the predicate
   */
  void flushChannelPoolPartitions(Predicate<Object> predicate);

  /**
   * Return the config associated to this client.
   *
   * @return the config associated to this client.
   */
  AsyncHttpClientConfig getConfig();
}
