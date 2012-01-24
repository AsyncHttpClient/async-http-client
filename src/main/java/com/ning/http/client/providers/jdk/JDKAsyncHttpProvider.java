/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.providers.jdk;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.Body;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.PerRequestConfig;
import com.ning.http.client.ProgressAsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.ProxyUtils;
import com.ning.http.util.SslUtils;
import com.ning.http.util.UTF8UrlEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.AuthenticationException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static com.ning.http.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;

public class JDKAsyncHttpProvider implements AsyncHttpProvider {
    private final static Logger logger = LoggerFactory.getLogger(JDKAsyncHttpProvider.class);

    private final static String NTLM_DOMAIN = "http.auth.ntlm.domain";

    private final AsyncHttpClientConfig config;

    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final static int MAX_BUFFERED_BYTES = 8192;

    private final AtomicInteger maxConnections = new AtomicInteger();

    private String jdkNtlmDomain;

    private Authenticator jdkAuthenticator;

    private boolean bufferResponseInMemory = false;

    public JDKAsyncHttpProvider(AsyncHttpClientConfig config) {

        this.config = config;
        AsyncHttpProviderConfig<?, ?> providerConfig = config.getAsyncHttpProviderConfig();
        if (providerConfig != null && JDKAsyncHttpProviderConfig.class.isAssignableFrom(providerConfig.getClass())) {
            configure(JDKAsyncHttpProviderConfig.class.cast(providerConfig));
        }
    }

    private void configure(JDKAsyncHttpProviderConfig config) {
        for (Map.Entry<String, String> e : config.propertiesSet()) {
            System.setProperty(e.getKey(), e.getValue());
        }

        if (config.getProperty(JDKAsyncHttpProviderConfig.FORCE_RESPONSE_BUFFERING) != null) {
            bufferResponseInMemory = true;
        }
    }

    public <T> ListenableFuture<T> execute(Request request, AsyncHandler<T> handler) throws IOException {
        return execute(request, handler, null);
    }

    public <T> ListenableFuture<T> execute(Request request, AsyncHandler<T> handler, ListenableFuture<?> future) throws IOException {
        if (isClose.get()) {
            throw new IOException("Closed");
        }

        if (config.getMaxTotalConnections() > -1 && (maxConnections.get() + 1) > config.getMaxTotalConnections()) {
            throw new IOException(String.format("Too many connections %s", config.getMaxTotalConnections()));
        }

        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        boolean avoidProxy = ProxyUtils.avoidProxy(proxyServer, request);
        Proxy proxy = null;
        if (!avoidProxy && (proxyServer != null || realm != null)) {
            try {
                proxy = configureProxyAndAuth(proxyServer, realm);
            } catch (AuthenticationException e) {
                throw new IOException(e.getMessage());
            }
        }

        HttpURLConnection urlConnection = createUrlConnection(request);

        PerRequestConfig conf = request.getPerRequestConfig();
        int requestTimeout = (conf != null && conf.getRequestTimeoutInMs() != 0) ?
                conf.getRequestTimeoutInMs() : config.getRequestTimeoutInMs();

        JDKDelegateFuture delegate = null;
        if (future != null) {
            delegate = new JDKDelegateFuture(handler, requestTimeout, future, urlConnection);
        }

        JDKFuture f = delegate == null ? new JDKFuture<T>(handler, requestTimeout, urlConnection) : delegate;
        f.touch();

        f.setInnerFuture(config.executorService().submit(new AsyncHttpUrlConnection(urlConnection, request, handler, f)));
        maxConnections.incrementAndGet();

        return f;
    }

