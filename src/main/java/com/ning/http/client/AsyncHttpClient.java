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

import com.ning.http.client.Request.EntityWriter;
import com.ning.http.client.providers.NettyAsyncHttpProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
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
 *       Future<Response> f = c.doGet("http://www.ning.com/", new AsyncHandler<Response>() &#123;
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
 *     Future<Integer> f = c.doGet("http://www.ning.com/", new AsyncHandler<Integer>() &#123;
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
 *      Future<Response> f = c.doGet("http://www.ning.com/", new AsyncStreamingHandler() &#123;
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
 * This class can also be used with the need of {@link AsyncHandler}</p>
 * {@code
 *      AsyncHttpClient c = new AsyncHttpClient();
 *      Future<Response> f = c.doGet(TARGET_URL);
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


    public AsyncHttpClientConfig getConfig(){
        return config;
    }


    public Future<Response> doGet(String url) throws IOException {
        return doGet(url,(Headers) null);
    }

    public Future<Response> doGet(String url, Headers headers) throws IOException {
        return doGet(url, headers,(List<Cookie>) null);
    }

    public Future<Response> doGet(String url, Headers headers, List<Cookie> cookies) throws IOException {
        return performRequest(new Request(RequestType.GET, url, headers, cookies), voidHandler);
    }

    public <T> Future<T> doGet(String url, AsyncHandler<T> handler) throws IOException {
        return doGet(url, null, null, handler);
    }

    public <T> Future<T> doGet(String url, Headers headers, AsyncHandler<T> handler) throws IOException {
        return doGet(url, headers, null, handler);
    }

    public <T> Future<T> doGet(String url, Headers headers, List<Cookie> cookies, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.GET, url, headers, cookies), handler);
    }

    public Future<Response>  doPost(String url, byte[] data) throws IOException {
        return doPost(url, null, null, data);
    }

    public Future<Response>  doPost(String url, Headers headers, byte[] data) throws IOException {
        return doPost(url, headers, null, data);
    }

    public Future<Response>  doPost(String url, Headers headers, List<Cookie> cookies, byte[] data) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, data), voidHandler);
    }

    public Future<Response>  doPost(String url, InputStream data) throws IOException {
        return doPost(url, null, null, data, -1);
    }

    public Future<Response>  doPost(String url, EntityWriter entityWriter) throws IOException {
        return doPost(url, null, null, entityWriter, -1);
    }

    public Future<Response>  doPost(String url, Headers headers, InputStream data) throws IOException {
        return doPost(url, headers, null, data, -1);
    }

    public Future<Response>  doPost(String url, Headers headers, EntityWriter entityWriter) throws IOException {
        return doPost(url, headers, entityWriter, -1);
    }

    public Future<Response>  doPost(String url, Headers headers, EntityWriter entityWriter, long length) throws IOException {
        return doPost(url, headers, null, entityWriter, length);
    }

    public Future<Response>  doPost(String url, Headers headers, List<Cookie> cookies, InputStream data) throws IOException {
        return doPost(url, headers, cookies, data, -1);
    }

    public Future<Response>  doPost(String url, InputStream data, long length) throws IOException {
        return doPost(url, null, null, data, length);
    }

    public Future<Response>  doPost(String url, Headers headers, InputStream data, long length) throws IOException {
        return doPost(url, headers, null, data, length);
    }

    public Future<Response>  doPost(String url, Headers headers, List<Cookie> cookies, InputStream data, long length) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, data, length), voidHandler);
    }

   public Future<Response>  doPost(String url, Headers headers, List<Cookie> cookies, EntityWriter entityWriter, long length) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, entityWriter, length), voidHandler);
    }

    public Future<Response> doPost(String url, Map<String, String> params) throws IOException {
        return doPost(url, (Headers)null, (List<Cookie>)null, params);
    }

    public Future<Response>  doPost(String url, Headers headers, Map<String, String> params) throws IOException {
        return doPost(url, headers, null, params);
    }

    public Future<Response>  doPost(String url, Headers headers, List<Cookie> cookies, Map<String, String> params) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, params), voidHandler);
    }

    public <T> Future<T> doPost(String url, byte[] data, AsyncHandler<T> handler) throws IOException {
        return doPost(url, null, null, data, handler);
    }

    public <T> Future<T> doPost(String url, Headers headers, byte[] data, AsyncHandler<T> handler) throws IOException {
        return doPost(url, headers, null, data, handler);
    }

    public <T> Future<T> doPost(String url, Headers headers, List<Cookie> cookies, byte[] data, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, data), handler);
    }

    public <T> Future<T> doPost(String url, InputStream data, AsyncHandler<T> handler) throws IOException {
        return doPost(url, null, null, data, -1, handler);
    }

    public <T> Future<T> doPost(String url, EntityWriter entityWriter, AsyncHandler<T> handler) throws IOException {
        return doPost(url, null, null, entityWriter, -1, handler);
    }

    public <T> Future<T> doPost(String url, Headers headers, InputStream data, AsyncHandler<T> handler) throws IOException {
        return doPost(url, headers, null, data, -1, handler);
    }

    public <T> Future<T> doPost(String url, Headers headers, EntityWriter entityWriter, AsyncHandler<T> handler) throws IOException {
        return doPost(url, headers, entityWriter, handler, -1);
    }

    public <T> Future<T> doPost(String url, Headers headers, EntityWriter entityWriter, AsyncHandler<T> handler, long length) throws IOException {
        return doPost(url, headers, null, entityWriter, length, handler);
    }

    public <T> Future<T> doPost(String url, Headers headers, List<Cookie> cookies, InputStream data, AsyncHandler<T> handler) throws IOException {
        return doPost(url, headers, cookies, data, -1, handler);
    }

    public <T> Future<T> doPost(String url, InputStream data, long length, AsyncHandler<T> handler) throws IOException {
        return doPost(url, null, null, data, length, handler);
    }

    public <T> Future<T> doPost(String url, Headers headers, InputStream data, long length, AsyncHandler<T> handler) throws IOException {
        return doPost(url, headers, null, data, length, handler);
    }

    public <T> Future<T> doPost(String url, Headers headers, List<Cookie> cookies, InputStream data, long length, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, data, length), handler);
    }

    public <T> Future<T> doPost(String url, Headers headers, List<Cookie> cookies, EntityWriter entityWriter, long length, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, entityWriter, length), handler);
    }

    public <T> Future<T> doPost(String url, Map<String, String> params, AsyncHandler<T> handler) throws IOException {
        return doPost(url, null, null, params, handler);
    }

    public <T> Future<T> doPost(String url, Headers headers, Map<String, String> params, AsyncHandler<T> handler) throws IOException {
        return doPost(url, headers, null, params, handler);
    }
   
    public <T> Future<T> doPost(String url, Headers headers, List<Cookie> cookies, Map<String, String> params, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, params), handler);
    }

    public Future<Response> doMultipartPost(String url, List<Part> params) throws IOException {
        return doMultipartPost(url, null, null, params);
    }

    public Future<Response> doMultipartPost(String url, Headers headers, List<Part> params) throws IOException {
        return doMultipartPost(url, headers, null, params);
    }

    public Future<Response> doMultipartPost(String url, Headers headers, List<Cookie> cookies, List<Part> params) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, params), voidHandler);
    }    

    public <T> Future<T> doMultipartPost(String url, List<Part> params, AsyncHandler<T> handler) throws IOException {
        return doMultipartPost(url, null, null, params, handler);
    }

    public <T> Future<T> doMultipartPost(String url, Headers headers, List<Part> params, AsyncHandler<T> handler) throws IOException {
        return doMultipartPost(url, headers, null, params, handler);
    }

    public <T> Future<T> doMultipartPost(String url, Headers headers, List<Cookie> cookies, List<Part> params, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.POST, url, headers, cookies, params), handler);
    }

    public Future<Response> doPut(String url, byte[] data) throws IOException {
        return doPut(url, null, null, data);
    }

    public Future<Response> doPut(String url, Headers headers, byte[] data) throws IOException {
        return doPut(url, headers, null, data);
    }

    public Future<Response> doPut(String url, Headers headers, List<Cookie> cookies, byte[] data) throws IOException {
        return performRequest(new Request(RequestType.PUT, url, headers, cookies, data), voidHandler);
    }

    public Future<Response> doPut(String url, InputStream data) throws IOException {
        return doPut(url, null, null, data, -1);
    }

    public Future<Response> doPut(String url, Headers headers, InputStream data) throws IOException {
        return doPut(url, headers, null, data, -1);
    }

    public Future<Response> doPut(String url, Headers headers, List<Cookie> cookies, InputStream data) throws IOException {
        return doPut(url, headers, cookies, data, -1);
    }

    public Future<Response> doPut(String url, InputStream data, long length) throws IOException {
        return doPut(url, null, null, data, length);
    }

    public Future<Response> doPut(String url, Headers headers, InputStream data, long length) throws IOException {
        return doPut(url, headers, null, data, length);
    }

    public Future<Response> doPut(String url, Headers headers, EntityWriter entityWriter, long length) throws IOException {
        return doPut(url, headers, (List<Cookie>)null, entityWriter, length);
    }

    public Future<Response> doPut(String url, Headers headers, List<Cookie> cookies, InputStream data, long length) throws IOException {
        return performRequest(new Request(RequestType.PUT, url, headers, cookies, data, length), voidHandler);
    }

    public Future<Response> doPut(String url, Headers headers, List<Cookie> cookies, EntityWriter entityWriter, long length) throws IOException {
        return performRequest(new Request(RequestType.PUT, url, headers, cookies, entityWriter, length), voidHandler);
    }

    public <T> Future<T> doPut(String url, byte[] data, AsyncHandler<T> handler) throws IOException {
        return doPut(url, null, null, data, handler);
    }

    public <T> Future<T> doPut(String url, Headers headers, byte[] data, AsyncHandler<T> handler) throws IOException {
        return doPut(url, headers, null, data, handler);
    }

    public <T> Future<T> doPut(String url, Headers headers, List<Cookie> cookies, byte[] data, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.PUT, url, headers, cookies, data), handler);
    }

    public <T> Future<T> doPut(String url, InputStream data, AsyncHandler<T> handler) throws IOException {
        return doPut(url, null, null, data, -1, handler);
    }

    public <T> Future<T> doPut(String url, Headers headers, InputStream data, AsyncHandler<T> handler) throws IOException {
        return doPut(url, headers, null, data, -1, handler);
    }

    public <T> Future<T> doPut(String url, Headers headers, List<Cookie> cookies, InputStream data, AsyncHandler<T> handler) throws IOException {
        return doPut(url, headers, cookies, data, -1, handler);
    }

    public <T> Future<T> doPut(String url, InputStream data, long length, AsyncHandler<T> handler) throws IOException {
        return doPut(url, null, null, data, length, handler);
    }

    public <T> Future<T> doPut(String url, Headers headers, InputStream data, long length, AsyncHandler<T> handler) throws IOException {
        return doPut(url, headers, null, data, length, handler);
    }

    public <T> Future<T> doPut(String url, Headers headers, EntityWriter entityWriter, long length, AsyncHandler<T> handler) throws IOException {
        return doPut(url, headers, null, entityWriter, length, handler);
    }

    public <T> Future<T> doPut(String url, Headers headers, List<Cookie> cookies, InputStream data, long length, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.PUT, url, headers, cookies, data, length), handler);
    }

    public <T> Future<T> doPut(String url, Headers headers, List<Cookie> cookies, EntityWriter entityWriter, long length, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.PUT, url, headers, cookies, entityWriter, length), handler);
    }

    public Future<Response> doDelete(String url) throws IOException {
        return doDelete(url, (Headers)null);
    }

    public Future<Response> doDelete(String url, Headers headers) throws IOException {
        return doDelete(url, headers, (List<Cookie>)null);
    }

    public Future<Response> doDelete(String url, Headers headers, List<Cookie> cookies) throws IOException {
        return performRequest(new Request(RequestType.DELETE, url, headers, cookies), voidHandler);
    }

    public <T> Future<T> doDelete(String url, AsyncHandler<T> handler) throws IOException {
        return doDelete(url, null, null, handler);
    }

    public <T> Future<T> doDelete(String url, Headers headers, AsyncHandler<T> handler) throws IOException {
        return doDelete(url, headers, null, handler);
    }

    public <T> Future<T> doDelete(String url, Headers headers, List<Cookie> cookies, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.DELETE, url, headers, cookies), handler);
    }

    public Future<Response> doHead(String url) throws IOException {
        return doHead(url, (Headers)null);
    }

    public Future<Response> doHead(String url, Headers headers) throws IOException {
        return doHead(url, headers, (List<Cookie>)null);
    }

    public Future<Response> doHead(String url, Headers headers, List<Cookie> cookies) throws IOException {
        return performRequest(new Request(RequestType.HEAD, url, headers, cookies), voidHandler);
    }
    
    public <T> Future<T> doHead(String url, AsyncHandler<T> handler) throws IOException {
        return doHead(url, null, null, handler);
    }

    public <T> Future<T> doHead(String url, Headers headers, AsyncHandler<T> handler) throws IOException {
        return doHead(url, headers, null, handler);
    }

    public <T> Future<T> doHead(String url, Headers headers, List<Cookie> cookies, AsyncHandler<T> handler) throws IOException {
        return performRequest(new Request(RequestType.HEAD, url, headers, cookies), handler);
    }

    public <T> Future<T> performRequest(Request request,
                                AsyncHandler<T> handler) throws IOException {
        return httpProvider.handle(request, handler);
    }
}
