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
package org.asynchttpclient;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.multipart.Part;
import org.asynchttpclient.resumable.ResumableAsyncHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class support asynchronous and synchronous HTTP request.
 * <p/>
 * To execute synchronous HTTP request, you just need to do <blockquote>
 * 
 * <pre>
 * AsyncHttpClient c = new AsyncHttpClient();
 * Future&lt;Response&gt; f = c.prepareGet(&quot;http://www.ning.com/&quot;).execute();
 * </pre>
 * 
 * </blockquote
 * <p/>
 * The code above will block until the response is fully received. To execute
 * asynchronous HTTP request, you create an {@link AsyncHandler} or its abstract
 * implementation, {@link AsyncCompletionHandler}
 * <p/>
 * <blockquote>
 * 
 * <pre>
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
 * </pre>
 * 
 * </blockquote The {@link AsyncCompletionHandler#onCompleted(Response)} will be
 * invoked once the http response has been fully read, which include the http
 * headers and the response body. Note that the entire response will be buffered
 * in memory.
 * <p/>
 * You can also have more control about the how the response is asynchronously
 * processed by using a {@link AsyncHandler} <blockquote>
 * 
 * <pre>
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
 * </pre>
 * 
 * </blockquote From any {@link HttpContent} sub classes, you can asynchronously
 * process the response status,headers and body and decide when to stop the
 * processing the response by throwing a new {link ResponseComplete} at any
 * moment.
 * <p/>
 * This class can also be used without the need of {@link AsyncHandler}
 * </p>
 * <blockquote>
 * 
 * <pre>
 * AsyncHttpClient c = new AsyncHttpClient();
 * Future&lt;Response&gt; f = c.prepareGet(TARGET_URL).execute();
 * Response r = f.get();
 * </pre>
 * 
 * </blockquote>
 * <p/>
 * Finally, you can configure the AsyncHttpClient using an
 * {@link AsyncHttpClientConfig} instance
 * </p>
 * <blockquote>
 * 
 * <pre>
 *      AsyncHttpClient c = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(...).build());
 *      Future<Response> f = c.prepareGet(TARGET_URL).execute();
 *      Response r = f.get();
 * </pre>
 * 
 * </blockquote>
 * <p/>
 * An instance of this class will cache every HTTP 1.1 connections and close
 * them when the {@link AsyncHttpClientConfig#getIdleConnectionTimeoutInMs()}
 * expires. This object can hold many persistent connections to different host.
 */
public class AsyncHttpClientImpl implements Closeable, AsyncHttpClient {

    /**
     * Providers that will be searched for, on the classpath, in order when no
     * provider is explicitly specified by the developer.
     */
    private static final String[] DEFAULT_PROVIDERS = { "org.asynchttpclient.providers.netty.NettyAsyncHttpProvider",
            "org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider" };

    private final AsyncHttpProvider httpProvider;
    private final AsyncHttpClientConfig config;
    private final static Logger logger = LoggerFactory.getLogger(AsyncHttpClientImpl.class);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    /**
     * Default signature calculator to use for all requests constructed by this
     * client instance.
     * 
     * @since 1.1
     */
    protected SignatureCalculator signatureCalculator;

    /**
     * Create a new HTTP Asynchronous Client using the default
     * {@link AsyncHttpClientConfig} configuration. The default
     * {@link AsyncHttpProvider} that will be used will be based on the
     * classpath configuration.
     * 
     * The default providers will be searched for in this order:
     * <ul>
     * <li>netty</li>
     * <li>grizzly</li>
     * <li>JDK</li>
     * </ul>
     * 
     * If none of those providers are found, then the engine will throw an
     * IllegalStateException.
     */
    public AsyncHttpClientImpl() {
        this(new AsyncHttpClientConfig.Builder().build());
    }

    /**
     * Create a new HTTP Asynchronous Client using an implementation of
     * {@link AsyncHttpProvider} and the default {@link AsyncHttpClientConfig}
     * configuration.
     * 
     * @param provider
     *            a {@link AsyncHttpProvider}
     */
    public AsyncHttpClientImpl(AsyncHttpProvider provider) {
        this(provider, new AsyncHttpClientConfig.Builder().build());
    }

    /**
     * Create a new HTTP Asynchronous Client using the specified
     * {@link AsyncHttpClientConfig} configuration. This configuration will be
     * passed to the default {@link AsyncHttpProvider} that will be selected
     * based on the classpath configuration.
     * 
     * The default providers will be searched for in this order:
     * <ul>
     * <li>netty</li>
     * <li>grizzly</li>
     * </ul>
     * 
     * If none of those providers are found, then the engine will throw an
     * IllegalStateException.
     * 
     * @param config
     *            a {@link AsyncHttpClientConfig}
     */
    public AsyncHttpClientImpl(AsyncHttpClientConfig config) {
        this(loadDefaultProvider(DEFAULT_PROVIDERS, config), config);
    }

    /**
     * Create a new HTTP Asynchronous Client using a
     * {@link AsyncHttpClientConfig} configuration and and a AsyncHttpProvider
     * class' name.
     * 
     * @param config
     *            a {@link AsyncHttpClientConfig}
     * @param providerClass
     *            a {@link AsyncHttpProvider}
     */
    public AsyncHttpClientImpl(String providerClass, AsyncHttpClientConfig config) {
        this(loadProvider(providerClass, config), new AsyncHttpClientConfig.Builder().build());
    }

    /**
     * Create a new HTTP Asynchronous Client using a
     * {@link AsyncHttpClientConfig} configuration and and a
     * {@link AsyncHttpProvider}.
     * 
     * @param config
     *            a {@link AsyncHttpClientConfig}
     * @param httpProvider
     *            a {@link AsyncHttpProvider}
     */
    public AsyncHttpClientImpl(AsyncHttpProvider httpProvider, AsyncHttpClientConfig config) {
        this.config = config;
        this.httpProvider = httpProvider;
    }

    public class BoundRequestBuilder extends RequestBuilderBase<BoundRequestBuilder> {
        /**
         * Calculator used for calculating request signature for the request
         * being built, if any.
         */
        protected SignatureCalculator signatureCalculator;

        /**
         * URL used as the base, not including possibly query parameters. Needed
         * for signature calculation
         */
        protected String baseURL;

        private BoundRequestBuilder(String reqType, boolean useRawUrl) {
            super(BoundRequestBuilder.class, reqType, useRawUrl);
        }

        private BoundRequestBuilder(Request prototype) {
            super(BoundRequestBuilder.class, prototype);
        }

        public <T> ListenableFuture<T> execute(AsyncHandler<T> handler) throws IOException {
            return AsyncHttpClientImpl.this.executeRequest(build(), handler);
        }

        public ListenableFuture<Response> execute() throws IOException {
            return AsyncHttpClientImpl.this.executeRequest(build(), new AsyncCompletionHandlerBase());
        }

        // Note: For now we keep the delegates in place even though they are not
        // needed
        // since otherwise Clojure (and maybe other languages) won't be able to
        // access these methods - see Clojure tickets 126 and 259

        @Override
        public BoundRequestBuilder addBodyPart(Part part) {
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
        public BoundRequestBuilder addParameter(String key, String value) {
            return super.addParameter(key, value);
        }

        @Override
        public BoundRequestBuilder addQueryParameter(String name, String value) {
            return super.addQueryParameter(name, value);
        }

        @Override
        public Request build() {
            /*
             * Let's first calculate and inject signature, before finalizing
             * actual build (order does not matter with current implementation
             * but may in future)
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
        public BoundRequestBuilder setBody(byte[] data) {
            return super.setBody(data);
        }

        @Override
        public BoundRequestBuilder setBody(InputStream stream) {
            return super.setBody(stream);
        }

        @Override
        public BoundRequestBuilder setBody(String data) {
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
        public BoundRequestBuilder setParameters(Map<String, Collection<String>> parameters) {
            return super.setParameters(parameters);
        }

        @Override
        public BoundRequestBuilder setParameters(FluentStringsMap parameters) {
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

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#getProvider()
     */
    @Override
    public AsyncHttpProvider getProvider() {
        return httpProvider;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#close()
     */
    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true))
            httpProvider.close();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#closeAsynchronously()
     */
    @Override
    public void closeAsynchronously() {
        final ExecutorService e = Executors.newSingleThreadExecutor();
        e.submit(new Runnable() {
            public void run() {
                try {
                    close();
                } catch (Throwable t) {
                    logger.warn("", t);
                } finally {
                    e.shutdown();
                }
            }
        });
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!isClosed.get()) {
                logger.error("AsyncHttpClient.close() hasn't been invoked, which may produce file descriptor leaks");
            }
        } finally {
            super.finalize();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#isClosed()
     */
    @Override
    public boolean isClosed() {
        return isClosed.get();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#getConfig()
     */
    @Override
    public AsyncHttpClientConfig getConfig() {
        return config;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#setSignatureCalculator(org.
     * asynchttpclient.SignatureCalculator)
     */
    @Override
    public AsyncHttpClient setSignatureCalculator(SignatureCalculator signatureCalculator) {
        this.signatureCalculator = signatureCalculator;
        return this;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#prepareGet(java.lang.String)
     */
    @Override
    public BoundRequestBuilder prepareGet(String url) {
        return requestBuilder("GET", url);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.asynchttpclient.IAsyncHttpClient#prepareConnect(java.lang.String)
     */
    @Override
    public BoundRequestBuilder prepareConnect(String url) {
        return requestBuilder("CONNECT", url);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.asynchttpclient.IAsyncHttpClient#prepareOptions(java.lang.String)
     */
    @Override
    public BoundRequestBuilder prepareOptions(String url) {
        return requestBuilder("OPTIONS", url);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#prepareHead(java.lang.String)
     */
    @Override
    public BoundRequestBuilder prepareHead(String url) {
        return requestBuilder("HEAD", url);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#preparePost(java.lang.String)
     */
    @Override
    public BoundRequestBuilder preparePost(String url) {
        return requestBuilder("POST", url);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#preparePut(java.lang.String)
     */
    @Override
    public BoundRequestBuilder preparePut(String url) {
        return requestBuilder("PUT", url);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#prepareDelete(java.lang.String)
     */
    @Override
    public BoundRequestBuilder prepareDelete(String url) {
        return requestBuilder("DELETE", url);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#preparePatch(java.lang.String)
     */
    @Override
    public BoundRequestBuilder preparePatch(String url) {
        return requestBuilder("PATCH", url);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClient#prepareTrace(java.lang.String)
     */
    @Override
    public BoundRequestBuilder prepareTrace(String url) {
        return requestBuilder("TRACE", url);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.asynchttpclient.IAsyncHttpClient#prepareRequest(org.asynchttpclient
     * .Request)
     */
    @Override
    public BoundRequestBuilder prepareRequest(Request request) {
        return requestBuilder(request);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.asynchttpclient.IAsyncHttpClient#executeRequest(org.asynchttpclient
     * .Request, org.asynchttpclient.AsyncHandler)
     */
    @Override
    public <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler) throws IOException {

        FilterContext<T> fc = new FilterContext.FilterContextBuilder<T>().asyncHandler(handler).request(request)
                .build();
        fc = preProcessRequest(fc);

        return httpProvider.execute(fc.getRequest(), fc.getAsyncHandler());
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.asynchttpclient.IAsyncHttpClient#executeRequest(org.asynchttpclient
     * .Request)
     */
    @Override
    public ListenableFuture<Response> executeRequest(Request request) throws IOException {
        FilterContext<Response> fc = new FilterContext.FilterContextBuilder<Response>()
                .asyncHandler(new AsyncCompletionHandlerBase()).request(request).build();
        fc = preProcessRequest(fc);
        return httpProvider.execute(fc.getRequest(), fc.getAsyncHandler());
    }

    /**
     * Configure and execute the associated {@link RequestFilter}. This class
     * may decorate the {@link Request} and {@link AsyncHandler}
     * 
     * @param fc
     *            {@link FilterContext}
     * @return {@link FilterContext}
     */
    private <T> FilterContext<T> preProcessRequest(FilterContext<T> fc) throws IOException {
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
        if (fc.getAsyncHandler() instanceof ResumableAsyncHandler) {
            request = ResumableAsyncHandler.class.cast(fc.getAsyncHandler()).adjustRequestRange(request);
        }

        if (request.getRangeOffset() != 0) {
            RequestBuilder builder = new RequestBuilder(request);
            builder.setHeader("Range", "bytes=" + request.getRangeOffset() + "-");
            request = builder.build();
        }
        fc = new FilterContext.FilterContextBuilder<T>(fc).request(request).build();
        return fc;
    }

    @SuppressWarnings("unchecked")
    private static AsyncHttpProvider loadProvider(final String className, final AsyncHttpClientConfig config) {
        try {
            Class<AsyncHttpProvider> providerClass = (Class<AsyncHttpProvider>) Thread.currentThread()
                    .getContextClassLoader().loadClass(className);

            return providerClass.getDeclaredConstructor(new Class[] { AsyncHttpClientConfig.class })
                    .newInstance(config);
        } catch (Throwable t) {
            System.out.println("ClassPath : "
                    + ((java.net.URLClassLoader) Thread.currentThread().getContextClassLoader()).getURLs());
            if (t instanceof InvocationTargetException) {
                final InvocationTargetException ite = (InvocationTargetException) t;
                if (logger.isErrorEnabled()) {
                    logger.error("Unable to instantiate provider {}.  Trying other providers.", className);
                    logger.error(ite.getCause().toString(), ite.getCause());
                }
            }
            // Let's try with another classloader
            try {
                Class<AsyncHttpProvider> providerClass = (Class<AsyncHttpProvider>) AsyncHttpClientImpl.class
                        .getClassLoader().loadClass(className);
                return providerClass.getDeclaredConstructor(new Class[] { AsyncHttpClientConfig.class }).newInstance(
                        config);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static AsyncHttpProvider loadDefaultProvider(String[] providerClassNames, AsyncHttpClientConfig config) {
        AsyncHttpProvider provider;
        for (final String className : providerClassNames) {
            provider = loadProvider(className, config);
            if (provider != null) {
                return provider;
            }
        }
        throw new IllegalStateException("No providers found on the classpath");
    }

    protected BoundRequestBuilder requestBuilder(String reqType, String url) {
        return new BoundRequestBuilder(reqType, config.isUseRawUrl()).setUrl(url).setSignatureCalculator(
                signatureCalculator);
    }

    protected BoundRequestBuilder requestBuilder(Request prototype) {
        return new BoundRequestBuilder(prototype).setSignatureCalculator(signatureCalculator);
    }
}
