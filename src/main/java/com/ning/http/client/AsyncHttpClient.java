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
import java.util.concurrent.Future;

/**
 * This class support asynchronous and synchronous HTTP request.
 *
 * To execute synchronous HTTP request, you just need to do
 * {@code
 *    AsyncHttpClient c = new AsyncHttpClient();
 *    Future<Response> f = c.prepareGet("http://www.ning.com/").execute();
 * }
 *
 * The code above will block until the response is fully received. To execute asynchronous HTTP request, you
 * create an {@link AsyncHandler} or its abstract implementation, {@link com.ning.http.client.AsyncCompletionHandler}
 *
 * {@code
 *       AsyncHttpClient c = new AsyncHttpClient();
 *       Future<Response> f = c.prepareGet("http://www.ning.com/").execute(new AsyncCompletionHandler<Response>() &#123;
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
 *     Future<Integer> f = c.prepareGet("http://www.ning.com/").execute(new AsyncCompletionHandler<Integer>() &#123;
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
 * The {@link AsyncCompletionHandler#onCompleted(com.ning.http.client.Response)} will be invoked once the http response has been fully read, which include
 * the http headers and the response body. Note that the entire response will be buffered in memory.
 * 
 * You can also have more control about the how the response is asynchronously processed by using a {@link AsyncHandler}
 * {@code
 *      AsyncHttpClient c = new AsyncHttpClient();
 *      Future<String> f = c.prepareGet("http://www.ning.com/").execute(new AsyncHandler<String>() &#123;
 *          private StringBuilder builder = new StringBuilder();
 *
 *          @Override
 *          public void onStatusReceived(HttpResponseStatus s) throws Exception &#123;
 *               // The Status have been read
 *               // If you don't want to read the headers,body, or stop processing the response
 *               throw new ResponseComplete();
 *          }
 *
 *          @Override
 *          public void onHeadersReceived(HttpResponseHeaders bodyPart) throws Exception &#123;
 *               // The headers have been read
 *               // If you don't want to read the body, or stop processing the response
 *               throw new ResponseComplete();
 *          }
 *          @Override
 *
 *          public void onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception &#123;
 *               builder.append(new String(bodyPart));
 *          &#125;
 *
 *          @Override
 *          public String onCompleted() throws Exception &#123;
 *               // Will be invoked once the response has been fully read or a ResponseComplete exception
 *               // has been thrown.
 *               return builder.toString();
 *          &#125;
 *
 *          @Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 *
 *      String bodyResponse = f.get();
 * }
 * From any {@link HttpContent} sub classses, you can asynchronously process the response status,headers and body and decide when to
 * stop the processing the response by throwing a new {link ResponseComplete} at any moment.
 *
 * This class can also be used without the need of {@link AsyncHandler}</p>
 * {@code
 *      AsyncHttpClient c = new AsyncHttpClient();
 *      Future<Response> f = c.prepareGet(TARGET_URL).execute();
 *      Response r = f.get();
 * }
 *
 * Finally, you can configure the AsyncHttpClient using an {@link AsyncHttpClientConfig} instance</p>
 * {@code
 *      AsyncHttpClient c = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeout(...).build());
 *      Future<Response> f = c.prepareGet(TARGET_URL).execute();
 *      Response r = f.get();
 * }
 */
public class AsyncHttpClient {

    private final AsyncHttpProvider httpProvider;

    private final AsyncHttpClientConfig config;


    public AsyncHttpClient() {
        this(new AsyncHttpClientConfig.Builder().build());
    }

    public AsyncHttpClient(AsyncHttpClientConfig config) {
        this.config = config;
        this.httpProvider = new NettyAsyncHttpProvider(config);
    }

    public AsyncHttpClient(AsyncHttpProvider httpProvider) {
        this.config = new AsyncHttpClientConfig.Builder().build();
        this.httpProvider = httpProvider;
    }

    private final static AsyncHandler voidHandler = new AsyncCompletionHandler<Response>(){

        @Override
        public Response onCompleted(Response response) throws Exception {
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

    /**
     * Return the asynchronouys {@link com.ning.http.client.AsyncHttpProvider}
     * @return
     */
    public AsyncHttpProvider getProvider() {
        return httpProvider;
    }

    /**
     * Close the underlying connection.
     */
    public void close() {
        httpProvider.close();
    }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }

    /**
     * Return the {@link com.ning.http.client.AsyncHttpClientConfig}
     * @return
     */
    public AsyncHttpClientConfig getConfig(){
        return config;
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
        return httpProvider.execute(request, handler);
    }
}
