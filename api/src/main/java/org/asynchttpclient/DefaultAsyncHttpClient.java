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

import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.resumable.ResumableAsyncHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultAsyncHttpClient implements AsyncHttpClient {

    /**
     * Providers that will be searched for, on the classpath, in order when no
     * provider is explicitly specified by the developer.
     */
    private static final String[] DEFAULT_PROVIDERS = {//
    "org.asynchttpclient.providers.netty.NettyAsyncHttpProvider",/**/
    "org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider"//
    };

    private final AsyncHttpProvider httpProvider;
    private final AsyncHttpClientConfig config;
    private final static Logger logger = LoggerFactory.getLogger(DefaultAsyncHttpClient.class);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    /**
     * Default signature calculator to use for all requests constructed by this client instance.
     *
     * @since 1.1
     */
    protected SignatureCalculator signatureCalculator;

    /**
     * Create a new HTTP Asynchronous Client using the default {@link AsyncHttpClientConfig} configuration. The
     * default {@link AsyncHttpProvider} that will be used will be based on the classpath configuration.
     *
     * The default providers will be searched for in this order:
     * <ul>
     *     <li>netty</li>
     *     <li>grizzly</li>
     * </ul>
     *
     * If none of those providers are found, then the engine will throw an IllegalStateException.
     */
    public DefaultAsyncHttpClient() {
        this(new AsyncHttpClientConfig.Builder().build());
    }

    /**
     * Create a new HTTP Asynchronous Client using an implementation of {@link AsyncHttpProvider} and
     * the default {@link AsyncHttpClientConfig} configuration.
     *
     * @param provider a {@link AsyncHttpProvider}
     */
    public DefaultAsyncHttpClient(AsyncHttpProvider provider) {
        this(provider, new AsyncHttpClientConfig.Builder().build());
    }

    /**
     * Create a new HTTP Asynchronous Client using the specified {@link AsyncHttpClientConfig} configuration.
     * This configuration will be passed to the default {@link AsyncHttpProvider} that will be selected based on
     * the classpath configuration.
     *
     * The default providers will be searched for in this order:
     * <ul>
     *     <li>netty</li>
     *     <li>grizzly</li>
     * </ul>
     *
     * If none of those providers are found, then the engine will throw an IllegalStateException.
     *
     * @param config a {@link AsyncHttpClientConfig}
     */
    public DefaultAsyncHttpClient(AsyncHttpClientConfig config) {
        this(loadDefaultProvider(DEFAULT_PROVIDERS, config), config);
    }

    /**
     * Create a new HTTP Asynchronous Client using a {@link AsyncHttpClientConfig} configuration and
     * and a AsyncHttpProvider class' name.
     *
     * @param config        a {@link AsyncHttpClientConfig}
     * @param providerClass a {@link AsyncHttpProvider}
     */
    public DefaultAsyncHttpClient(String providerClass, AsyncHttpClientConfig config) {
        this(loadProvider(providerClass, config), config);
    }

    /**
     * Create a new HTTP Asynchronous Client using a {@link AsyncHttpClientConfig} configuration and
     * and a {@link AsyncHttpProvider}.
     *
     * @param config       a {@link AsyncHttpClientConfig}
     * @param httpProvider a {@link AsyncHttpProvider}
     */
    public DefaultAsyncHttpClient(AsyncHttpProvider httpProvider, AsyncHttpClientConfig config) {
        this.config = config;
        this.httpProvider = httpProvider;
    }

    @Override
    public AsyncHttpProvider getProvider() {
        return httpProvider;
    }

    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true))
            httpProvider.close();
    }

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

    @Override
    public boolean isClosed() {
        return isClosed.get();
    }

    @Override
    public AsyncHttpClientConfig getConfig() {
        return config;
    }

    @Override
    public DefaultAsyncHttpClient setSignatureCalculator(SignatureCalculator signatureCalculator) {
        this.signatureCalculator = signatureCalculator;
        return this;
    }

    @Override
    public BoundRequestBuilder prepareGet(String url) {
        return requestBuilder("GET", url);
    }

    @Override
    public BoundRequestBuilder prepareConnect(String url) {
        return requestBuilder("CONNECT", url);
    }

    @Override
    public BoundRequestBuilder prepareOptions(String url) {
        return requestBuilder("OPTIONS", url);
    }

    @Override
    public BoundRequestBuilder prepareHead(String url) {
        return requestBuilder("HEAD", url);
    }

    @Override
    public BoundRequestBuilder preparePost(String url) {
        return requestBuilder("POST", url);
    }

    @Override
    public BoundRequestBuilder preparePut(String url) {
        return requestBuilder("PUT", url);
    }

    @Override
    public BoundRequestBuilder prepareDelete(String url) {
        return requestBuilder("DELETE", url);
    }

    @Override
    public BoundRequestBuilder preparePatch(String url) {
        return requestBuilder("PATCH", url);
    }

    @Override
    public BoundRequestBuilder prepareTrace(String url) {
        return requestBuilder("TRACE", url);
    }

    @Override
    public BoundRequestBuilder prepareRequest(Request request) {
        return requestBuilder(request);
    }

    @Override
    public <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler) throws IOException {

        FilterContext<T> fc = new FilterContext.FilterContextBuilder<T>().asyncHandler(handler).request(request).build();
        fc = preProcessRequest(fc);

        return httpProvider.execute(fc.getRequest(), fc.getAsyncHandler());
    }

    @Override
    public ListenableFuture<Response> executeRequest(Request request) throws IOException {
        FilterContext<Response> fc = new FilterContext.FilterContextBuilder<Response>().asyncHandler(new AsyncCompletionHandlerBase())
                .request(request).build();
        fc = preProcessRequest(fc);
        return httpProvider.execute(fc.getRequest(), fc.getAsyncHandler());
    }

    /**
     * Configure and execute the associated {@link RequestFilter}. This class may decorate the {@link Request} and {@link AsyncHandler}
     *
     * @param fc {@link FilterContext}
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
            Class<AsyncHttpProvider> providerClass = (Class<AsyncHttpProvider>) Thread.currentThread().getContextClassLoader()
                    .loadClass(className);
            return providerClass.getDeclaredConstructor(new Class[] { AsyncHttpClientConfig.class }).newInstance(config);
        } catch (Throwable t) {
            if (t instanceof InvocationTargetException) {
                final InvocationTargetException ite = (InvocationTargetException) t;
                if (logger.isErrorEnabled()) {
                    logger.error("Unable to instantiate provider {}.  Trying other providers.", className);
                    logger.error(ite.getCause().toString(), ite.getCause());
                }
            }
            // Let's try with another classloader
            try {
                Class<AsyncHttpProvider> providerClass = (Class<AsyncHttpProvider>) DefaultAsyncHttpClient.class.getClassLoader().loadClass(
                        className);
                return providerClass.getDeclaredConstructor(new Class[] { AsyncHttpClientConfig.class }).newInstance(config);
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

    protected BoundRequestBuilder requestBuilder(String method, String url) {
        return new BoundRequestBuilder(this, method, config.isDisableUrlEncodingForBoundRequests()).setUrl(url).setSignatureCalculator(signatureCalculator);
    }

    protected BoundRequestBuilder requestBuilder(Request prototype) {
        return new BoundRequestBuilder(this, prototype).setSignatureCalculator(signatureCalculator);
    }
}
