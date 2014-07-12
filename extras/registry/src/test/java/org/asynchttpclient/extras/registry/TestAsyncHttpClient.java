/*
 * Copyright (c) 2010-2014 Sonatype, Inc. All rights reserved.
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

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;
import org.asynchttpclient.SignatureCalculator;

import java.io.IOException;

public class TestAsyncHttpClient implements AsyncHttpClient {

    public TestAsyncHttpClient() {
    }

    public TestAsyncHttpClient(AsyncHttpProvider provider) {
    }

    public TestAsyncHttpClient(AsyncHttpClientConfig config) {
    }

    public TestAsyncHttpClient(String providerClass, AsyncHttpClientConfig config) {
    }

    public TestAsyncHttpClient(AsyncHttpProvider httpProvider, AsyncHttpClientConfig config) {
    }

    @Override
    public AsyncHttpProvider getProvider() {
        return null;
    }

    @Override
    public void close() {
    }

    @Override
    public void closeAsynchronously() {
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public AsyncHttpClientConfig getConfig() {
        return null;
    }

    @Override
    public AsyncHttpClient setSignatureCalculator(SignatureCalculator signatureCalculator) {
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
    public <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler) throws IOException {
        return null;
    }

    @Override
    public ListenableFuture<Response> executeRequest(Request request) throws IOException {
        return null;
    }

}
