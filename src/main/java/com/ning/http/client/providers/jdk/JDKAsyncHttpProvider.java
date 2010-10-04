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
import com.ning.http.client.ByteArrayPart;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.Cookie;
import com.ning.http.client.FilePart;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.FluentStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Part;
import com.ning.http.client.PerRequestConfig;
import com.ning.http.client.ProgressAsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.StringPart;
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import com.ning.http.multipart.ByteArrayPartSource;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.multipart.PartSource;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.SslUtils;
import com.ning.http.util.UTF8UrlEncoder;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.DefaultCookie;

import javax.naming.AuthenticationException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
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
import java.util.Iterator;
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

    private final AsyncHttpClientConfig config;

    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final ConnectionsPool<String, URLConnection> connectionsPool;

    private final static int MAX_BUFFERED_BYTES = 8192;

    private final AtomicInteger currentConnectionCount = new AtomicInteger();

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

    protected final static URI createUri(String u) {
        URI uri = URI.create(u);
        final String scheme = uri.getScheme().toLowerCase();
        if (scheme == null || !scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + u
                    + ", must be equal (ignoring case) to 'http'");
        }

        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("The URI path, of the URI " + uri
                    + ", must be non-null");
        } else if (path.length() > 0 && path.charAt(0) != '/') {
            throw new IllegalArgumentException("The URI path, of the URI " + uri
                    + ". must start with a '/'");
        } else if (path.length() == 0) {
            return URI.create(u + "/");
        }

        return uri;
    }

    public <T> Future<T> execute(Request request, AsyncHandler<T> handler) throws IOException {

        if (isClose.get()) {
            throw new IOException("Closed");
        }

        if (config.getMaxTotalConnections() != -1 && currentConnectionCount.get() >= config.getMaxTotalConnections()) {
            throw new IOException("Too many connections");
        }        

        System.setProperty("http.maxConnections", "1");        
        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        Proxy proxy = null;
        if (proxyServer != null || request.getRealm() != null) {
            try {
                proxy = configureProxyAndAuth(proxyServer, request.getRealm());
            } catch (AuthenticationException e) {
                throw new IOException(e);
            }
        }

        HttpURLConnection urlConnection = createUrlConnection(request);
        JDKFuture f = new JDKFuture<T>(handler, config.getRequestTimeoutInMs());

        f.setInnerFuture(config.executorService().submit(new AsyncHttpUrlConnection(urlConnection, request, handler, f)));

        return f;
    }

    private HttpURLConnection createUrlConnection(Request request) throws IOException {
        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        Proxy proxy = null;
        if (proxyServer != null || request.getRealm() != null) {
            try {
                proxy = configureProxyAndAuth(proxyServer, request.getRealm());
            } catch (AuthenticationException e) {
                throw new IOException(e);
            }
        }

        HttpURLConnection urlConnection = null;
        if (proxy == null) {
            urlConnection = (HttpURLConnection) createUri(request.getUrl()).toURL().openConnection();
        } else {
            urlConnection = (HttpURLConnection) createUri(request.getUrl()).toURL().openConnection(proxy);
        }

        if (request.getUrl().startsWith("https")) {
            HttpsURLConnection secure = (HttpsURLConnection) urlConnection;
            SSLContext sslContext = config.getSSLContext();
            if (sslContext == null) {
                try {
                    sslContext = SslUtils.getSSLContext();
                } catch (NoSuchAlgorithmException e) {
                    throw new IOException(e);
                } catch (GeneralSecurityException e) {
                    throw new IOException(e);
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
                    uri = createUri(currentRequest.getRawUrl());
                } catch (IllegalArgumentException u) {
                    uri = createUri(currentRequest.getUrl());
                }
                currentConnectionCount.incrementAndGet();

                configure(uri, currentUrlConnection, currentRequest);
                currentUrlConnection.connect();

                int statusCode = currentUrlConnection.getResponseCode();
                boolean redirectEnabled = (request.isRedirectEnabled() || config.isRedirectEnabled());
                if (redirectEnabled && (statusCode == 302 || statusCode == 301)) {

                    if (currentRedirectCount++ < config.getMaxRedirects()) {
                        String location = currentUrlConnection.getHeaderField("Location");
                        if (location.startsWith("/")) {
                            location = getBaseUrl(uri) + location;
                        }

                        if (!location.equals(uri.toString())) {
                            URI newUri = createUri(location);

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
                    byte[] bytes = readFully(is, lengthWrapper);
                    if (lengthWrapper[0] > 0) {
                        // That sucks!!
                        int valid = lengthWrapper[0];
                        int i = 0;
                        if (bytes[lengthWrapper[0] - 1] == 0) {
                            for (byte b : bytes) {
                                if (b == 0 && i > 0) {
                                    valid = i;
                                    break;
                                }
                                i++;
                            }
                        }
                        byte[] body = new byte[valid];
                        System.arraycopy(bytes, 0, body, 0, body.length);

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
                currentUrlConnection.disconnect();
                currentConnectionCount.decrementAndGet();                
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
                System.setProperty("http.auth.ntlm.domain", realm.getDomain());
            }
            
            // Add default accept headers.
            if (request.getHeaders().getFirstValue("Accept") == null) {
                urlConnection.setRequestProperty("Accept", "*/*");
            }

            if (config.getUserAgent() != null) {
                urlConnection.setRequestProperty("User-Agent", config.getUserAgent() + " (JDKAsyncHttpProvider)");
            }

            // TODO: Get rid of Netty's.
            if (request.getCookies() != null && !request.getCookies().isEmpty()) {
                CookieEncoder httpCookieEncoder = new CookieEncoder(false);
                Iterator<Cookie> ic = request.getCookies().iterator();
                Cookie c;
                org.jboss.netty.handler.codec.http.Cookie cookie;
                while (ic.hasNext()) {
                    c = ic.next();
                    cookie = new DefaultCookie(c.getName(), c.getValue());
                    cookie.setPath(c.getPath());
                    cookie.setMaxAge(c.getMaxAge());
                    cookie.setDomain(c.getDomain());
                    httpCookieEncoder.addCookie(cookie);
                }
                urlConnection.setRequestProperty("Cookie", httpCookieEncoder.encode());
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
                    byte[] bytes = readFully(request.getStreamData(), lengthWrapper);
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

                    MultipartRequestEntity mre = createMultipartRequestEntity(request.getParts(), request.getParams());

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
                        String password = "";
                        if (proxyServer != null) {
                            if (proxyServer.getPassword() != null) {
                                password = proxyServer.getPassword();
                            }
                            return new PasswordAuthentication(proxyServer.getPrincipal(), password.toCharArray());
                        }
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

    private String getBaseUrl(URI uri) {
        String url = uri.getScheme() + "://" + uri.getAuthority();
        int port = uri.getPort();
        if (port == -1) {
            port = getPort(uri);
            url += ":" + port;
        }
        return url;
    }

    private static int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1)
            port = uri.getScheme().equals("http") ? 80 : 443;
        return port;
    }

    /**
     * This is quite ugly as our internal names are duplicated, but we build on top of HTTP Client implementation.
     *
     * @param params
     * @param methodParams
     * @return
     * @throws java.io.FileNotFoundException
     */
    private final static MultipartRequestEntity createMultipartRequestEntity(List<Part> params, FluentStringsMap methodParams) throws FileNotFoundException {
        com.ning.http.multipart.Part[] parts = new com.ning.http.multipart.Part[params.size()];
        int i = 0;

        for (Part part : params) {
            if (part instanceof StringPart) {
                parts[i] = new com.ning.http.multipart.StringPart(part.getName(),
                        ((StringPart) part).getValue(),
                        "UTF-8");
            } else if (part instanceof FilePart) {
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(),
                        ((FilePart) part).getFile(),
                        ((FilePart) part).getMimeType(),
                        ((FilePart) part).getCharSet());

            } else if (part instanceof ByteArrayPart) {
                PartSource source = new ByteArrayPartSource(((ByteArrayPart) part).getFileName(), ((ByteArrayPart) part).getData());
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(),
                        source,
                        ((ByteArrayPart) part).getMimeType(),
                        ((ByteArrayPart) part).getCharSet());

            } else if (part == null) {
                throw new NullPointerException("Part cannot be null");
            } else {
                throw new IllegalArgumentException(String.format("[" + Thread.currentThread().getName() + "] Unsupported part type for multipart parameter %s",
                        part.getName()));
            }
            ++i;
        }
        return new MultipartRequestEntity(parts, methodParams);
    }

    private static byte[] readFully(InputStream in, int[] lengthWrapper) throws IOException {
        // just in case available() returns bogus (or -1), allocate non-trivial chunk
        byte[] b = new byte[Math.max(512, in.available())];
        int offset = 0;
        while (true) {
            int left = b.length - offset;
            int count = in.read(b, offset, left);
            if (count < 0) { // EOF
                break;
            }
            offset += count;
            if (count == left) { // full buffer, need to expand
                b = doubleUp(b);
            }
        }
        // wish Java had Tuple return type...
        lengthWrapper[0] = offset;
        return b;
    }

    private static byte[] doubleUp(byte[] b) {
        int len = b.length;
        byte[] b2 = new byte[len + len];
        System.arraycopy(b, 0, b2, 0, len);
        return b2;
    }
}