    private HttpURLConnection createUrlConnection(Request request) throws IOException {
        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        boolean avoidProxy = ProxyUtils.avoidProxy(proxyServer, request);
        Proxy proxy = null;
        if (!avoidProxy && proxyServer != null || realm != null) {
            try {
                proxy = configureProxyAndAuth(proxyServer, realm);
            } catch (AuthenticationException e) {
                throw new IOException(e.getMessage());
            }
        }

        HttpURLConnection urlConnection = null;
        if (proxy == null) {
            urlConnection =
                    (HttpURLConnection) AsyncHttpProviderUtils.createUri(request.getUrl()).toURL().openConnection(Proxy.NO_PROXY);
        } else {
            urlConnection = (HttpURLConnection) AsyncHttpProviderUtils.createUri(request.getUrl()).toURL().openConnection(proxy);
        }

        if (request.getUrl().startsWith("https")) {
            HttpsURLConnection secure = (HttpsURLConnection) urlConnection;
            SSLContext sslContext = config.getSSLContext();
            if (sslContext == null) {
                try {
                    sslContext = SslUtils.getSSLContext();
                } catch (NoSuchAlgorithmException e) {
                    throw new IOException(e.getMessage());
                } catch (GeneralSecurityException e) {
                    throw new IOException(e.getMessage());
                }
            }
            secure.setSSLSocketFactory(sslContext.getSocketFactory());
            secure.setHostnameVerifier(config.getHostnameVerifier());
        }
        return urlConnection;
    }

    public void close() {
        isClose.set(true);
    }

    public Response prepareResponse(HttpResponseStatus status, HttpResponseHeaders headers, Collection<HttpResponseBodyPart> bodyParts) {
        return new JDKResponse(status, headers, bodyParts);
    }

    private final class AsyncHttpUrlConnection<T> implements Callable<T> {

        private HttpURLConnection urlConnection;
        private Request request;
        private final AsyncHandler<T> asyncHandler;
        private final ListenableFuture<T> future;
        private int currentRedirectCount;
        private AtomicBoolean isAuth = new AtomicBoolean(false);
        private byte[] cachedBytes;
        private int cachedBytesLenght;
        private boolean terminate = true;

        public AsyncHttpUrlConnection(HttpURLConnection urlConnection, Request request, AsyncHandler<T> asyncHandler, ListenableFuture<T> future) {
            this.urlConnection = urlConnection;
            this.request = request;
            this.asyncHandler = asyncHandler;
            this.future = future;
            this.request = request;
        }

