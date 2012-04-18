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
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.RequestFilter;
import com.ning.http.client.providers.jdk.JDKAsyncHttpProvider;
import com.ning.http.client.resumable.ResumableAsyncHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class support asynchronous and synchronous HTTP request.
 * <p/>
 * To execute synchronous HTTP request, you just need to do
 * <blockquote><pre>
 *    AsyncHttpClient c = new AsyncHttpClient();
 *    Future<Response> f = c.prepareGet("http://www.ning.com/").execute();
 * </pre></blockquote
 * <p/>
 * The code above will block until the response is fully received. To execute asynchronous HTTP request, you
 * create an {@link AsyncHandler} or its abstract implementation, {@link com.ning.http.client.AsyncCompletionHandler}
 * <p/>
 * <blockquote><pre>
 *       AsyncHttpClient c = new AsyncHttpClient();
 *       Future<Response> f = c.prepareGet("http://www.ning.com/").execute(new AsyncCompletionHandler<Response>() &#123;
 * <p/>
 *          &#64;Override
 *          public Response onCompleted(Response response) throws IOException &#123;
 *               // Do something
 *              return response;
 *          &#125;
 * <p/>
 *          &#64;Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 *      Response response = f.get();
 * <p/>
 *      // We are just interested to retrieve the status code.
 *     Future<Integer> f = c.prepareGet("http://www.ning.com/").execute(new AsyncCompletionHandler<Integer>() &#123;
 * <p/>
 *          &#64;Override
 *          public Integer onCompleted(Response response) throws IOException &#123;
 *               // Do something
 *              return response.getStatusCode();
 *          &#125;
 * <p/>
 *          &#64;Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 *      Integer statusCode = f.get();
 * </pre></blockquote
 * The {@link AsyncCompletionHandler#onCompleted(com.ning.http.client.Response)} will be invoked once the http response has been fully read, which include
 * the http headers and the response body. Note that the entire response will be buffered in memory.
 * <p/>
 * You can also have more control about the how the response is asynchronously processed by using a {@link AsyncHandler}
 * <blockquote><pre>
 *      AsyncHttpClient c = new AsyncHttpClient();
 *      Future<String> f = c.prepareGet("http://www.ning.com/").execute(new AsyncHandler<String>() &#123;
 *          private StringBuilder builder = new StringBuilder();
 * <p/>
 *          &#64;Override
 *          public STATE onStatusReceived(HttpResponseStatus s) throws Exception &#123;
 *               // return STATE.CONTINUE or STATE.ABORT
 *               return STATE.CONTINUE
 *          }
 * <p/>
 *          &#64;Override
 *          public STATE onHeadersReceived(HttpResponseHeaders bodyPart) throws Exception &#123;
 *               // return STATE.CONTINUE or STATE.ABORT
 *               return STATE.CONTINUE
 * <p/>
 *          }
 *          &#64;Override
 * <p/>
 *          public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception &#123;
 *               builder.append(new String(bodyPart));
 *               // return STATE.CONTINUE or STATE.ABORT
 *               return STATE.CONTINUE
 *          &#125;
 * <p/>
 *          &#64;Override
 *          public String onCompleted() throws Exception &#123;
 *               // Will be invoked once the response has been fully read or a ResponseComplete exception
 *               // has been thrown.
 *               return builder.toString();
 *          &#125;
 * <p/>
 *          &#64;Override
 *          public void onThrowable(Throwable t) &#123;
 *          &#125;
 *      &#125;);
 * <p/>
 *      String bodyResponse = f.get();
 * </pre></blockquote
 * From any {@link HttpContent} sub classes, you can asynchronously process the response status,headers and body and decide when to
 * stop the processing the response by throwing a new {link ResponseComplete} at any moment.
 * <p/>
 * This class can also be used without the need of {@link AsyncHandler}</p>
 * <blockquote><pre>
 *      AsyncHttpClient c = new AsyncHttpClient();
 *      Future<Response> f = c.prepareGet(TARGET_URL).execute();
 *      Response r = f.get();
 * </pre></blockquote>
 * <p/>
 * Finally, you can configure the AsyncHttpClient using an {@link AsyncHttpClientConfig} instance</p>
 * <blockquote><pre>
 *      AsyncHttpClient c = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(...).build());
 *      Future<Response> f = c.prepareGet(TARGET_URL).execute();
 *      Response r = f.get();
 * </pre></blockquote>
 * <p/>
 * An instance of this class will cache every HTTP 1.1 connections and close them when the {@link AsyncHttpClientConfig#getIdleConnectionTimeoutInMs()}
 * expires. This object can hold many persistent connections to different host.
 */
public class AsyncHttpClient {

    private final static String DEFAULT_PROVIDER = "com.ning.http.client.providers.netty.NettyAsyncHttpProvider";
    private final AsyncHttpProvider httpProvider;
    private final AsyncHttpClientConfig config;
    private final static Logger logger = LoggerFactory.getLogger(AsyncHttpClient.class);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    /**
     * Default signature calculator to use for all requests constructed by this client instance.
     *
     * @since 1.1
     */
    protected SignatureCalculator signatureCalculator;

    /**
     * Create a new HTTP Asynchronous Client using the default {@link AsyncHttpClientConfig} configuration. The
     * default {@link AsyncHttpProvider} will be used ({@link com.ning.http.client.providers.netty.NettyAsyncHttpProvider}
     */
    public AsyncHttpClient() {
        this(new AsyncHttpClientConfig.Builder().build());
    }

    /**
     * Create a new HTTP Asynchronous Client using an implementation of {@link AsyncHttpProvider} and
     * the default {@link AsyncHttpClientConfig} configuration.
     *
     * @param provider a {@link AsyncHttpProvider}
     */
    public AsyncHttpClient(AsyncHttpProvider provider) {
        this(provider, new AsyncHttpClientConfig.Builder().build());
    }

    /**
     * Create a new HTTP Asynchronous Client using a {@link AsyncHttpClientConfig} configuration and the
     * {@link #DEFAULT_PROVIDER}
     *
     * @param config a {@link AsyncHttpClientConfig}
     */
    public AsyncHttpClient(AsyncHttpClientConfig config) {
        this(loadDefaultProvider(DEFAULT_PROVIDER, config), config);
    }

    /**
     * Create a new HTTP Asynchronous Client using a {@link AsyncHttpClientConfig} configuration and
     * and a {@link AsyncHttpProvider}.
     *
     * @param config       a {@link AsyncHttpClientConfig}
     * @param httpProvider a {@link AsyncHttpProvider}
     */
    public AsyncHttpClient(AsyncHttpProvider httpProvider, AsyncHttpClientConfig config) {
        this.config = config;
        this.httpProvider = httpProvider;
    }

    /**
     * Create a new HTTP Asynchronous Client using a {@link AsyncHttpClientConfig} configuration and
     * and a AsyncHttpProvider class' name.
     *
     * @param config        a {@link AsyncHttpClientConfig}
     * @param providerClass a {@link AsyncHttpProvider}
     */
    public AsyncHttpClient(String providerClass, AsyncHttpClientConfig config) {
        this.config = new AsyncHttpClientConfig.Builder().build();
        this.httpProvider = loadDefaultProvider(providerClass, config);
    }

    public class BoundRequestBuilder extends RequestBuilderBase<BoundRequestBuilder> {
        /**
         * Calculator used for calculating request signature for the request being
         * built, if any.
         */
        protected SignatureCalculator signatureCalculator;

        /**
         * URL used as the base, not including possibly query parameters. Needed for
         * signature calculation
         */
        protected String baseURL;

        private BoundRequestBuilder(String reqType, boolean useRawUrl) {
            super(BoundRequestBuilder.class, reqType, useRawUrl);
        }

        private BoundRequestBuilder(Request prototype) {
            super(BoundRequestBuilder.class, prototype);
        }

        public <T> ListenableFuture<T> execute(AsyncHandler<T> handler) throws IOException {
            return AsyncHttpClient.this.executeRequest(build(), handler);
        }

        public ListenableFuture<Response> execute() throws IOException {
            return AsyncHttpClient.this.executeRequest(build(), new AsyncCompletionHandlerBase());
        }

        // Note: For now we keep the delegates in place even though they are not needed
        //       since otherwise Clojure (and maybe other languages) won't be able to
        //       access these methods - see Clojure tickets 126 and 259

        @Override
        public BoundRequestBuilder addBodyPart(Part part) throws IllegalArgumentException {
            return super.addBodyPart(part);
        }

        @Override
        public BoundRequestBuilder addCookie(Cookie cookie) {
            return super.addCookie(cookie);
        }

        @Override
        public BoundRequestBuilder addHeader(String name, String value) {
            return super.addHeader(name, value);
        }

        @Override
        public BoundRequestBuilder addParameter(String key, String value) throws IllegalArgumentException {
            return super.addParameter(key, value);
        }

        @Override
        public BoundRequestBuilder addQueryParameter(String name, String value) {
            return super.addQueryParameter(name, value);
        }

        @Override
        public Request build() {
            /* Let's first calculate and inject signature, before finalizing actual build
             * (order does not matter with current implementation but may in future)
             */
            if (signatureCalculator != null) {
                String url = baseURL;
                // Should not include query parameters, ensure:
                int i = url.indexOf('?');
                if (i >= 0) {
                    url = url.substring(0, i);
                }
                signatureCalculator.calculateAndAddSignature(url, request, this);
            }
            return super.build();
        }

        @Override
        public BoundRequestBuilder setBody(byte[] data) throws IllegalArgumentException {
            return super.setBody(data);
        }

        @Override
        public BoundRequestBuilder setBody(EntityWriter dataWriter, long length) throws IllegalArgumentException {
            return super.setBody(dataWriter, length);
        }

        @Override
        public BoundRequestBuilder setBody(EntityWriter dataWriter) {
            return super.setBody(dataWriter);
        }

        @Override
        public BoundRequestBuilder setBody(InputStream stream) throws IllegalArgumentException {
            return super.setBody(stream);
        }

        @Override
        public BoundRequestBuilder setBody(String data) throws IllegalArgumentException {
            return super.setBody(data);
        }

        @Override
        public BoundRequestBuilder setHeader(String name, String value) {
            return super.setHeader(name, value);
        }

        @Override
        public BoundRequestBuilder setHeaders(FluentCaseInsensitiveStringsMap headers) {
            return super.setHeaders(headers);
        }

        @Override
        public BoundRequestBuilder setHeaders(Map<String, Collection<String>> headers) {
            return super.setHeaders(headers);
        }

        @Override
        public BoundRequestBuilder setParameters(Map<String, Collection<String>> parameters) throws IllegalArgumentException {
            return super.setParameters(parameters);
        }

        @Override
        public BoundRequestBuilder setParameters(FluentStringsMap parameters) throws IllegalArgumentException {
            return super.setParameters(parameters);
        }

        @Override
        public BoundRequestBuilder setUrl(String url) {
            baseURL = url;
            return super.setUrl(url);
        }

        @Override
        public BoundRequestBuilder setVirtualHost(String virtualHost) {
            return super.setVirtualHost(virtualHost);
        }

        public BoundRequestBuilder setSignatureCalculator(SignatureCalculator signatureCalculator) {
            this.signatureCalculator = signatureCalculator;
            return this;
        }
    }


    /**
     * Return the asynchronous {@link com.ning.http.client.AsyncHttpProvider}
     *
     * @return an {@link com.ning.http.client.AsyncHttpProvider}
     */
    public AsyncHttpProvider getProvider() {
        return httpProvider;
    }

    /**
     * Close the underlying connections.
     */
    public void close() {
        httpProvider.close();
        isClosed.set(true);
    }

    /**
     * Asynchronous close the {@link AsyncHttpProvider} by spawning a thread and avoid blocking.
     */
    public void closeAsynchronously() {
        config.applicationThreadPool.submit(new Runnable() {

            public void run() {
                httpProvider.close();
                isClosed.set(true);
            }
        });
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!isClosed.get()) {
                logger.debug("AsyncHttpClient.close() hasn't been invoked, which may produce file descriptor leaks");
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Return true if closed
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return isClosed.get();
    }

    /**
     * Return the {@link com.ning.http.client.AsyncHttpClientConfig}
     *
     * @return {@link com.ning.http.client.AsyncHttpClientConfig}
     */
    public AsyncHttpClientConfig getConfig() {
        return config;
    }

    /**
     * Set default signature calculator to use for requests build by this client instance
     */
    public AsyncHttpClient setSignatureCalculator(SignatureCalculator signatureCalculator) {
        this.signatureCalculator = signatureCalculator;
        return this;
    }

    /**
     * Prepare an HTTP client GET request.
     *
     * @param url A well formed URL.
     * @return {@link RequestBuilder}
     */
    public BoundRequestBuilder prepareGet(String url) {
        return requestBuilder("GET", url);
    }

    /**
     * Prepare an HTTP client CONNECT request.
     *
     * @param url A well formed URL.
     * @return {@link RequestBuilder}
     */
    public BoundRequestBuilder prepareConnect(String url) {
        return requestBuilder("CONNECT", url);
    }

    /**
     * Prepare an HTTP client OPTIONS request.
     *
     * @param url A well formed URL.
     * @return {@link RequestBuilder}
     */
    public BoundRequestBuilder prepareOptions(String url) {
        return requestBuilder("OPTIONS", url);
    }

    /**
     * Prepare an HTTP client HEAD request.
     *
     * @param url A well formed URL.
     * @return {@link RequestBuilder}
     */
    public BoundRequestBuilder prepareHead(String url) {
        return requestBuilder("HEAD", url);
    }

    /**
     * Prepare an HTTP client POST request.
     *
     * @param url A well formed URL.
     * @return {@link RequestBuilder}
     */
    public BoundRequestBuilder preparePost(String url) {
        return requestBuilder("POST", url);
    }

    /**
     * Prepare an HTTP client PUT request.
     *
     * @param url A well formed URL.
     * @return {@link RequestBuilder}
     */
    public BoundRequestBuilder preparePut(String url) {
        return requestBuilder("PUT", url);
    }

    /**
     * Prepare an HTTP client DELETE request.
     *
     * @param url A well formed URL.
     * @return {@link RequestBuilder}
     */
    public BoundRequestBuilder prepareDelete(String url) {
        return requestBuilder("DELETE", url);
    }

    /**
     * Construct a {@link RequestBuilder} using a {@link Request}
     *
     * @param request a {@link Request}
     * @return {@link RequestBuilder}
     */
    public BoundRequestBuilder prepareRequest(Request request) {
        return requestBuilder(request);
    }

    /**
     * Execute an HTTP request.
     *
     * @param request {@link Request}
     * @param handler an instance of {@link AsyncHandler}
     * @param <T>     Type of the value that will be returned by the associated {@link java.util.concurrent.Future}
     * @return a {@link Future} of type T
     * @throws IOException
     */
    public <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler) throws IOException {

        FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(request).build();
        fc = preProcessRequest(fc);

        return httpProvider.execute(fc.getRequest(), fc.getAsyncHandler());
    }

    /**
     * Execute an HTTP request.
     *
     * @param request {@link Request}
     * @return a {@link Future} of type Response
     * @throws IOException
     */
    public ListenableFuture<Response> executeRequest(Request request) throws IOException {
        FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(new AsyncCompletionHandlerBase()).request(request).build();
        fc = preProcessRequest(fc);
        return httpProvider.execute(fc.getRequest(), fc.getAsyncHandler());
    }

    /**
     * Configure and execute the associated {@link RequestFilter}. This class may decorate the {@link Request} and {@link AsyncHandler}
     *
     * @param fc {@link FilterContext}
     * @return {@link FilterContext}
     */
    private FilterContext preProcessRequest(FilterContext fc) throws IOException {
        for (RequestFilter asyncFilter : config.getRequestFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                if (fc == null) {
                    throw new NullPointerException("FilterContext is null");
                }
            } catch (FilterException e) {
                IOException ex = new IOException();
                ex.initCause(e);
                throw ex;
            }
        }

        Request request = fc.getRequest();
        if (ResumableAsyncHandler.class.isAssignableFrom(fc.getAsyncHandler().getClass())) {
            request = ResumableAsyncHandler.class.cast(fc.getAsyncHandler()).adjustRequestRange(request);
        }

        if (request.getRangeOffset() != 0) {
            RequestBuilder builder = new RequestBuilder(request);
            builder.setHeader("Range", "bytes=" + request.getRangeOffset() + "-");
            request = builder.build();
        }
        fc = new FilterContext.FilterContextBuilder(fc).request(request).build();
        return fc;
    }

    @SuppressWarnings("unchecked")
    private final static AsyncHttpProvider loadDefaultProvider(String className, AsyncHttpClientConfig config) {
        try {
            Class<AsyncHttpProvider> providerClass = (Class<AsyncHttpProvider>) Thread.currentThread()
                    .getContextClassLoader().loadClass(className);
            return providerClass.getDeclaredConstructor(
                    new Class[]{AsyncHttpClientConfig.class}).newInstance(new Object[]{config});
        } catch (Throwable t) {

            // Let's try with another classloader
            try {
                Class<AsyncHttpProvider> providerClass = (Class<AsyncHttpProvider>)
                        AsyncHttpClient.class.getClassLoader().loadClass(className);
                return providerClass.getDeclaredConstructor(
                        new Class[]{AsyncHttpClientConfig.class}).newInstance(new Object[]{config});
            } catch (Throwable t2) {
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Default provider not found {}. Using the {}", DEFAULT_PROVIDER,
                        JDKAsyncHttpProvider.class.getName());
            }
            return new JDKAsyncHttpProvider(config);
        }
    }

    protected BoundRequestBuilder requestBuilder(String reqType, String url) {
        return new BoundRequestBuilder(reqType, config.isUseRawUrl()).setUrl(url).setSignatureCalculator(signatureCalculator);
    }

    protected BoundRequestBuilder requestBuilder(Request prototype) {
        return new BoundRequestBuilder(prototype).setSignatureCalculator(signatureCalculator);
    }
}
