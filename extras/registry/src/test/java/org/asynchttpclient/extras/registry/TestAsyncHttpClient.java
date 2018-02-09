/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.registry;

import org.asynchttpclient.*;

import java.util.function.Predicate;

public class TestAsyncHttpClient implements AsyncHttpClient {

  public TestAsyncHttpClient() {
  }

  public TestAsyncHttpClient(AsyncHttpClientConfig config) {
  }

  public TestAsyncHttpClient(String providerClass, AsyncHttpClientConfig config) {
  }

  @Override
  public void close() {
  }

  @Override
  public boolean isClosed() {
    return false;
  }

  @Override
  public AsyncHttpClient setSignatureCalculator(SignatureCalculator signatureCalculator) {
    return null;
  }

  @Override
  public BoundRequestBuilder prepare(String method, String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder prepareGet(String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder prepareConnect(String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder prepareOptions(String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder prepareHead(String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder preparePost(String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder preparePut(String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder prepareDelete(String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder preparePatch(String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder prepareTrace(String url) {
    return null;
  }

  @Override
  public BoundRequestBuilder prepareRequest(Request request) {
    return null;
  }

  @Override
  public <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler) {
    return null;
  }

  @Override
  public ListenableFuture<Response> executeRequest(Request request) {
    return null;
  }

  @Override
  public BoundRequestBuilder prepareRequest(RequestBuilder requestBuilder) {
    return null;
  }

  @Override
  public <T> ListenableFuture<T> executeRequest(RequestBuilder requestBuilder, AsyncHandler<T> handler) {
    return null;
  }

  @Override
  public ListenableFuture<Response> executeRequest(RequestBuilder requestBuilder) {
    return null;
  }

  @Override
  public ClientStats getClientStats() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void flushChannelPoolPartitions(Predicate<Object> predicate) {
    throw new UnsupportedOperationException();
  }

  @Override
  public AsyncHttpClientConfig getConfig() {
    return null;
  }
}
