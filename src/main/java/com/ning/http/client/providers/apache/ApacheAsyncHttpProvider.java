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
package com.ning.http.client.providers.apache;

import static java.nio.charset.StandardCharsets.*;
import static com.ning.http.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;
import static com.ning.http.util.MiscUtils.closeSilently;
import static com.ning.http.util.MiscUtils.isNonEmpty;

import org.apache.commons.httpclient.CircularRedirectException;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.NoHttpResponseException;
import org.apache.commons.httpclient.ProxyHost;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.EntityEnclosingMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.OptionsMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.PartSource;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.commons.httpclient.params.HttpConnectionParams;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.commons.httpclient.util.IdleConnectionTimeoutThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.Body;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Param;
import com.ning.http.client.ProgressAsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.cookie.CookieEncoder;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.multipart.ByteArrayPart;
import com.ning.http.client.multipart.FilePart;
import com.ning.http.client.multipart.Part;
import com.ning.http.client.multipart.StringPart;
import com.ning.http.client.resumable.ResumableAsyncHandler;
import com.ning.http.client.uri.Uri;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.ProxyUtils;
import com.ning.http.util.UTF8UrlEncoder;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * An {@link com.ning.http.client.AsyncHttpProvider} for Apache Http Client 3.1
 */
public class ApacheAsyncHttpProvider implements AsyncHttpProvider {
    private final static Logger logger = LoggerFactory.getLogger(ApacheAsyncHttpProvider.class);

    private final AsyncHttpClientConfig config;
    private final AtomicBoolean isClose = new AtomicBoolean(false);
    private IdleConnectionTimeoutThread idleConnectionTimeoutThread;
    private final AtomicInteger maxConnections = new AtomicInteger();
    private final MultiThreadedHttpConnectionManager connectionManager;
    private final HttpClientParams params;
    private final ScheduledExecutorService reaper;

    static {
        final SocketFactory factory = new TrustingSSLSocketFactory();
        Protocol.registerProtocol("https", new Protocol("https", new ProtocolSocketFactory() {
            public Socket createSocket(String string, int i, InetAddress inetAddress, int i1) throws IOException {
                return factory.createSocket(string, i, inetAddress, i1);
            }

            public Socket createSocket(String string, int i, InetAddress inetAddress, int i1, HttpConnectionParams httpConnectionParams)
                    throws IOException {
                return factory.createSocket(string, i, inetAddress, i1);
            }

            public Socket createSocket(String string, int i) throws IOException {
                return factory.createSocket(string, i);
            }
        }, 443));
    }

    public ApacheAsyncHttpProvider(AsyncHttpClientConfig config) {
        this.config = config;
        connectionManager = new MultiThreadedHttpConnectionManager();

        params = new HttpClientParams();
        params.setParameter(HttpMethodParams.SINGLE_COOKIE_HEADER, Boolean.TRUE);
        params.setCookiePolicy(CookiePolicy.BROWSER_COMPATIBILITY);
        params.setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler());

