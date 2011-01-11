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
 *
 * This class rely on {@link BodyGenerator} and {@link BodyConsumer} for handling the request and response body. No
 * {@link AsyncHandler} are required. As simple as:
 *
 * {@code
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
                .setIdleConnectionInPoolTimeoutInMs(100)
                .setMaximumConnectionsTotal(50)
                .setRequestTimeoutInMs(5 * 60 * 1000)
                .setUrl(getTargetUrl())
                .setHeader("Content-Type", "text/html").build();

        StringBuilder s = new StringBuilder();
        Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new AppendableBodyConsumer(s));
 * }
 * or
 * {@code
    public void ByteArrayOutputStreamBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
                .setUrl(getTargetUrl())
                .build();

        ByteArrayOutputStream o = new ByteArrayOutputStream(10);
        Future<Response> future = client.post(new FileodyGenerator(myFile), new OutputStreamBodyConsumer(o));
    }
 * }
 *
 */
public class SimpleAsyncHttpClient {

    private final static Logger logger = LoggerFactory.getLogger(SimpleAsyncHttpClient.class);
    private final AsyncHttpClientConfig config;
    private final RequestBuilder requestBuilder;
    private AsyncHttpClient asyncHttpClient;

    private SimpleAsyncHttpClient(AsyncHttpClientConfig config, RequestBuilder requestBuilder) {
        this.config = config;
        this.requestBuilder = requestBuilder;
    }

    public Future<Response> post(BodyGenerator bodyGenerator) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");
        r.setBody(bodyGenerator);
        return execute(r, null);
    }

    public Future<Response> post(BodyGenerator bodyGenerator, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("POST");
        r.setBody(bodyGenerator);
        return execute(r, bodyConsumer);
    }

    public Future<Response> post(Request request, BodyGenerator bodyGenerator) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("POST");
        r.setBody(bodyGenerator);
        return execute(r, null);
    }

    public Future<Response> post(Request request, BodyGenerator bodyGenerator, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("POST");
        r.setBody(bodyGenerator);
        return execute(r, bodyConsumer);
    }

    public Future<Response> put(BodyGenerator bodyGenerator, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("PUT");
        r.setBody(bodyGenerator);
        return execute(r,bodyConsumer);
    }

    public Future<Response> put(BodyGenerator bodyGenerator) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("PUT");
        r.setBody(bodyGenerator);
        return execute(r, null);
    }

    public Future<Response> put(Request request, BodyGenerator bodyGenerator, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("PUT");
        r.setBody(bodyGenerator);
        return execute(r, bodyConsumer);
    }

    public Future<Response> put(Request request, BodyGenerator bodyGenerator) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("PUT");
        r.setBody(bodyGenerator);
        return execute(r, null);
    }

    public Future<Response> get() throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        return execute(r, null);
    }

    public Future<Response> get(BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        return execute(r, bodyConsumer);
    }

    public Future<Response> get(Request request) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        return execute(r, null);
    }

    public Future<Response> get(Request request, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        return execute(r, bodyConsumer);
    }

    public Future<Response> delete(Request request) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("DELETE");
        return execute(r, null);
    }

    public Future<Response> delete(Request request, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("DELETE");
        return execute(r, bodyConsumer);
    }

    public Future<Response> delete() throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("DELETE");
        return execute(r, null);
    }

    public Future<Response> delete(BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("DELETE");
        return execute(r, bodyConsumer);
    }

    public Future<Response> head() throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("HEAD");
        return execute(r, null);
    }

    public Future<Response> head(Request request) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("HEAD");
        return execute(r, null);
    }

    public Future<Response> options() throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("OPTIONS");
        return execute(r, null);
    }

    public Future<Response> options(BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("OPTIONS");
        return execute(r, bodyConsumer);
    }

    public Future<Response> options(Request request) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("OPTIONS");
        return execute(r, null);
    }

    public Future<Response> options(Request request, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("OPTIONS");
        return execute(r, bodyConsumer);
    }

    public Future<Response> trace() throws IOException {
        RequestBuilder r = rebuildRequest(requestBuilder.build());
        r.setMethod("TRACE");
        return execute(r, null);
    }

    public Future<Response> trace(Request request, BodyConsumer bodyConsumer) throws IOException {
        RequestBuilder r = rebuildRequest(request);
        r.setMethod("TRACE");
        return execute(r, bodyConsumer);
    }
    
    private RequestBuilder rebuildRequest(Request rb) {
        return new RequestBuilder(rb);
    }

    private Future<Response> execute(RequestBuilder rb, BodyConsumer bodyConsumer) throws IOException {
        return asyncHttpClient().executeRequest(rb.build(), new BodyConsumerAsyncHandler(bodyConsumer));
    }

    private AsyncHttpClient asyncHttpClient() {
        synchronized (config) {
            if (asyncHttpClient == null) {
                asyncHttpClient = new AsyncHttpClient(config);
            }
        }
        return asyncHttpClient;
    }

    public void close() {
        asyncHttpClient().close();
    }

    public final static class Builder {

        private final RequestBuilder requestBuilder = new RequestBuilder("GET");
        private final AsyncHttpClientConfig.Builder configBuilder = new AsyncHttpClientConfig.Builder();
        private Realm.RealmBuilder realmBuilder;
        private ProxyServer.Protocol proxyProtocol = null;
        private String proxyHost = null;
        private String proxyPrincipal = null;
        private String proxyPassword = null;
        private int proxyPort = 80;

        public Builder() {
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

        private Realm.RealmBuilder realm() {
            if (realmBuilder == null) {
                realmBuilder = new Realm.RealmBuilder();
            }
            return realmBuilder;
        }

        public SimpleAsyncHttpClient build() {

            if (realmBuilder != null) {
                configBuilder.setRealm(realmBuilder.build());
            }

            if (proxyHost != null) {
                configBuilder.setProxyServer(new ProxyServer(proxyProtocol, proxyHost, proxyPort, proxyPrincipal, proxyPassword));
            }

            SimpleAsyncHttpClient sc = new SimpleAsyncHttpClient(configBuilder.build(), requestBuilder);
            return sc;
        }
    }

    private final static class BodyConsumerAsyncHandler extends AsyncCompletionHandlerBase {

        private final BodyConsumer bodyConsumer;

        public BodyConsumerAsyncHandler(BodyConsumer bodyConsumer) {
            this.bodyConsumer = bodyConsumer;
        }

        /**
         * {@inheritDoc}
         */
        public STATE onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
            if (bodyConsumer != null) {
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
            try {
                if (bodyConsumer != null) {
                    bodyConsumer.close();
                }
            } catch (IOException ex) {
                logger.warn("Unable to close a BodyConsumer {}", bodyConsumer);
            }
            return super.onCompleted(response);
        }
    }

}
