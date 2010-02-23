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
 *
 */
package com.ning.http.client;

import com.ning.http.client.providers.NettyAsyncHttpProvider;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * This class support asynchronous and synchronous HTTP request.
 *
 * To execute synchronous HTTP request, you just need to do
 * {@code
 *    AsyncHttpClient c = new AsyncHttpClient();
 *    Future<Response> f = c.doGet("http://www.ning.com/").get();
 * }
 *
 * The code above will block until the response is fully received. To execute asynchronous HTTP request, you
 * create an {@link AsyncHandler}
 *
 * {@code
 *       AsyncHttpClient c = new AsyncHttpClient();
 *       Future<Response> f = c.prepareGet("http://www.ning.com/").execute(new AsyncHandler<Response>() &#123;
 *
 *          @Override
 *          public Response onCompleted(Response response) throws IOException &#123;
 *               // Do something
 *              return response;
 *          &#125;
 *
 *          @Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 *      Response response = f.get();
 *
 *      // We are just interested to retrieve the status code.
 *     Future<Integer> f = c.prepareGet("http://www.ning.com/").execute(new AsyncHandler<Integer>() &#123;
 *
 *          @Override
 *          public Integer onCompleted(Response response) throws IOException &#123;
 *               // Do something
 *              return response.getStatusCode();
 *          &#125;
 *
 *          @Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 *      Integer statusCode = f.get();
 * }
 * The {@link AsyncHandler#onCompleted(com.ning.http.client.Response)} will be invoked once the http response has been fully read, which include
 * the http headers and the response body. Note that the entire response will be buffered in memory.
 * 
 * You can also have more control about the how the response is asynchronously processed by using a {@link AsyncStreamingHandler}
 * {@code
 *      AsyncHttpClient c = new AsyncHttpClient();
 *      Future<Response> f = c.prepareGet("http://www.ning.com/").execute(new AsyncStreamingHandler() &#123;
 *
 *          @Override
 *          public Response onContentReceived(HttpContent content) throws ResponseComplete &#123;
 *              if (content instanceof HttpResponseHeaders) &#123;
 *                  // The headers have been read
 *                  // If you don't want to read the body, or stop processing the response
 *                  throw new ResponseComplete();
 *              &#125; else if (content instanceof HttpResponseBody) &#123;
 *                  HttpResponseBody b = (HttpResponseBody) content;
 *                  // Do something with the body. It may not been fully read yet.
 *                  if (b.isComplete()) &#123;
 *                      // The full response has been read.
 *                  &#125;
 *              &#125;
 *              return content.getResponse();
 *          &#125;
 *
 *          @Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 *      Response response = f.get();
 * }
 * From an {@link HttpContent}, you can asynchronously process the response headers and body and decide when to
 * stop the processing the response by throwing {@link AsyncStreamingHandler.ResponseComplete} at any moment. The returned
 * {@link Response} will be incomplete until {@link HttpResponseBody#isComplete()} return true, which means the
 * response has been fully read and buffered in memory.
 *
 * This class can also be used without the need of {@link AsyncHandler}</p>
 * {@code
 *      AsyncHttpClient c = new AsyncHttpClient();
 *      Future<Response> f = c.prepareGet(TARGET_URL).execute();
 *      Response r = f.get();
 * }
 */
public class AsyncHttpClient {
    public static final int DEFAULT_MAX_TOTAL_CONNECTIONS = Integer.getInteger("xn.httpClient.defaultMaxTotalConnections", 2000);
    public static final int DEFAULT_MAX_CONNECTIONS_PER_HOST = Integer.getInteger("xn.httpClient.defaultMaxConnectionsPerHost", 2000);
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = Long.getLong("xn.httpClient.defaultConnectionTimeoutInMS", 60 * 1000L);
    public static final long DEFAULT_IDLE_CONNECTION_TIMEOUT_MS = Long.getLong("xn.httpClient.defaultIdleConnectionTimeoutInMS", 15 * 1000L);
    public static final int DEFAULT_REQUEST_TIMEOUT_MS = Integer.getInteger("xn.httpClient.defaultRequestTimeoutInMS", 60 * 1000);
    public static final boolean DEFAULT_REDIRECTS_ENABLED = Boolean.getBoolean("xn.httpClient.defaultRedirectsEnabled");
    public static final int DEFAULT_MAX_REDIRECTS = Integer.getInteger("xn.httpClient.defaultMaxRedirects", 5);

    private final AsyncHttpProvider httpProvider;

    public AsyncHttpClient() {
        this(new NettyAsyncHttpProvider(new ProviderConfig(
                Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()))));
    }

    public AsyncHttpClient(AsyncHttpProvider httpProvider) {
        this.httpProvider = httpProvider;

        setMaximumConnectionsTotal(DEFAULT_MAX_TOTAL_CONNECTIONS);
        setMaximumConnectionsPerHost(DEFAULT_MAX_CONNECTIONS_PER_HOST);
        setFollowRedirects(DEFAULT_REDIRECTS_ENABLED);
        setRequestTimeout(DEFAULT_REQUEST_TIMEOUT_MS);
        setConnectionTimeout(DEFAULT_CONNECTION_TIMEOUT_MS);
        setIdleConnectionTimeout(DEFAULT_IDLE_CONNECTION_TIMEOUT_MS);
        setMaximumNumberOfRedirects(DEFAULT_MAX_REDIRECTS);
    }

    private final static AsyncHandler<Response> voidHandler = new AsyncHandler<Response>(){

        @Override
        public Response onCompleted(Response response) throws IOException{
            return response;
        }

        @Override
        public void onThrowable(Throwable t) {
            t.printStackTrace();
        }

    };

    public class BoundRequestBuilder extends RequestBuilderBase<BoundRequestBuilder> {
        private BoundRequestBuilder(RequestType type) {
            super(type);
        }

        private BoundRequestBuilder(Request prototype) {
            super(prototype);
        }

        public <T> Future<T> execute(AsyncHandler<T> handler) throws IOException {
            return AsyncHttpClient.this.performRequest(build(), handler);
        }

        public Future<Response> execute() throws IOException {
            return AsyncHttpClient.this.performRequest(build(), voidHandler);
        }
    }

    public AsyncHttpProvider getProvider() {
        return httpProvider;
    }

    public void close() {
        httpProvider.close();
    }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }

    public void setMaximumConnectionsTotal(int maxConnectionsTotal) {
        httpProvider.setMaximumConnectionsTotal(maxConnectionsTotal);
    }

    public void setMaximumConnectionsPerHost(int maxConnectionsPerHost) {
        httpProvider.setMaximumConnectionsPerHost(maxConnectionsPerHost);
    }

    public void setConnectionTimeout(long timeOutInMS) {
        httpProvider.setConnectionTimeout(timeOutInMS);
    }

    public void setIdleConnectionTimeout(long timeOutInMS) {
        httpProvider.setIdleConnectionTimeout(timeOutInMS);
    }

    public void setRequestTimeout(int timeOutInMS) {
        httpProvider.setRequestTimeout(timeOutInMS);
    }

    public void setFollowRedirects(boolean followRedirects) {
        httpProvider.setFollowRedirects(followRedirects);
    }

    public void setMaximumNumberOfRedirects(int maxNumRedirects) {
        httpProvider.setMaximumNumberOfRedirects(maxNumRedirects);
    }

    public void setCompressionEnabled(boolean compressionEnabled) {
        httpProvider.setCompressionEnabled(compressionEnabled);
    }

    public void setUserAgent(String userAgent) {
        httpProvider.setUserAgent(userAgent);
    }

    /**
     * Sets the proxy for this HttpClient.
     *
     * @param proxyServer The proxy server to use. Can be null, which means "no proxy".
     */
    public void setProxy(final ProxyServer proxyServer) {
        httpProvider.setProxyServer(proxyServer);
    }

    public boolean isCompressionEnabled() {
        return httpProvider.isCompressionEnabled();
    }
    
    public BoundRequestBuilder prepareGet(String url) {
        return new BoundRequestBuilder(RequestType.GET).setUrl(url);
    }

    public BoundRequestBuilder prepareHead(String url) {
        return new BoundRequestBuilder(RequestType.HEAD).setUrl(url);
    }

    public BoundRequestBuilder preparePost(String url) {
        return new BoundRequestBuilder(RequestType.POST).setUrl(url);
    }

    public BoundRequestBuilder preparePut(String url) {
        return new BoundRequestBuilder(RequestType.PUT).setUrl(url);
    }

    public BoundRequestBuilder prepareDelete(String url) {
        return new BoundRequestBuilder(RequestType.DELETE).setUrl(url);
    }

    public BoundRequestBuilder prepareRequest(Request request) {
        return new BoundRequestBuilder(request);
    }

    public <T> Future<T> performRequest(Request request, AsyncHandler<T> handler) throws IOException {
        return httpProvider.handle(request, handler);
    }
}
