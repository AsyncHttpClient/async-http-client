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
package com.ning.http.client.providers.jdk;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.PerRequestConfig;
import com.ning.http.client.ProgressAsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.SslUtils;
import com.ning.http.util.UTF8UrlEncoder;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

import javax.naming.AuthenticationException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
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
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

public class JDKAsyncHttpProvider implements AsyncHttpProvider<HttpURLConnection> {
    private final static Logger logger = LogManager.getLogger(JDKAsyncHttpProvider.class);

    private final static String NTLM_DOMAIN = "http.auth.ntlm.domain";

    private final AsyncHttpClientConfig config;

    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final ConnectionsPool<String, URLConnection> connectionsPool;

    private final static int MAX_BUFFERED_BYTES = 8192;

    private final AtomicInteger maxConnections = new AtomicInteger();

    private String jdkNtlmDomain;

    private Authenticator jdkAuthenticator;

    public JDKAsyncHttpProvider(AsyncHttpClientConfig config) {

        this.config = config;

        // This is dangerous as we can't catch a wrong typed ConnectionsPool
        ConnectionsPool<String, URLConnection> cp = (ConnectionsPool<String, URLConnection>) config.getConnectionsPool();
        if (cp == null) {
            cp = new JDKConnectionsPool(config);
        }
        this.connectionsPool = cp;

        AsyncHttpProviderConfig<?, ?> providerConfig = config.getAsyncHttpProviderConfig();
        if (providerConfig != null && JDKAsyncHttpProviderConfig.class.isAssignableFrom(providerConfig.getClass())) {
            configure(JDKAsyncHttpProviderConfig.class.cast(providerConfig));
        }
    }

    private void configure(JDKAsyncHttpProviderConfig config) {
    }
    
    public <T> Future<T> execute(Request request, AsyncHandler<T> handler) throws IOException {

        if (isClose.get()) {
            throw new IOException("Closed");
        }

        if (config.getMaxTotalConnections() > -1 && (maxConnections.get() + 1) > config.getMaxTotalConnections()) {
            throw new IOException(String.format("Too many connections %s", config.getMaxTotalConnections()));
        }

        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        Proxy proxy = null;
        if (proxyServer != null || request.getRealm() != null) {
            try {
                proxy = configureProxyAndAuth(proxyServer, request.getRealm());
            } catch (AuthenticationException e) {
                throw new IOException(e.getMessage());
            }
        }

        HttpURLConnection urlConnection = createUrlConnection(request);
        JDKFuture f = new JDKFuture<T>(handler, config.getRequestTimeoutInMs());
        f.setInnerFuture(config.executorService().submit(new AsyncHttpUrlConnection(urlConnection, request, handler, f)));
        maxConnections.incrementAndGet();
        
        return f;
    }