        public T call() throws Exception {
            AsyncHandler.STATE state = AsyncHandler.STATE.ABORT;
            try {
                URI uri = null;
                // Encoding with URLConnection is a bit bogus so we need to try both way before setting it
                try {
                    uri = AsyncHttpProviderUtils.createUri(request.getRawUrl());
                } catch (IllegalArgumentException u) {
                    uri = AsyncHttpProviderUtils.createUri(request.getUrl());
                }

                configure(uri, urlConnection, request);
                urlConnection.connect();

                if (TransferCompletionHandler.class.isAssignableFrom(asyncHandler.getClass())) {
                    throw new IllegalStateException(TransferCompletionHandler.class.getName() + "not supported by this provider");
                }

                int statusCode = urlConnection.getResponseCode();

                logger.debug("\n\nRequest {}\n\nResponse {}\n", request, statusCode);

                ResponseStatus status = new ResponseStatus(uri, urlConnection, JDKAsyncHttpProvider.this);
                FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(asyncHandler).request(request).responseStatus(status).build();
                for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                    fc = asyncFilter.filter(fc);
                    if (fc == null) {
                        throw new NullPointerException("FilterContext is null");
                    }
                }

                // The request has changed
                if (fc.replayRequest()) {
                    request = fc.getRequest();
                    urlConnection = createUrlConnection(request);
                    terminate = false;
                    return call();
                }

                boolean redirectEnabled = (request.isRedirectEnabled() || config.isRedirectEnabled());
                if (redirectEnabled && (statusCode == 302 || statusCode == 301)) {

                    if (currentRedirectCount++ < config.getMaxRedirects()) {
                        String location = urlConnection.getHeaderField("Location");
                        URI redirUri = AsyncHttpProviderUtils.getRedirectUri(uri, location);
                        String newUrl = redirUri.toString();

                        if (!newUrl.equals(uri.toString())) {
                            RequestBuilder builder = new RequestBuilder(request);

                            logger.debug("Redirecting to {}", newUrl);

                            request = builder.setUrl(newUrl).build();
                            urlConnection = createUrlConnection(request);
                            terminate = false;
                            return call();
                        }
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
                if (statusCode == 401 && !isAuth.getAndSet(true) && realm != null) {
                    String wwwAuth = urlConnection.getHeaderField("WWW-Authenticate");

                    logger.debug("Sending authentication to {}", request.getUrl());

                    Realm nr = new Realm.RealmBuilder().clone(realm)
                            .parseWWWAuthenticateHeader(wwwAuth)
                            .setUri(URI.create(request.getUrl()).getPath())
                            .setMethodName(request.getMethod())
                            .setUsePreemptiveAuth(true)
                            .build();
                    RequestBuilder builder = new RequestBuilder(request);
                    request = builder.setRealm(nr).build();
                    urlConnection = createUrlConnection(request);
                    terminate = false;
                    return call();
                }

                state = asyncHandler.onStatusReceived(status);
                if (state == AsyncHandler.STATE.CONTINUE) {
                    state = asyncHandler.onHeadersReceived(new ResponseHeaders(uri, urlConnection, JDKAsyncHttpProvider.this));
                }

                if (state == AsyncHandler.STATE.CONTINUE) {
                    InputStream is = getInputStream(urlConnection);
                    String contentEncoding = urlConnection.getHeaderField("Content-Encoding");
                    boolean isGZipped = contentEncoding == null ? false : "gzip".equalsIgnoreCase(contentEncoding);
                    if (isGZipped) {
                        is = new GZIPInputStream(is);
                    }

                    int byteToRead = urlConnection.getContentLength();
                    InputStream stream = is;
                    if (bufferResponseInMemory || byteToRead <= 0) {
                        int[] lengthWrapper = new int[1];
                        byte[] bytes = AsyncHttpProviderUtils.readFully(is, lengthWrapper);
                        stream = new ByteArrayInputStream(bytes, 0, lengthWrapper[0]);
                        byteToRead = lengthWrapper[0];
                    }

                    if (byteToRead > 0) {
                        int minBytes = Math.min(8192, byteToRead);
                        byte[] bytes = new byte[minBytes];
                        int leftBytes = minBytes < 8192 ? minBytes : byteToRead;
                        int read = 0;
                        while (leftBytes > -1) {

                            read = stream.read(bytes);
                            if (read == -1) {
                                break;
                            }

                            future.touch();

                            byte[] b = new byte[read];
                            System.arraycopy(bytes, 0, b, 0, read);
                            leftBytes -= read;
                            asyncHandler.onBodyPartReceived(new ResponseBodyPart(uri, b, JDKAsyncHttpProvider.this, leftBytes > -1));
                        }
                    }

                    if (request.getMethod().equalsIgnoreCase("HEAD")) {
                        asyncHandler.onBodyPartReceived(new ResponseBodyPart(uri, "".getBytes(), JDKAsyncHttpProvider.this, true));
                    }
                }

                if (ProgressAsyncHandler.class.isAssignableFrom(asyncHandler.getClass())) {
                    ProgressAsyncHandler.class.cast(asyncHandler).onHeaderWriteCompleted();
                    ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteCompleted();
                }
                try {
                    T t = asyncHandler.onCompleted();
                    future.content(t);
                    future.done(null);
                    return t;
                } catch (Throwable t) {
                    RuntimeException ex = new RuntimeException();
                    ex.initCause(t);
                    throw ex;
                }
            } catch (Throwable t) {
                logger.debug(t.getMessage(), t);

                if (IOException.class.isAssignableFrom(t.getClass()) && config.getIOExceptionFilters().size() > 0) {
                    FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(asyncHandler)
                            .request(request).ioException(IOException.class.cast(t)).build();

                    try {
                        fc = handleIoException(fc);
                    } catch (FilterException e) {
                        if (config.getMaxTotalConnections() != -1) {
                            maxConnections.decrementAndGet();
                        }
                        future.done(null);
                    }

                    if (fc.replayRequest()) {
                        request = fc.getRequest();
                        urlConnection = createUrlConnection(request);
                        return call();
                    }
                }

                try {
                    future.abort(filterException(t));
                } catch (Throwable t2) {
                    logger.error(t2.getMessage(), t2);
                }
            } finally {
                if (terminate) {
                    if (config.getMaxTotalConnections() != -1) {
                        maxConnections.decrementAndGet();
                    }
                    urlConnection.disconnect();
                    if (jdkNtlmDomain != null) {
                        System.setProperty(NTLM_DOMAIN, jdkNtlmDomain);
                    }
                    Authenticator.setDefault(jdkAuthenticator);
                }
            }
            return null;
        }

        private FilterContext handleIoException(FilterContext fc) throws FilterException {
            for (IOExceptionFilter asyncFilter : config.getIOExceptionFilters()) {
                fc = asyncFilter.filter(fc);
                if (fc == null) {
                    throw new NullPointerException("FilterContext is null");
                }
            }
            return fc;
        }

        private Throwable filterException(Throwable t) {
            if (UnknownHostException.class.isAssignableFrom(t.getClass())) {
                t = new ConnectException(t.getMessage());
            }

            if (SocketTimeoutException.class.isAssignableFrom(t.getClass())) {
                int responseTimeoutInMs = config.getRequestTimeoutInMs();

                if (request.getPerRequestConfig() != null && request.getPerRequestConfig().getRequestTimeoutInMs() != -1) {
                    responseTimeoutInMs = request.getPerRequestConfig().getRequestTimeoutInMs();
                }
                t = new TimeoutException(String.format("No response received after %s", responseTimeoutInMs));
            }

            if (SSLHandshakeException.class.isAssignableFrom(t.getClass())) {
                Throwable t2 = new ConnectException();
                t2.initCause(t);
                t = t2;
            }

            return t;
        }

        private void configure(URI uri, HttpURLConnection urlConnection, Request request) throws IOException, AuthenticationException {

            PerRequestConfig conf = request.getPerRequestConfig();
            int requestTimeout = (conf != null && conf.getRequestTimeoutInMs() != 0) ?
                    conf.getRequestTimeoutInMs() : config.getRequestTimeoutInMs();

            urlConnection.setConnectTimeout(config.getConnectionTimeoutInMs());

            if (requestTimeout != -1)
                urlConnection.setReadTimeout(requestTimeout);

            urlConnection.setInstanceFollowRedirects(false);
            String host = uri.getHost();
            String method = request.getMethod();

            if (request.getVirtualHost() != null) {
                host = request.getVirtualHost();
            }

            if (uri.getPort() == -1 || request.getVirtualHost() != null) {
                urlConnection.setRequestProperty("Host", host);
            } else {
                urlConnection.setRequestProperty("Host", host + ":" + uri.getPort());
            }


            if (config.isCompressionEnabled()) {
                urlConnection.setRequestProperty("Accept-Encoding", "gzip");
            }

            if (!method.equalsIgnoreCase("CONNECT")) {
                FluentCaseInsensitiveStringsMap h = request.getHeaders();
                if (h != null) {
                    for (String name : h.keySet()) {
                        if (!"host".equalsIgnoreCase(name)) {
                            for (String value : h.get(name)) {
                                urlConnection.setRequestProperty(name, value);
                                if (name.equalsIgnoreCase("Expect")) {
                                    throw new IllegalStateException("Expect: 100-Continue not supported");
                                }
                            }
                        }
                    }
                }
            }

            String ka = config.getAllowPoolingConnection() ? "keep-alive" : "close";
            urlConnection.setRequestProperty("Connection", ka);
            ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
            boolean avoidProxy = ProxyUtils.avoidProxy(proxyServer, uri.getHost());
            if (!avoidProxy) {
                urlConnection.setRequestProperty("Proxy-Connection", ka);
                if (proxyServer.getPrincipal() != null) {
                    urlConnection.setRequestProperty("Proxy-Authorization", AuthenticatorUtils.computeBasicAuthentication(proxyServer));
                }

                if (proxyServer.getProtocol().equals(ProxyServer.Protocol.NTLM)) {
                    jdkNtlmDomain = System.getProperty(NTLM_DOMAIN);
                    System.setProperty(NTLM_DOMAIN, proxyServer.getNtlmDomain());
                }
            }

            Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
            if (realm != null && realm.getUsePreemptiveAuth()) {
                switch (realm.getAuthScheme()) {
                    case BASIC:
                        urlConnection.setRequestProperty("Authorization",
                                AuthenticatorUtils.computeBasicAuthentication(realm));
                        break;
                    case DIGEST:
                        if (realm.getNonce() != null && !realm.getNonce().equals("")) {
                            try {
                                urlConnection.setRequestProperty("Authorization",
                                        AuthenticatorUtils.computeDigestAuthentication(realm));
                            } catch (NoSuchAlgorithmException e) {
                                throw new SecurityException(e);
                            }
                        }
                        break;
                    case NTLM:
                        jdkNtlmDomain = System.getProperty(NTLM_DOMAIN);
                        System.setProperty(NTLM_DOMAIN, realm.getDomain());
                        break;
                    case NONE:
                        break;
                    default:
                        throw new IllegalStateException(String.format("Invalid Authentication %s", realm.toString()));
                }

            }

            // Add default accept headers.
            if (request.getHeaders().getFirstValue("Accept") == null) {
                urlConnection.setRequestProperty("Accept", "*/*");
            }

            if (request.getHeaders().getFirstValue("User-Agent") != null) {
                urlConnection.setRequestProperty("User-Agent", request.getHeaders().getFirstValue("User-Agent"));
            } else if (config.getUserAgent() != null) {
                urlConnection.setRequestProperty("User-Agent", config.getUserAgent());
            } else {
                urlConnection.setRequestProperty("User-Agent", AsyncHttpProviderUtils.constructUserAgent(JDKAsyncHttpProvider.class));
            }

            if (request.getCookies() != null && !request.getCookies().isEmpty()) {
                urlConnection.setRequestProperty("Cookie", AsyncHttpProviderUtils.encodeCookies(request.getCookies()));
            }

            String reqType = request.getMethod();
            urlConnection.setRequestMethod(reqType);

            if ("POST".equals(reqType) || "PUT".equals(reqType)) {
                urlConnection.setRequestProperty("Content-Length", "0");
                urlConnection.setDoOutput(true);
                String bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET : request.getBodyEncoding();

                if (cachedBytes != null) {
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(cachedBytesLenght));
                    urlConnection.setFixedLengthStreamingMode(cachedBytesLenght);
                    urlConnection.getOutputStream().write(cachedBytes, 0, cachedBytesLenght);
                } else if (request.getByteData() != null) {
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(request.getByteData().length));
                    urlConnection.setFixedLengthStreamingMode(request.getByteData().length);

                    urlConnection.getOutputStream().write(request.getByteData());
                } else if (request.getStringData() != null) {
                    if (!request.getHeaders().containsKey("Content-Type")) {
                        urlConnection.setRequestProperty("Content-Type", "text/html;" + bodyCharset);
                    }
                    byte[] b = request.getStringData().getBytes(bodyCharset);
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(b.length));
                    urlConnection.getOutputStream().write(b);
                } else if (request.getStreamData() != null) {
                    int[] lengthWrapper = new int[1];
                    cachedBytes = AsyncHttpProviderUtils.readFully(request.getStreamData(), lengthWrapper);
                    cachedBytesLenght = lengthWrapper[0];
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(cachedBytesLenght));
                    urlConnection.setFixedLengthStreamingMode(cachedBytesLenght);

