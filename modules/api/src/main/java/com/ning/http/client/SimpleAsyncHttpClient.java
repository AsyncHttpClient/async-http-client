/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client;

import com.ning.http.client.resumable.ResumableAsyncHandler;
import com.ning.http.client.resumable.ResumableIOExceptionFilter;
import com.ning.http.client.simple.HeaderMap;
import com.ning.http.client.simple.SimpleAHCTransferListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Simple implementation of {@link AsyncHttpClient} and it's related builders ({@link com.ning.http.client.AsyncHttpClientConfig},
 * {@link Realm}, {@link com.ning.http.client.ProxyServer} and {@link com.ning.http.client.AsyncHandler}. You can
 * build powerful application by just using this class.
 * <p/>
 * This class rely on {@link BodyGenerator} and {@link BodyConsumer} for handling the request and response body. No
 * {@link AsyncHandler} are required. As simple as:
 * <blockquote><pre>
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 * .setIdleConnectionInPoolTimeoutInMs(100)
 * .setMaximumConnectionsTotal(50)
 * .setRequestTimeoutInMs(5 * 60 * 1000)
 * .setUrl(getTargetUrl())
 * .setHeader("Content-Type", "text/html").build();
 * <p/>
 * StringBuilder s = new StringBuilder();
 * Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new AppendableBodyConsumer(s));
 * </pre></blockquote>
 * or
 * <blockquote><pre>
 * public void ByteArrayOutputStreamBodyConsumerTest() throws Throwable {
 * <p/>
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 * .setUrl(getTargetUrl())
 * .build();
 * <p/>
 * ByteArrayOutputStream o = new ByteArrayOutputStream(10);
 * Future<Response> future = client.post(new FileodyGenerator(myFile), new OutputStreamBodyConsumer(o));
 * </pre></blockquote>
 */
public class SimpleAsyncHttpClient {

    private final static Logger logger = LoggerFactory.getLogger(SimpleAsyncHttpClient.class);
    private final AsyncHttpClientConfig config;
    private final RequestBuilder requestBuilder;
    private AsyncHttpClient asyncHttpClient;
    private final ThrowableHandler defaultThrowableHandler;
    private final boolean resumeEnabled;
    private final ErrorDocumentBehaviour errorDocumentBehaviour;
    private final SimpleAHCTransferListener listener;
    private final boolean derived;

    private SimpleAsyncHttpClient(AsyncHttpClientConfig config, RequestBuilder requestBuilder, ThrowableHandler defaultThrowableHandler, ErrorDocumentBehaviour errorDocumentBehaviour, boolean resumeEnabled, AsyncHttpClient ahc, SimpleAHCTransferListener listener) {
        this.config = config;
        this.requestBuilder = requestBuilder;
        this.defaultThrowableHandler = defaultThrowableHandler;
        this.resumeEnabled = resumeEnabled;
        this.errorDocumentBehaviour = errorDocumentBehaviour;
        this.asyncHttpClient = ahc;
        this.listener = listener;

        this.derived = ahc != null;
    }

    public Future<Response> post(Part... parts) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");

        for (Part part : parts) {
            r.addBodyPart(part);
        }

        return execute(r, null, null);
    }

    public Future<Response> post(BodyConsumer consumer, Part... parts) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");

        for (Part part : parts) {
            r.addBodyPart(part);
        }

        return execute(r, consumer, null);
    }

    public Future<Response> post(BodyGenerator bodyGenerator) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");
        r.setBody(bodyGenerator);
        return execute(r, null, null);
    }

    public Future<Response> post(BodyGenerator bodyGenerator, ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");
        r.setBody(bodyGenerator);
        return execute(r, null, throwableHandler);
    }

    public Future<Response> post(BodyGenerator bodyGenerator, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");
        r.setBody(bodyGenerator);
        return execute(r, bodyConsumer, null);
    }

    public Future<Response> post(BodyGenerator bodyGenerator, BodyConsumer bodyConsumer, ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");
        r.setBody(bodyGenerator);
        return execute(r, bodyConsumer, throwableHandler);
    }

    public Future<Response> put(Part... parts) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");

        for (Part part : parts) {
            r.addBodyPart(part);
        }

        return execute(r, null, null);
    }

    public Future<Response> put(BodyConsumer consumer, Part... parts) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");

        for (Part part : parts) {
            r.addBodyPart(part);
        }

        return execute(r, consumer, null);
    }

    public Future<Response> put(BodyGenerator bodyGenerator, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("PUT");
        r.setBody(bodyGenerator);
        return execute(r, bodyConsumer, null);
    }

    public Future<Response> put(BodyGenerator bodyGenerator, BodyConsumer bodyConsumer, ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("PUT");
        r.setBody(bodyGenerator);
        return execute(r, bodyConsumer, throwableHandler);
    }

    public Future<Response> put(BodyGenerator bodyGenerator) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("PUT");
        r.setBody(bodyGenerator);
        return execute(r, null, null);
    }

    public Future<Response> put(BodyGenerator bodyGenerator, ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("PUT");
        r.setBody(bodyGenerator);
        return execute(r, null, throwableHandler);
    }

    public Future<Response> get() throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        return execute(r, null, null);
    }

    public Future<Response> get(ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        return execute(r, null, throwableHandler);
    }

    public Future<Response> get(BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        return execute(r, bodyConsumer, null);
    }

    public Future<Response> get(BodyConsumer bodyConsumer, ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        return execute(r, bodyConsumer, throwableHandler);
    }

    public Future<Response> delete() throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("DELETE");
        return execute(r, null, null);
    }

    public Future<Response> delete(ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("DELETE");
        return execute(r, null, throwableHandler);
    }

    public Future<Response> delete(BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("DELETE");
        return execute(r, bodyConsumer, null);
    }

    public Future<Response> delete(BodyConsumer bodyConsumer, ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("DELETE");
        return execute(r, bodyConsumer, throwableHandler);
    }

    public Future<Response> head() throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("HEAD");
        return execute(r, null, null);
    }

    public Future<Response> head(ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("HEAD");
        return execute(r, null, throwableHandler);
    }

    public Future<Response> options() throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("OPTIONS");
        return execute(r, null, null);
    }

    public Future<Response> options(ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("OPTIONS");
        return execute(r, null, throwableHandler);
    }

    public Future<Response> options(BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("OPTIONS");
        return execute(r, bodyConsumer, null);
    }

    public Future<Response> options(BodyConsumer bodyConsumer, ThrowableHandler throwableHandler) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("OPTIONS");
        return execute(r, bodyConsumer, throwableHandler);
    }

    private RequestBuilder rebuildRequest(Request rb) {
        return new RequestBuilder(rb);
    }

    private Future<Response> execute(RequestBuilder rb, BodyConsumer bodyConsumer, ThrowableHandler throwableHandler) throws IOException {
        if (throwableHandler == null) {
            throwableHandler = defaultThrowableHandler;
        }

        Request request = rb.build();
        ProgressAsyncHandler<Response> handler = new BodyConsumerAsyncHandler(bodyConsumer, throwableHandler, errorDocumentBehaviour, request.getUrl(), listener);

        if (resumeEnabled && request.getMethod().equals("GET") &&
                bodyConsumer != null && bodyConsumer instanceof ResumableBodyConsumer) {
            ResumableBodyConsumer fileBodyConsumer = (ResumableBodyConsumer) bodyConsumer;
            long length = fileBodyConsumer.getTransferredBytes();
            fileBodyConsumer.resume();
            handler = new ResumableBodyConsumerAsyncHandler(length, handler);
        }

        return asyncHttpClient().executeRequest(request, handler);
    }

    private AsyncHttpClient asyncHttpClient() {
        synchronized (config) {
            if (asyncHttpClient == null) {
                asyncHttpClient = new AsyncHttpClient(config);
            }
        }
        return asyncHttpClient;
    }

    /**
     * Close the underlying AsyncHttpClient for this instance.
     * <p/>
     * If this instance is derived from another instance, this method does
     * nothing as the client instance is managed by the original
     * SimpleAsyncHttpClient.
     *
     * @see #derive()
     * @see AsyncHttpClient#close()
     */
    public void close() {
        if (!derived && asyncHttpClient != null) {
            asyncHttpClient.close();
        }
    }

    /**
     * Returns a Builder for a derived SimpleAsyncHttpClient that uses the same
     * instance of {@link AsyncHttpClient} to execute requests.
     * <p/>
     * <p/>
     * <p/>
     * The original SimpleAsyncHttpClient is responsible for managing the
     * underlying AsyncHttpClient. For the derived instance, {@link #close()} is
     * a NOOP. If the original SimpleAsyncHttpClient is closed, all derived
     * instances become invalid.
     *
     * @return a Builder for a derived SimpleAsyncHttpClient that uses the same
     *         instance of {@link AsyncHttpClient} to execute requests, never
     *         {@code null}.
     */
    public DerivedBuilder derive() {
        return new Builder(this);
    }

    public enum ErrorDocumentBehaviour {
        /**
         * Write error documents as usual via
         * {@link BodyConsumer#consume(java.nio.ByteBuffer)}.
         */
        WRITE,

        /**
         * Accumulate error documents in memory but do not consume.
         */
        ACCUMULATE,

        /**
         * Omit error documents. An error document will neither be available in
         * the response nor written via a {@link BodyConsumer}.
         */
        OMIT;
    }

    /**
     * This interface contains possible configuration changes for a derived SimpleAsyncHttpClient.
     *
     * @see SimpleAsyncHttpClient#derive()
     */
    public interface DerivedBuilder {

        DerivedBuilder setFollowRedirects(boolean followRedirects);

        DerivedBuilder setVirtualHost(String virtualHost);

        DerivedBuilder setUrl(String url);

        DerivedBuilder setParameters(FluentStringsMap parameters) throws IllegalArgumentException;

        DerivedBuilder setParameters(Map<String, Collection<String>> parameters) throws IllegalArgumentException;

        DerivedBuilder setHeaders(Map<String, Collection<String>> headers);

        DerivedBuilder setHeaders(FluentCaseInsensitiveStringsMap headers);

        DerivedBuilder setHeader(String name, String value);

        DerivedBuilder addQueryParameter(String name, String value);

        DerivedBuilder addParameter(String key, String value) throws IllegalArgumentException;

        DerivedBuilder addHeader(String name, String value);

        DerivedBuilder addCookie(Cookie cookie);

        DerivedBuilder addBodyPart(Part part) throws IllegalArgumentException;

        DerivedBuilder setResumableDownload(boolean resume);

        SimpleAsyncHttpClient build();
    }

    public final static class Builder implements DerivedBuilder {

        private final RequestBuilder requestBuilder;
        private final AsyncHttpClientConfig.Builder configBuilder = new AsyncHttpClientConfig.Builder();
        private Realm.RealmBuilder realmBuilder = null;
        private ProxyServer.Protocol proxyProtocol = null;
        private String proxyHost = null;
        private String proxyPrincipal = null;
        private String proxyPassword = null;
        private int proxyPort = 80;
        private ThrowableHandler defaultThrowableHandler = null;
        private boolean enableResumableDownload = false;
        private ErrorDocumentBehaviour errorDocumentBehaviour = ErrorDocumentBehaviour.WRITE;
        private AsyncHttpClient ahc = null;
        private SimpleAHCTransferListener listener = null;

        public Builder() {
            requestBuilder = new RequestBuilder("GET", false);
        }

        private Builder(SimpleAsyncHttpClient client) {
            this.requestBuilder = new RequestBuilder(client.requestBuilder.build());
            this.defaultThrowableHandler = client.defaultThrowableHandler;
            this.errorDocumentBehaviour = client.errorDocumentBehaviour;
            this.enableResumableDownload = client.resumeEnabled;
            this.ahc = client.asyncHttpClient();
            this.listener = client.listener;
        }

        public Builder addBodyPart(Part part) throws IllegalArgumentException {
            requestBuilder.addBodyPart(part);
            return this;
        }

        public Builder addCookie(Cookie cookie) {
            requestBuilder.addCookie(cookie);
            return this;
        }

        public Builder addHeader(String name, String value) {
            requestBuilder.addHeader(name, value);
            return this;
        }

        public Builder addParameter(String key, String value) throws IllegalArgumentException {
            requestBuilder.addParameter(key, value);
            return this;
        }

        public Builder addQueryParameter(String name, String value) {
            requestBuilder.addQueryParameter(name, value);
            return this;
        }

        public Builder setHeader(String name, String value) {
            requestBuilder.setHeader(name, value);
            return this;
        }

        public Builder setHeaders(FluentCaseInsensitiveStringsMap headers) {
            requestBuilder.setHeaders(headers);
            return this;
        }

        public Builder setHeaders(Map<String, Collection<String>> headers) {
            requestBuilder.setHeaders(headers);
            return this;
        }

        public Builder setParameters(Map<String, Collection<String>> parameters) throws IllegalArgumentException {
            requestBuilder.setParameters(parameters);
            return this;
        }

        public Builder setParameters(FluentStringsMap parameters) throws IllegalArgumentException {
            requestBuilder.setParameters(parameters);
            return this;
        }

        public Builder setUrl(String url) {
            requestBuilder.setUrl(url);
            return this;
        }

        public Builder setVirtualHost(String virtualHost) {
            requestBuilder.setVirtualHost(virtualHost);
            return this;
        }

        public Builder setFollowRedirects(boolean followRedirects) {
            requestBuilder.setFollowRedirects(followRedirects);
            return this;
        }

        public Builder setMaximumConnectionsTotal(int defaultMaxTotalConnections) {
            configBuilder.setMaximumConnectionsTotal(defaultMaxTotalConnections);
            return this;
        }

        public Builder setMaximumConnectionsPerHost(int defaultMaxConnectionPerHost) {
            configBuilder.setMaximumConnectionsPerHost(defaultMaxConnectionPerHost);
            return this;
        }

        public Builder setConnectionTimeoutInMs(int connectionTimeuot) {
            configBuilder.setConnectionTimeoutInMs(connectionTimeuot);
            return this;
        }

        public Builder setIdleConnectionInPoolTimeoutInMs(int defaultIdleConnectionInPoolTimeoutInMs) {
            configBuilder.setIdleConnectionInPoolTimeoutInMs(defaultIdleConnectionInPoolTimeoutInMs);
            return this;
        }

        public Builder setRequestTimeoutInMs(int defaultRequestTimeoutInMs) {
            configBuilder.setRequestTimeoutInMs(defaultRequestTimeoutInMs);
            return this;
        }

        public Builder setMaximumNumberOfRedirects(int maxDefaultRedirects) {
            configBuilder.setMaximumNumberOfRedirects(maxDefaultRedirects);
            return this;
        }

        public Builder setCompressionEnabled(boolean compressionEnabled) {
            configBuilder.setCompressionEnabled(compressionEnabled);
            return this;
        }

        public Builder setUserAgent(String userAgent) {
            configBuilder.setUserAgent(userAgent);
            return this;
        }

        public Builder setAllowPoolingConnection(boolean allowPoolingConnection) {
            configBuilder.setAllowPoolingConnection(allowPoolingConnection);
            return this;
        }

        public Builder setScheduledExecutorService(ScheduledExecutorService reaper) {
            configBuilder.setScheduledExecutorService(reaper);
            return this;
        }

        public Builder setExecutorService(ExecutorService applicationThreadPool) {
            configBuilder.setExecutorService(applicationThreadPool);
            return this;
        }

        public Builder setSSLEngineFactory(SSLEngineFactory sslEngineFactory) {
            configBuilder.setSSLEngineFactory(sslEngineFactory);
            return this;
        }

        public Builder setSSLContext(final SSLContext sslContext) {
            configBuilder.setSSLContext(sslContext);
            return this;
        }

        public Builder setRequestCompressionLevel(int requestCompressionLevel) {
            configBuilder.setRequestCompressionLevel(requestCompressionLevel);
            return this;
        }

        public Builder setRealmDomain(String domain) {
            realm().setDomain(domain);
            return this;
        }

        public Builder setRealmPrincipal(String principal) {
            realm().setPrincipal(principal);
            return this;
        }

        public Builder setRealmPassword(String password) {
            realm().setPassword(password);
            return this;
        }

        public Builder setRealmScheme(Realm.AuthScheme scheme) {
            realm().setScheme(scheme);
            return this;
        }

        public Builder setRealmName(String realmName) {
            realm().setRealmName(realmName);
            return this;
        }

        public Builder setRealmUsePreemptiveAuth(boolean usePreemptiveAuth) {
            realm().setUsePreemptiveAuth(usePreemptiveAuth);
            return this;
        }

        public Builder setRealmEnconding(String enc) {
            realm().setEnconding(enc);
            return this;
        }

        public Builder setProxyProtocol(ProxyServer.Protocol protocol) {
            this.proxyProtocol = protocol;
            return this;
        }

        public Builder setProxyHost(String host) {
            this.proxyHost = host;
            return this;
        }

        public Builder setProxyPrincipal(String principal) {
            this.proxyPrincipal = principal;
            return this;
        }

        public Builder setProxyPassword(String password) {
            this.proxyPassword = password;
            return this;
        }

        public Builder setProxyPort(int port) {
            this.proxyPort = port;
            return this;
        }

        public Builder setDefaultThrowableHandler(ThrowableHandler throwableHandler) {
            this.defaultThrowableHandler = throwableHandler;
            return this;
        }

        /**
         * This setting controls whether an error document should be written via
         * the {@link BodyConsumer} after an error status code was received (e.g.
         * 404). Default is {@link ErrorDocumentBehaviour#WRITE}.
         */
        public Builder setErrorDocumentBehaviour(ErrorDocumentBehaviour behaviour) {
            this.errorDocumentBehaviour = behaviour;
            return this;
        }

        /**
         * Enable resumable downloads for the SimpleAHC. Resuming downloads will only work for GET requests
         * with an instance of {@link ResumableBodyConsumer}.
         */
        public Builder setResumableDownload(boolean enableResumableDownload) {
            this.enableResumableDownload = enableResumableDownload;
            return this;
        }

        private Realm.RealmBuilder realm() {
            if (realmBuilder == null) {
                realmBuilder = new Realm.RealmBuilder();
            }
            return realmBuilder;
        }

        /**
         * Set the listener to notify about connection progress.
         */
        public Builder setListener(SimpleAHCTransferListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * Set the number of time a request will be retried when an {@link java.io.IOException} occurs because of a Network exception.
         *
         * @param maxRequestRetry the number of time a request will be retried
         * @return this
         */
        public Builder setMaxRequestRetry(int maxRequestRetry) {
            configBuilder.setMaxRequestRetry(maxRequestRetry);
            return this;
        }

        public SimpleAsyncHttpClient build() {

            if (realmBuilder != null) {
                configBuilder.setRealm(realmBuilder.build());
            }

            if (proxyHost != null) {
                configBuilder.setProxyServer(new ProxyServer(proxyProtocol, proxyHost, proxyPort, proxyPrincipal, proxyPassword));
            }

            configBuilder.addIOExceptionFilter(new ResumableIOExceptionFilter());

            SimpleAsyncHttpClient sc = new SimpleAsyncHttpClient(configBuilder.build(), requestBuilder, defaultThrowableHandler, errorDocumentBehaviour, enableResumableDownload, ahc, listener);

            return sc;
        }
    }

    private final static class ResumableBodyConsumerAsyncHandler
            extends ResumableAsyncHandler<Response>
            implements ProgressAsyncHandler<Response> {

        private final ProgressAsyncHandler<Response> delegate;

        public ResumableBodyConsumerAsyncHandler(long byteTransferred, ProgressAsyncHandler<Response> delegate) {
            super(byteTransferred, delegate);
            this.delegate = delegate;
        }

        public com.ning.http.client.AsyncHandler.STATE onHeaderWriteCompleted() {
            return delegate.onHeaderWriteCompleted();
        }

        public com.ning.http.client.AsyncHandler.STATE onContentWriteCompleted() {
            return delegate.onContentWriteCompleted();
        }

        public com.ning.http.client.AsyncHandler.STATE onContentWriteProgress(long amount, long current, long total) {
            return delegate.onContentWriteProgress(amount, current, total);
        }
    }

    private final static class BodyConsumerAsyncHandler extends AsyncCompletionHandlerBase {

        private final BodyConsumer bodyConsumer;
        private final ThrowableHandler exceptionHandler;
        private final ErrorDocumentBehaviour errorDocumentBehaviour;
        private final String url;
        private final SimpleAHCTransferListener listener;

        private boolean accumulateBody = false;
        private boolean omitBody = false;
        private int amount = 0;
        private long total = -1;

        public BodyConsumerAsyncHandler(BodyConsumer bodyConsumer, ThrowableHandler exceptionHandler, ErrorDocumentBehaviour errorDocumentBehaviour, String url, SimpleAHCTransferListener listener) {
            this.bodyConsumer = bodyConsumer;
            this.exceptionHandler = exceptionHandler;
            this.errorDocumentBehaviour = errorDocumentBehaviour;
            this.url = url;
            this.listener = listener;
        }

        @Override
        public void onThrowable(Throwable t) {
            try {
                if (exceptionHandler != null) {
                    exceptionHandler.onThrowable(t);
                } else {
                    super.onThrowable(t);
                }
            } finally {
                closeConsumer();
            }
        }

        /**
         * {@inheritDoc}
         */
        public STATE onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
            fireReceived(content);
            if (omitBody) {
                return STATE.CONTINUE;
            }

            if (!accumulateBody && bodyConsumer != null) {
                bodyConsumer.consume(content.getBodyByteBuffer());
            } else {
                return super.onBodyPartReceived(content);
            }
            return STATE.CONTINUE;
        }


        /**
         * {@inheritDoc}
         */
        @Override
        public Response onCompleted(Response response) throws Exception {
            fireCompleted(response);
            closeConsumer();
            return super.onCompleted(response);
        }

        private void closeConsumer() {
            try {
                if (bodyConsumer != null) {
                    bodyConsumer.close();
                }
            } catch (IOException ex) {
                logger.warn("Unable to close a BodyConsumer {}", bodyConsumer);
            }
        }

        @Override
        public STATE onStatusReceived(HttpResponseStatus status)
                throws Exception {
            fireStatus(status);

            if (isErrorStatus(status)) {
                switch (errorDocumentBehaviour) {
                    case ACCUMULATE:
                        accumulateBody = true;
                        break;
                    case OMIT:
                        omitBody = true;
                        break;
                    default:
                        break;
                }
            }
            return super.onStatusReceived(status);
        }

        private boolean isErrorStatus(HttpResponseStatus status) {
            return status.getStatusCode() >= 400;
        }

        @Override
        public STATE onHeadersReceived(HttpResponseHeaders headers)
                throws Exception {
            calculateTotal(headers);

            fireHeaders(headers);

            return super.onHeadersReceived(headers);
        }

        private void calculateTotal(HttpResponseHeaders headers) {
            String length = headers.getHeaders().getFirstValue("Content-Length");

            try {
                total = Integer.valueOf(length);
            } catch (Exception e) {
                total = -1;
            }
        }

        @Override
        public STATE onContentWriteProgress(long amount, long current, long total) {
            fireSent(url, amount, current, total);
            return super.onContentWriteProgress(amount, current, total);
        }

        private void fireStatus(HttpResponseStatus status) {
            if (listener != null) {
                listener.onStatus(url, status.getStatusCode(), status.getStatusText());
            }
        }

        private void fireReceived(HttpResponseBodyPart content) {
            int remaining = content.getBodyByteBuffer().remaining();

            amount += remaining;

            if (listener != null) {
                listener.onBytesReceived(url, amount, remaining, total);
            }
        }

        private void fireHeaders(HttpResponseHeaders headers) {
            if (listener != null) {
                listener.onHeaders(url, new HeaderMap(headers.getHeaders()));
            }
        }

        private void fireSent(String url, long amount, long current, long total) {
            if (listener != null) {
                listener.onBytesSent(url, amount, current, total);
            }
        }

        private void fireCompleted(Response response) {
            if (listener != null) {
                listener.onCompleted(url, response.getStatusCode(), response.getStatusText());
            }
        }
    }

}