    private HttpURLConnection createUrlConnection(Request request) throws IOException {
        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        Proxy proxy = null;
        if (proxyServer != null || request.getRealm() != null) {
            try {
                proxy = configureProxyAndAuth(proxyServer, request.getRealm());
            } catch (AuthenticationException e) {
                throw new IOException(e.getMessage());
            }
        }

        HttpURLConnection urlConnection = null;
        if (proxy == null) {
            urlConnection = (HttpURLConnection) AsyncHttpProviderUtils.createUri(request.getUrl()).toURL().openConnection();
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
            secure.setHostnameVerifier(new HostnameVerifier() {

                public boolean verify(String s, SSLSession sslSession) {
                    return true;
                }
            });
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

        private final HttpURLConnection urlConnection;
        private final Request request;
        private final AsyncHandler<T> asyncHandler;
        private final JDKFuture future;
        private Request currentRequest;
        // We kept a reference to the original one for debugging purpose
        private HttpURLConnection currentUrlConnection;
        private int currentRedirectCount;

        public AsyncHttpUrlConnection(HttpURLConnection urlConnection, Request request, AsyncHandler<T> asyncHandler, JDKFuture future) {
            this.urlConnection = urlConnection;
            this.request = request;
            this.asyncHandler = asyncHandler;
            this.future = future;
            this.currentRequest = request;
            this.currentUrlConnection = urlConnection;
        }

        public T call() throws Exception {
            AsyncHandler.STATE state = AsyncHandler.STATE.ABORT;
            try {
                URI uri = null;
                // Encoding with URLConnection is a bit bogus so we need to try both way before setting it
                try {
                    uri = AsyncHttpProviderUtils.createUri(currentRequest.getRawUrl());
                } catch (IllegalArgumentException u) {
                    uri = AsyncHttpProviderUtils.createUri(currentRequest.getUrl());
                }

                configure(uri, currentUrlConnection, currentRequest);
                currentUrlConnection.connect();

                int statusCode = currentUrlConnection.getResponseCode();
                boolean redirectEnabled = (request.isRedirectEnabled() || config.isRedirectEnabled());
                if (redirectEnabled && (statusCode == 302 || statusCode == 301)) {

                    if (currentRedirectCount++ < config.getMaxRedirects()) {
                        String location = currentUrlConnection.getHeaderField("Location");
                        if (location.startsWith("/")) {
                            location = AsyncHttpProviderUtils.getBaseUrl(uri) + location;
                        }

                        if (!location.equals(uri.toString())) {
                            URI newUri = AsyncHttpProviderUtils.createUri(location);

                            RequestBuilder builder = new RequestBuilder(currentRequest);
                            String newUrl = newUri.toString();

                            if (logger.isDebugEnabled()) {
                                logger.debug(String.format("[" + Thread.currentThread().getName() + "] Redirecting to %s", newUrl));
                            }
                            currentRequest = builder.setUrl(newUrl).build();
                            currentUrlConnection = createUrlConnection(currentRequest);
                            return call();
                        }
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                state = asyncHandler.onStatusReceived(new ResponseStatus(uri, currentUrlConnection, JDKAsyncHttpProvider.this));
                if (state == AsyncHandler.STATE.CONTINUE) {
                    state = asyncHandler.onHeadersReceived(new ResponseHeaders(uri, currentUrlConnection, JDKAsyncHttpProvider.this));
                }

                if (state == AsyncHandler.STATE.CONTINUE) {
                    InputStream is = getInputStream(currentUrlConnection);
                    String contentEncoding = currentUrlConnection.getHeaderField("Content-Encoding");
                    boolean isGZipped = contentEncoding == null ? false : "gzip".equalsIgnoreCase(contentEncoding);
                    if (isGZipped) {
                        is = new GZIPInputStream(is);
                    }

                    int[] lengthWrapper = new int[1];
                    byte[] bytes = AsyncHttpProviderUtils.readFully(is, lengthWrapper);
                    if (lengthWrapper[0] > 0) {
                        byte[] body = new byte[lengthWrapper[0]];
                        System.arraycopy(bytes, 0, body, 0, lengthWrapper[0]);

                        asyncHandler.onBodyPartReceived(new ResponseBodyPart(uri, body, JDKAsyncHttpProvider.this));
                    }
                }

                if (ProgressAsyncHandler.class.isAssignableFrom(asyncHandler.getClass())) {
                    ProgressAsyncHandler.class.cast(asyncHandler).onHeaderWriteCompleted();
                    ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteCompleted();
                }
                return asyncHandler.onCompleted();

            } catch (Throwable t) {
                if (logger.isDebugEnabled()) {
                    logger.debug(t);
                }

                try {
                    future.abort(filterException(t));
                } catch (Throwable t2) {
                    logger.error(t2);
                }
            } finally {
                if (config.getMaxTotalConnections() != -1) {
                    maxConnections.decrementAndGet();
                }
                currentUrlConnection.disconnect();
                if (jdkNtlmDomain != null) {
                    System.setProperty(NTLM_DOMAIN, jdkNtlmDomain);
                }
                Authenticator.setDefault(jdkAuthenticator);
            }
            return null;
        }

        private Throwable filterException(Throwable t) {
            if (UnknownHostException.class.isAssignableFrom(t.getClass())) {
                t = new ConnectException(t.getMessage());
            }

            if (SocketTimeoutException.class.isAssignableFrom(t.getClass())) {
                t = new TimeoutException("Request timed out.");
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
            String method = request.getReqType();

            if (request.getVirtualHost() != null) {
                host = request.getVirtualHost();
            }

            if (uri.getPort() == -1) {
                urlConnection.setRequestProperty("Host", host);
            } else {
                urlConnection.setRequestProperty("Host", host + ":" + uri.getPort());
            }


            if (config.isCompressionEnabled()) {
                urlConnection.setRequestProperty("Accept-Encoding", "gzip");
            }

            boolean contentTypeSet = false;
            if (!method.equalsIgnoreCase("CONNECT")) {
                FluentCaseInsensitiveStringsMap h = request.getHeaders();
                if (h != null) {
                    for (String name : h.keySet()) {
                        if (!"host".equalsIgnoreCase(name)) {
                            for (String value : h.get(name)) {
                                urlConnection.setRequestProperty(name, value);
                            }
                        }
                    }
                }
            }

            String ka = config.getKeepAlive() ? "keep-alive" : "close";
            urlConnection.setRequestProperty("Connection", ka);
            ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
            if (proxyServer != null) {
                urlConnection.setRequestProperty("Proxy-Connection", ka);
                if (proxyServer.getPrincipal() != null) {
                    urlConnection.setRequestProperty("Proxy-Authorization", AuthenticatorUtils.computeBasicAuthentication(proxyServer));
                }
            }

            Realm realm = request.getRealm();
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
                    default:
                        throw new IllegalStateException(String.format("[" + Thread.currentThread().getName() + "] Invalid Authentication %s", realm.toString()));
                }
            }

            if (realm != null && realm.getDomain() != null && realm.getScheme() == Realm.AuthScheme.NTLM) {
                jdkNtlmDomain = System.getProperty(NTLM_DOMAIN);
                System.setProperty(NTLM_DOMAIN, realm.getDomain());
            }
            
            // Add default accept headers.
            if (request.getHeaders().getFirstValue("Accept") == null) {
                urlConnection.setRequestProperty("Accept", "*/*");
            }

            if (config.getUserAgent() != null) {
                urlConnection.setRequestProperty("User-Agent", config.getUserAgent() + " (JDKAsyncHttpProvider)");
            }

            if (request.getCookies() != null && !request.getCookies().isEmpty()) {
                urlConnection.setRequestProperty("Cookie", AsyncHttpProviderUtils.encodeCookies(request.getCookies()));
            }

            String reqType = request.getReqType();
            urlConnection.setRequestMethod(reqType);

            if ("POST".equals(reqType) || "PUT".equals(reqType)) {
                urlConnection.setRequestProperty("Content-Length", "0");
                urlConnection.setDoOutput(true);
                //urlConnection.setChunkedStreamingMode(0);
               
                if (request.getByteData() != null) {
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(request.getByteData().length));
                    urlConnection.setFixedLengthStreamingMode(request.getByteData().length);

                    urlConnection.getOutputStream().write(request.getByteData());
                } else if (request.getStringData() != null) {
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(request.getStringData().length()));
                    urlConnection.getOutputStream().write(request.getStringData().getBytes("UTF-8"));
                } else if (request.getStreamData() != null) {
                    int[] lengthWrapper = new int[1];
                    byte[] bytes = AsyncHttpProviderUtils.readFully(request.getStreamData(), lengthWrapper);
                    int length = lengthWrapper[0];
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(length));
                    urlConnection.setFixedLengthStreamingMode(length);

                    urlConnection.getOutputStream().write(bytes, 0, length);
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
                    urlConnection.getOutputStream().write(sb.toString().getBytes("UTF-8"));
                } else if (request.getParts() != null) {
                    int lenght = (int) request.getLength();
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

                    ChannelBuffer b = ChannelBuffers.dynamicBuffer(lenght);
                    mre.writeRequest(urlConnection.getOutputStream());
                } else if (request.getEntityWriter() != null) {
                    int lenght = (int) request.getLength();
                    if (lenght != -1) {
                        urlConnection.setRequestProperty("Content-Length", String.valueOf(lenght));
                        urlConnection.setFixedLengthStreamingMode(lenght);
                    }
                    request.getEntityWriter().writeEntity(urlConnection.getOutputStream());
                } else if (request.getFile() != null) {
                    File file = request.getFile();
                    if (file.isHidden() || !file.exists() || !file.isFile()) {
                        throw new IOException(String.format("[" + Thread.currentThread().getName()
                                + "] File %s is not a file, is hidden or doesn't exist", file.getAbsolutePath()));
                    }
                    long lenght = new RandomAccessFile(file, "r").length();
                    urlConnection.setRequestProperty("Content-Length", String.valueOf(lenght));
                    urlConnection.setFixedLengthStreamingMode((int) lenght);

                    FileInputStream fis = new FileInputStream(file);
                    try {
                        final byte[] buffer = new byte[(int) lenght];
                        fis.read(buffer);
                        urlConnection.getOutputStream().write(buffer);
                    } finally {
                        fis.close();
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