                    urlConnection.getOutputStream().write(cachedBytes, 0, cachedBytesLenght);
                } else if (request.getParams() != null) {
                    StringBuilder sb = new StringBuilder();
                    for (final Map.Entry<String, List<String>> paramEntry : request.getParams()) {
                        final String key = paramEntry.getKey();
                        for (final String value : paramEntry.getValue()) {
                            if (sb.length() > 0) {
                                sb.append("&");
                            }
                            UTF8UrlEncoder.appendEncoded(sb, key);
                            sb.append("=");
                            UTF8UrlEncoder.appendEncoded(sb, value);
                        }
                    }
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(sb.length()));
                    urlConnection.setFixedLengthStreamingMode(sb.length());

                    if (!request.getHeaders().containsKey("Content-Type")) {
                        urlConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    }
                    urlConnection.getOutputStream().write(sb.toString().getBytes(bodyCharset));
                } else if (request.getParts() != null) {
                    int lenght = (int) request.getContentLength();
                    if (lenght != -1) {
                        urlConnection.setRequestProperty("Content-Length", String.valueOf(lenght));
                        urlConnection.setFixedLengthStreamingMode(lenght);
                    }

                    if (lenght == -1) {
                        lenght = MAX_BUFFERED_BYTES;
                    }

                    MultipartRequestEntity mre = AsyncHttpProviderUtils.createMultipartRequestEntity(request.getParts(), request.getParams());

                    urlConnection.setRequestProperty("Content-Type", mre.getContentType());
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(mre.getContentLength()));

                    mre.writeRequest(urlConnection.getOutputStream());
                } else if (request.getEntityWriter() != null) {
                    int lenght = (int) request.getContentLength();
                    if (lenght != -1) {
                        urlConnection.setRequestProperty("Content-Length", String.valueOf(lenght));
                        urlConnection.setFixedLengthStreamingMode(lenght);
                    }
                    request.getEntityWriter().writeEntity(urlConnection.getOutputStream());
                } else if (request.getFile() != null) {
                    File file = request.getFile();
                    if (!file.isFile()) {
                        throw new IOException(String.format(Thread.currentThread()
                                + "File %s is not a file or doesn't exist", file.getAbsolutePath()));
                    }
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(file.length()));
                    urlConnection.setFixedLengthStreamingMode((int) file.length());

                    FileInputStream fis = new FileInputStream(file);
                    try {
                        OutputStream os = urlConnection.getOutputStream();
                        for (final byte[] buffer = new byte[1024 * 16]; ; ) {
                            int read = fis.read(buffer);
                            if (read < 0) {
                                break;
                            }
                            os.write(buffer, 0, read);
                        }
                    } finally {
                        fis.close();
                    }
                } else if (request.getBodyGenerator() != null) {
                    Body body = request.getBodyGenerator().createBody();
                    try {
                        int length = (int) body.getContentLength();
                        if (length < 0) {
                            length = (int) request.getContentLength();
                        }
                        if (length >= 0) {
                            urlConnection.setRequestProperty("Content-Length", String.valueOf(length));
                            urlConnection.setFixedLengthStreamingMode(length);
                        }
                        OutputStream os = urlConnection.getOutputStream();
                        for (ByteBuffer buffer = ByteBuffer.allocate(1024 * 8); ; ) {
                            buffer.clear();
                            if (body.read(buffer) < 0) {
                                break;
                            }
                            os.write(buffer.array(), buffer.arrayOffset(), buffer.position());
                        }
                    } finally {
                        try {
                            body.close();
                        } catch (IOException e) {
                            logger.warn("Failed to close request body: {}", e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    private Proxy configureProxyAndAuth(final ProxyServer proxyServer, final Realm realm) throws AuthenticationException {

        Proxy proxy = null;
        if (proxyServer != null) {

            String proxyHost = proxyServer.getHost().startsWith("http://")
                    ? proxyServer.getHost().substring("http://".length()) : proxyServer.getHost();

            SocketAddress addr = new InetSocketAddress(proxyHost, proxyServer.getPort());
            proxy = new Proxy(Proxy.Type.HTTP, addr);
        }

        final boolean hasProxy = (proxyServer != null && proxyServer.getPrincipal() != null);
        final boolean hasAuthentication = (realm != null && realm.getPrincipal() != null);
        if (hasProxy || hasAuthentication) {

            Field f = null;
            try {
                f = Authenticator.class.getDeclaredField("theAuthenticator");

                f.setAccessible(true);
                jdkAuthenticator = (Authenticator) f.get(Authenticator.class);
            } catch (NoSuchFieldException e) {
            } catch (IllegalAccessException e) {
            }


            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (hasProxy && getRequestingHost().equals(proxyServer.getHost())
                            && getRequestingPort() == proxyServer.getPort()) {
                        String password = "";
                        if (proxyServer.getPassword() != null) {
                            password = proxyServer.getPassword();
                        }
                        return new PasswordAuthentication(proxyServer.getPrincipal(), password.toCharArray());
                    }

                    if (hasAuthentication) {
                        return new PasswordAuthentication(realm.getPrincipal(), realm.getPassword().toCharArray());
                    }

                    return super.getPasswordAuthentication();
                }
            });
        } else {
            Authenticator.setDefault(null);
        }
        return proxy;
    }

    private InputStream getInputStream(HttpURLConnection urlConnection) throws IOException {
        if (urlConnection.getResponseCode() < 400) {
            return urlConnection.getInputStream();
        } else {
            InputStream ein = urlConnection.getErrorStream();
            return (ein != null)
                    ? ein : new ByteArrayInputStream(new byte[0]);
        }
    }

}