        reaper = getReaper(config.getAsyncHttpProviderConfig());
    }

    private ScheduledExecutorService getReaper(AsyncHttpProviderConfig<?, ?> providerConfig) {

        ScheduledExecutorService reaper = null;
        if (providerConfig instanceof ApacheAsyncHttpProvider) {
            reaper = ApacheAsyncHttpProviderConfig.class.cast(providerConfig).getReaper();
        }

        if (reaper == null)
            reaper = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "AsyncHttpClient-Reaper");
                    t.setDaemon(true);
                    return t;
                }
            });

        return reaper;
    }

    public <T> ListenableFuture<T> execute(Request request, AsyncHandler<T> handler) throws IOException {
        if (isClose.get()) {
            throw new IOException("Closed");
        }

        if (handler instanceof ResumableAsyncHandler) {
            request = ResumableAsyncHandler.class.cast(handler).adjustRequestRange(request);
        }

        if (config.getMaxConnections() > -1 && (maxConnections.get() + 1) > config.getMaxConnections()) {
            throw new IOException(String.format("Too many connections %s", config.getMaxConnections()));
        }

        if (idleConnectionTimeoutThread != null) {
            idleConnectionTimeoutThread.shutdown();
            idleConnectionTimeoutThread = null;
        }

        int requestTimeout = AsyncHttpProviderUtils.requestTimeout(config, request);
        if (config.getReadTimeout() > 0 && requestTimeout != -1 && requestTimeout < config.getReadTimeout()) {
            idleConnectionTimeoutThread = new IdleConnectionTimeoutThread();
            idleConnectionTimeoutThread.setConnectionTimeout(config.getReadTimeout());
            idleConnectionTimeoutThread.addConnectionManager(connectionManager);
            idleConnectionTimeoutThread.start();
        }

        HttpClient httpClient = new HttpClient(params, connectionManager);

        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        if (realm != null) {
            httpClient.getParams().setAuthenticationPreemptive(realm.getUsePreemptiveAuth());
            Credentials defaultcreds = new UsernamePasswordCredentials(realm.getPrincipal(), realm.getPassword());
            httpClient.getState().setCredentials(new AuthScope(null, -1, AuthScope.ANY_REALM), defaultcreds);
        }

        HttpMethodBase method = createMethod(httpClient, request);
        ApacheResponseFuture f = new ApacheResponseFuture<T>(handler, requestTimeout, request, method);
        f.touch();

        f.setInnerFuture(config.executorService().submit(new ApacheClientRunnable(request, handler, method, f, httpClient)));
        maxConnections.incrementAndGet();
        return f;
    }

    public void close() {
        reaper.shutdown();
        if (idleConnectionTimeoutThread != null) {
            idleConnectionTimeoutThread.shutdown();
            idleConnectionTimeoutThread = null;
        }
        if (connectionManager != null) {
            try {
                connectionManager.shutdown();
            } catch (Exception e) {
                logger.error("Error shutting down connection manager", e);
            }
        }
    }

    public Response prepareResponse(HttpResponseStatus status, HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts) {
        return new ApacheResponse(status, headers, bodyParts);
    }

    private HttpMethodBase createMethod(HttpClient client, Request request) throws IOException, FileNotFoundException {
        String methodName = request.getMethod();
        HttpMethodBase method = null;
        if (methodName.equalsIgnoreCase("POST") || methodName.equalsIgnoreCase("PUT")) {
            EntityEnclosingMethod post = methodName.equalsIgnoreCase("POST") ? new PostMethod(request.getUrl()) : new PutMethod(request.getUrl());

            String bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET.name() : request.getBodyEncoding();

            post.getParams().setContentCharset(ISO_8859_1.name());
            if (request.getByteData() != null) {
                post.setRequestEntity(new ByteArrayRequestEntity(request.getByteData()));
                post.setRequestHeader("Content-Length", String.valueOf(request.getByteData().length));
            } else if (request.getStringData() != null) {
                post.setRequestEntity(new StringRequestEntity(request.getStringData(), "text/xml", bodyCharset));
                post.setRequestHeader("Content-Length", String.valueOf(request.getStringData().getBytes(bodyCharset).length));
            } else if (request.getStreamData() != null) {
                InputStreamRequestEntity r = new InputStreamRequestEntity(request.getStreamData());
                post.setRequestEntity(r);
                post.setRequestHeader("Content-Length", String.valueOf(r.getContentLength()));

            } else if (isNonEmpty(request.getFormParams())) {
                StringBuilder sb = new StringBuilder();
                for (final Param param : request.getFormParams()) {
                    final String name = param.getName();
                    final String value = param.getValue();
                    if (sb.length() > 0) {
                        sb.append("&");
                    }
                    UTF8UrlEncoder.appendEncoded(sb, name);
                    sb.append("=");
                    UTF8UrlEncoder.appendEncoded(sb, value);
                }

                post.setRequestHeader("Content-Length", String.valueOf(sb.length()));
                post.setRequestEntity(new StringRequestEntity(sb.toString(), "text/xml", ISO_8859_1.name()));

                if (!request.getHeaders().containsKey("Content-Type")) {
                    post.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
                }
            } else if (isNonEmpty(request.getParts())) {
                MultipartRequestEntity mre = createMultipartRequestEntity(bodyCharset, request.getParts(), post.getParams());
                post.setRequestEntity(mre);
                post.setRequestHeader("Content-Type", mre.getContentType());
                post.setRequestHeader("Content-Length", String.valueOf(mre.getContentLength()));
            } else if (request.getFile() != null) {
                File file = request.getFile();
                if (!file.isFile()) {
                    throw new IOException(String.format(Thread.currentThread()
                            + "File %s is not a file or doesn't exist", file.getAbsolutePath()));
                }
                post.setRequestHeader("Content-Length", String.valueOf(file.length()));

                FileInputStream fis = new FileInputStream(file);
                try {
                    InputStreamRequestEntity r = new InputStreamRequestEntity(fis);
                    post.setRequestEntity(r);
                    post.setRequestHeader("Content-Length", String.valueOf(r.getContentLength()));
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

                    // TODO: This is suboptimal
                    if (length >= 0) {
                        post.setRequestHeader("Content-Length", String.valueOf(length));

                        // This is totally sub optimal
                        byte[] bytes = new byte[length];
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        for (; ; ) {
                            buffer.clear();
                            if (body.read(buffer) < 0) {
                                break;
                            }
                        }
                        post.setRequestEntity(new ByteArrayRequestEntity(bytes));
                    }
                } finally {
                    closeSilently(body);
                }
            }

            if (request.getHeaders().getFirstValue("Expect") != null
                    && request.getHeaders().getFirstValue("Expect").equalsIgnoreCase("100-Continue")) {
                post.setUseExpectHeader(true);
            }
            method = post;
        } else if (methodName.equalsIgnoreCase("DELETE")) {
            method = new DeleteMethod(request.getUrl());
        } else if (methodName.equalsIgnoreCase("HEAD")) {
            method = new HeadMethod(request.getUrl());
        } else if (methodName.equalsIgnoreCase("GET")) {
            method = new GetMethod(request.getUrl());
        } else if (methodName.equalsIgnoreCase("OPTIONS")) {
            method = new OptionsMethod(request.getUrl());
        } else {
            throw new IllegalStateException(String.format("Invalid Method", methodName));
        }

        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
        if (proxyServer != null) {

            if (proxyServer.getPrincipal() != null) {
                Credentials defaultcreds = new UsernamePasswordCredentials(proxyServer.getPrincipal(), proxyServer.getPassword());
                client.getState().setProxyCredentials(new AuthScope(null, -1, AuthScope.ANY_REALM), defaultcreds);
            }

            ProxyHost proxyHost = proxyServer == null ? null : new ProxyHost(proxyServer.getHost(), proxyServer.getPort());
            client.getHostConfiguration().setProxyHost(proxyHost);
        }
        if(request.getLocalAddress()!=null) {
            client.getHostConfiguration().setLocalAddress(request.getLocalAddress());
        }

        method.setFollowRedirects(false);
        if (isNonEmpty(request.getCookies())) {
            method.setRequestHeader("Cookie", CookieEncoder.encode(request.getCookies()));
        }

        if (request.getHeaders() != null) {
            for (String name : request.getHeaders().keySet()) {
                if (!"host".equalsIgnoreCase(name)) {
                    for (String value : request.getHeaders().get(name)) {
                        method.setRequestHeader(name, value);
                    }
                }
            }
        }

        if (request.getHeaders().getFirstValue("User-Agent") != null) {
            method.setRequestHeader("User-Agent", request.getHeaders().getFirstValue("User-Agent"));
        } else if (config.getUserAgent() != null) {
            method.setRequestHeader("User-Agent", config.getUserAgent());
        }

        Header acceptableEncodingHeader = method.getRequestHeader("Accept-Encoding");
        if (acceptableEncodingHeader != null) {
            String acceptableEncodings = acceptableEncodingHeader.getValue();
            if (acceptableEncodings.indexOf("gzip") == -1) {
                StringBuilder buf = new StringBuilder(acceptableEncodings);
                if (buf.length() > 1) {
                    buf.append(",");
                }
                buf.append("gzip");
                method.setRequestHeader("Accept-Encoding", buf.toString());
            }
        } else if (config.isCompressionEnforced()) {
            method.setRequestHeader("Accept-Encoding", "gzip");
        }

        if (request.getVirtualHost() != null) {

            String vs = request.getVirtualHost();
            int index = vs.indexOf(":");
            if (index > 0) {
                vs = vs.substring(0, index);
            }
            method.getParams().setVirtualHost(vs);
        }

        return method;
    }

    private final static int computeAndSetContentLength(Request request, HttpMethodBase m) {
        int lenght = (int) request.getContentLength();
        if (lenght == -1 && m.getRequestHeader("Content-Length") != null) {
            lenght = Integer.valueOf(m.getRequestHeader("Content-Length").getValue());
        }

        if (lenght != -1) {
            m.setRequestHeader("Content-Length", String.valueOf(lenght));
        }
        return lenght;
    }

    public class ApacheClientRunnable<T> implements Callable<T> {

        private final AsyncHandler<T> asyncHandler;
        private HttpMethodBase method;
        private final ApacheResponseFuture<T> future;
        private Request request;
        private final HttpClient httpClient;
        private int currentRedirectCount;
        private AtomicBoolean isAuth = new AtomicBoolean(false);
        private boolean terminate = true;

        public ApacheClientRunnable(Request request, AsyncHandler<T> asyncHandler, HttpMethodBase method, ApacheResponseFuture<T> future, HttpClient httpClient) {
            this.asyncHandler = asyncHandler;
            this.method = method;
            this.future = future;
            this.request = request;
            this.httpClient = httpClient;
        }

        public T call() {
            terminate = true;
            AsyncHandler.STATE state = AsyncHandler.STATE.ABORT;
            try {
                Uri uri = request.getUri();

                int delay = AsyncHttpProviderUtils.requestTimeout(config, future.getRequest());
                if (delay != -1) {
                    ReaperFuture reaperFuture = new ReaperFuture(future);
                    Future scheduledFuture = reaper.scheduleAtFixedRate(reaperFuture, delay, 500, TimeUnit.MILLISECONDS);
                    reaperFuture.setScheduledFuture(scheduledFuture);
                    future.setReaperFuture(reaperFuture);
                }

                if (asyncHandler instanceof TransferCompletionHandler) {
                    throw new IllegalStateException(TransferCompletionHandler.class.getName() + "not supported by this provider");
                }

                int statusCode = 200;
                try {
                    statusCode = httpClient.executeMethod(method);
                } catch (CircularRedirectException ex) {
                    // Quite ugly, but this is needed to unify 
                    statusCode = 302;
                    currentRedirectCount = config.getMaxRedirects();
                }

                ApacheResponseStatus status = new ApacheResponseStatus(uri, config, method);
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
                    method = createMethod(httpClient, request);
                    terminate = false;
                    return call();
                }

                logger.debug("\n\nRequest {}\n\nResponse {}\n", request, method);

                if (AsyncHttpProviderUtils.followRedirect(config, request) && (statusCode == 302 || statusCode == 301)) {

                    isAuth.set(false);

                    if (currentRedirectCount++ < config.getMaxRedirects()) {
                        String location = method.getResponseHeader("Location").getValue();
                        Uri rediUri = Uri.create(uri, location);

                        if (!rediUri.equals(uri)) {
                            RequestBuilder builder = new RequestBuilder(request);

                            logger.debug("Redirecting to {}", rediUri);

                            request = builder.setUri(rediUri).build();
                            method = createMethod(httpClient, request);
                            terminate = false;
                            return call();
                        }
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                state = asyncHandler.onStatusReceived(status);
                if (state == AsyncHandler.STATE.CONTINUE) {
                    state = asyncHandler.onHeadersReceived(new ApacheResponseHeaders(method));
                }

                if (state == AsyncHandler.STATE.CONTINUE) {
                    InputStream is = method.getResponseBodyAsStream();
                    if (is != null) {
                        Header h = method.getResponseHeader("Content-Encoding");
                        if (h != null) {
                            String contentEncoding = h.getValue();
                            boolean isGZipped = contentEncoding == null ? false : "gzip".equalsIgnoreCase(contentEncoding);
                            if (isGZipped) {
                                is = new GZIPInputStream(is);
                            }
                        }

                        int byteToRead = (int) method.getResponseContentLength();
                        InputStream stream = is;
                        if (byteToRead <= 0) {
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

                                try {
                                    read = stream.read(bytes);
                                } catch (IOException ex) {
                                    logger.warn("Connection closed", ex);
                                    read = -1;
                                }

                                if (read == -1) {
                                    break;
                                }

                                future.touch();

                                byte[] b = new byte[read];
                                System.arraycopy(bytes, 0, b, 0, read);
                                leftBytes -= read;

                                asyncHandler.onBodyPartReceived(new ApacheResponseBodyPart(b, leftBytes > -1));

                            }
                        }
                    }

                    if (method.getName().equalsIgnoreCase("HEAD")) {
                        asyncHandler.onBodyPartReceived(new ApacheResponseBodyPart("".getBytes(), true));
                    }
                }

                if (asyncHandler instanceof ProgressAsyncHandler) {
                	ProgressAsyncHandler progressAsyncHandler = (ProgressAsyncHandler) asyncHandler;
                	progressAsyncHandler.onHeaderWriteCompleted();
                	progressAsyncHandler.onContentWriteCompleted();
                }

                try {
                    return asyncHandler.onCompleted();
                } catch (Throwable t) {
                    RuntimeException ex = new RuntimeException();
                    ex.initCause(t);
                    throw ex;
                }
            } catch (Throwable t) {

                if (t instanceof IOException && !config.getIOExceptionFilters().isEmpty()) {
                    FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(asyncHandler)
                            .request(future.getRequest()).ioException(IOException.class.cast(t)).build();

                    try {
                        fc = handleIoException(fc);
                    } catch (FilterException e) {
                        if (config.getMaxConnections() != -1) {
                            maxConnections.decrementAndGet();
                        }
                        future.done();
                        method.releaseConnection();
                    }

                    if (fc.replayRequest()) {
                        request = fc.getRequest();
                        return call();
                    }
                }

                if (method.isAborted()) {
                    return null;
                }

                logger.debug(t.getMessage(), t);

                try {
                    future.abort(filterException(t));
                } catch (Throwable t2) {
                    logger.error(t2.getMessage(), t2);
                }
            } finally {
                if (terminate) {
                    if (config.getMaxConnections() != -1) {
                        maxConnections.decrementAndGet();
                    }
                    future.done();

                    // Crappy Apache HttpClient who blocks forever here with large files.
                    config.executorService().submit(new Runnable() {

                        public void run() {
                            method.releaseConnection();
                        }
                    });
                }
            }
            return null;
        }

        private Throwable filterException(Throwable t) {
            if (t instanceof UnknownHostException) {
                t = new ConnectException(t.getMessage());

            } else if (t instanceof NoHttpResponseException) {
                int responseTimeout = AsyncHttpProviderUtils.requestTimeout(config, request);
                t = new TimeoutException(String.format("No response received after %s", responseTimeout));

            } else if (t instanceof SSLHandshakeException) {
                Throwable t2 = new ConnectException();
                t2.initCause(t);
                t = t2;
            }

            return t;
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
    }

    private MultipartRequestEntity createMultipartRequestEntity(String charset, List<Part> params, HttpMethodParams methodParams) throws FileNotFoundException {
        org.apache.commons.httpclient.methods.multipart.Part[] parts = new org.apache.commons.httpclient.methods.multipart.Part[params.size()];
        int i = 0;

        for (Part part : params) {
            if (part instanceof StringPart) {
                parts[i] = new org.apache.commons.httpclient.methods.multipart.StringPart(part.getName(),
                        ((StringPart) part).getValue(),
                        charset);
            } else if (part instanceof FilePart) {
                parts[i] = new org.apache.commons.httpclient.methods.multipart.FilePart(part.getName(),
                        ((FilePart) part).getFile(),
                        ((FilePart) part).getContentType(),
                        ((FilePart) part).getCharset().name());

            } else if (part instanceof ByteArrayPart) {
                PartSource source = new ByteArrayPartSource(((ByteArrayPart) part).getFileName(), ((ByteArrayPart) part).getBytes());
                parts[i] = new org.apache.commons.httpclient.methods.multipart.FilePart(part.getName(),
                        source,
                        ((ByteArrayPart) part).getContentType(),
                        ((ByteArrayPart) part).getCharset().name());

            } else if (part == null) {
                throw new NullPointerException("Part cannot be null");
            } else {
                throw new IllegalArgumentException(String.format("Unsupported part type for multipart parameter %s",
                        part.getName()));
            }
            ++i;
        }
        return new MultipartRequestEntity(parts, methodParams);
    }

    private static class TrustingSSLSocketFactory extends SSLSocketFactory {
        private SSLSocketFactory delegate;

        private TrustingSSLSocketFactory() {
            try {
                SSLContext sslcontext = SSLContext.getInstance("SSL");

                sslcontext.init(null, new TrustManager[]{new TrustEveryoneTrustManager()}, new SecureRandom());
                delegate = sslcontext.getSocketFactory();
            } catch (KeyManagementException e) {
                throw new IllegalStateException();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException();
            }
        }

        @Override
        public Socket createSocket(String s, int i) throws IOException, UnknownHostException {
            return delegate.createSocket(s, i);
        }

        @Override
        public Socket createSocket(String s, int i, InetAddress inetAddress, int i1) throws IOException, UnknownHostException {
            return delegate.createSocket(s, i, inetAddress, i1);
        }

        @Override
        public Socket createSocket(InetAddress inetAddress, int i) throws IOException {
            return delegate.createSocket(inetAddress, i);
        }

        @Override
        public Socket createSocket(InetAddress inetAddress, int i, InetAddress inetAddress1, int i1) throws IOException {
            return delegate.createSocket(inetAddress, i, inetAddress1, i1);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String s, int i, boolean b) throws IOException {
            return delegate.createSocket(socket, s, i, b);
        }
    }

    private static class TrustEveryoneTrustManager implements X509TrustManager {
        
        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            // do nothing
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            // do nothing
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private final class ReaperFuture implements Future, Runnable {
        private Future scheduledFuture;
        private ApacheResponseFuture<?> apacheResponseFuture;

        public ReaperFuture(ApacheResponseFuture<?> apacheResponseFuture) {
            this.apacheResponseFuture = apacheResponseFuture;
        }

        public void setScheduledFuture(Future scheduledFuture) {
            this.scheduledFuture = scheduledFuture;
        }

        @Override
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            //cleanup references to allow gc to reclaim memory independently
            //of this Future lifecycle
            this.apacheResponseFuture = null;
            return this.scheduledFuture.cancel(mayInterruptIfRunning);
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return this.scheduledFuture.get();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException,
                TimeoutException {
            return this.scheduledFuture.get(timeout, unit);
        }

        @Override
        public boolean isCancelled() {
            return this.scheduledFuture.isCancelled();
        }

        @Override
        public boolean isDone() {
            return this.scheduledFuture.isDone();
        }

        @Override
        public synchronized void run() {
            if (this.apacheResponseFuture != null && this.apacheResponseFuture.hasExpired()) {
                logger.debug("Request Timeout expired for " + this.apacheResponseFuture);

                int requestTimeout = AsyncHttpProviderUtils.requestTimeout(config, this.apacheResponseFuture.getRequest());
                apacheResponseFuture.abort(new TimeoutException(String.format("No response received after %s", requestTimeout)));

                this.apacheResponseFuture = null;
            }
        }
    }
}
