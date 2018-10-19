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
package org.asynchttpclient.extras.simple;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.ssl.SslContext;
import org.asynchttpclient.*;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.handler.ProgressAsyncHandler;
import org.asynchttpclient.handler.resumable.ResumableAsyncHandler;
import org.asynchttpclient.handler.resumable.ResumableIOExceptionFilter;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.uri.Uri;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.util.MiscUtils.closeSilently;
import static org.asynchttpclient.util.MiscUtils.withDefault;

/**
 * Simple implementation of {@link AsyncHttpClient} and it's related builders ({@link AsyncHttpClientConfig},
 * {@link Realm}, {@link ProxyServer} and {@link AsyncHandler}. You can
 * build powerful application by just using this class.
 * <br>
 * This class rely on {@link BodyGenerator} and {@link BodyConsumer} for handling the request and response body. No
 * {@link AsyncHandler} are required. As simple as:
 * <blockquote><pre>
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 * .setIdleConnectionInPoolTimeout(100)
 * .setMaximumConnectionsTotal(50)
 * .setRequestTimeout(5 * 60 * 1000)
 * .setUrl(getTargetUrl())
 * .setHeader("Content-Type", "text/html").build();
 *
 * StringBuilder s = new StringBuilder();
 * Future&lt;Response&gt; future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new AppendableBodyConsumer(s));
 * </pre></blockquote>
 * or
 * <blockquote><pre>
 * public void ByteArrayOutputStreamBodyConsumerTest() throws Throwable {
 *
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 * .setUrl(getTargetUrl())
 * .build();
 *
 * ByteArrayOutputStream o = new ByteArrayOutputStream(10);
 * Future&lt;Response&gt; future = client.post(new FileBodyGenerator(myFile), new OutputStreamBodyConsumer(o));
 * </pre></blockquote>
 */
public class SimpleAsyncHttpClient implements Closeable {

  private final AsyncHttpClientConfig config;
  private final RequestBuilder requestBuilder;
  private final ThrowableHandler defaultThrowableHandler;
  private final boolean resumeEnabled;
  private final ErrorDocumentBehaviour errorDocumentBehaviour;
  private final SimpleAHCTransferListener listener;
  private final boolean derived;
  private AsyncHttpClient asyncHttpClient;

  private SimpleAsyncHttpClient(AsyncHttpClientConfig config, RequestBuilder requestBuilder, ThrowableHandler defaultThrowableHandler,
                                ErrorDocumentBehaviour errorDocumentBehaviour, boolean resumeEnabled, AsyncHttpClient ahc, SimpleAHCTransferListener listener) {
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

  public Future<Response> post(BodyGenerator bodyGenerator, BodyConsumer bodyConsumer, ThrowableHandler throwableHandler)
          throws IOException {
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

  public Future<Response> put(BodyGenerator bodyGenerator, BodyConsumer bodyConsumer, ThrowableHandler throwableHandler)
          throws IOException {
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
    ProgressAsyncHandler<Response> handler = new BodyConsumerAsyncHandler(bodyConsumer, throwableHandler, errorDocumentBehaviour,
            request.getUri(), listener);

    if (resumeEnabled && request.getMethod().equals("GET") && bodyConsumer != null && bodyConsumer instanceof ResumableBodyConsumer) {
      ResumableBodyConsumer fileBodyConsumer = (ResumableBodyConsumer) bodyConsumer;
      long length = fileBodyConsumer.getTransferredBytes();
      fileBodyConsumer.resume();
      handler = new ResumableBodyConsumerAsyncHandler(length, handler);
    }

    return getAsyncHttpClient().executeRequest(request, handler);
  }

  private AsyncHttpClient getAsyncHttpClient() {
    synchronized (config) {
      if (asyncHttpClient == null) {
        asyncHttpClient = asyncHttpClient(config);
      }
    }
    return asyncHttpClient;
  }

  /**
   * Close the underlying AsyncHttpClient for this instance.
   * <br>
   * If this instance is derived from another instance, this method does
   * nothing as the client instance is managed by the original
   * SimpleAsyncHttpClient.
   *
   * @see #derive()
   * @see AsyncHttpClient#close()
   */
  public void close() throws IOException {
    if (!derived && asyncHttpClient != null) {
      asyncHttpClient.close();
    }
  }

  /**
   * Returns a Builder for a derived SimpleAsyncHttpClient that uses the same
   * instance of {@link AsyncHttpClient} to execute requests.
   * <br>
   * The original SimpleAsyncHttpClient is responsible for managing the
   * underlying AsyncHttpClient. For the derived instance, {@link #close()} is
   * a NOOP. If the original SimpleAsyncHttpClient is closed, all derived
   * instances become invalid.
   *
   * @return a Builder for a derived SimpleAsyncHttpClient that uses the same
   * instance of {@link AsyncHttpClient} to execute requests, never
   * {@code null}.
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
    OMIT
  }

  /**
   * This interface contains possible configuration changes for a derived SimpleAsyncHttpClient.
   *
   * @see SimpleAsyncHttpClient#derive()
   */
  /**
   * This interface contains possible configuration changes for a derived SimpleAsyncHttpClient.
   *
   * @see SimpleAsyncHttpClient#derive()
   */
  public interface DerivedBuilder {

    DerivedBuilder setFollowRedirect(boolean followRedirect);

    DerivedBuilder setVirtualHost(String virtualHost);

    DerivedBuilder setUrl(String url);

    DerivedBuilder setFormParams(List<Param> params);

    DerivedBuilder setFormParams(Map<String, List<String>> params);

    DerivedBuilder setHeaders(Map<? extends CharSequence, Collection<?>> headers);

    DerivedBuilder setHeaders(HttpHeaders headers);

    DerivedBuilder setHeader(CharSequence name, Object value);

    DerivedBuilder addQueryParam(String name, String value);

    DerivedBuilder addFormParam(String key, String value);

    DerivedBuilder addHeader(CharSequence name, Object value);

    DerivedBuilder addCookie(Cookie cookie);

    DerivedBuilder addBodyPart(Part part);

    DerivedBuilder setResumableDownload(boolean resume);

    SimpleAsyncHttpClient build();
  }

  public final static class Builder implements DerivedBuilder {

    private final RequestBuilder requestBuilder;
    private final DefaultAsyncHttpClientConfig.Builder configBuilder = config();
    private Realm.Builder realmBuilder = null;
    private Realm.AuthScheme proxyAuthScheme;
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
      this.ahc = client.getAsyncHttpClient();
      this.listener = client.listener;
    }

    public Builder addBodyPart(Part part) {
      requestBuilder.addBodyPart(part);
      return this;
    }

    public Builder addCookie(Cookie cookie) {
      requestBuilder.addCookie(cookie);
      return this;
    }

    public Builder addHeader(CharSequence name, Object value) {
      requestBuilder.addHeader(name, value);
      return this;
    }

    public Builder addFormParam(String key, String value) {
      requestBuilder.addFormParam(key, value);
      return this;
    }

    public Builder addQueryParam(String name, String value) {
      requestBuilder.addQueryParam(name, value);
      return this;
    }

    public Builder setHeader(CharSequence name, Object value) {
      requestBuilder.setHeader(name, value);
      return this;
    }

    public Builder setHeaders(HttpHeaders headers) {
      requestBuilder.setHeaders(headers);
      return this;
    }

    public Builder setHeaders(Map<? extends CharSequence, Collection<?>> headers) {
      requestBuilder.setHeaders(headers);
      return this;
    }

    public Builder setFormParams(Map<String, List<String>> parameters) {
      requestBuilder.setFormParams(parameters);
      return this;
    }

    public Builder setFormParams(List<Param> params) {
      requestBuilder.setFormParams(params);
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

    public Builder setFollowRedirect(boolean followRedirect) {
      requestBuilder.setFollowRedirect(followRedirect);
      return this;
    }

    public Builder setMaxConnections(int defaultMaxConnections) {
      configBuilder.setMaxConnections(defaultMaxConnections);
      return this;
    }

    public Builder setMaxConnectionsPerHost(int defaultMaxConnectionsPerHost) {
      configBuilder.setMaxConnectionsPerHost(defaultMaxConnectionsPerHost);
      return this;
    }

    public Builder setConnectTimeout(int connectTimeuot) {
      configBuilder.setConnectTimeout(connectTimeuot);
      return this;
    }

    public Builder setPooledConnectionIdleTimeout(int pooledConnectionIdleTimeout) {
      configBuilder.setPooledConnectionIdleTimeout(pooledConnectionIdleTimeout);
      return this;
    }

    public Builder setRequestTimeout(int defaultRequestTimeout) {
      configBuilder.setRequestTimeout(defaultRequestTimeout);
      return this;
    }

    public Builder setMaxRedirects(int maxRedirects) {
      configBuilder.setMaxRedirects(maxRedirects);
      return this;
    }

    public Builder setCompressionEnforced(boolean compressionEnforced) {
      configBuilder.setCompressionEnforced(compressionEnforced);
      return this;
    }

    public Builder setUserAgent(String userAgent) {
      configBuilder.setUserAgent(userAgent);
      return this;
    }

    public Builder setKeepAlive(boolean allowPoolingConnections) {
      configBuilder.setKeepAlive(allowPoolingConnections);
      return this;
    }

    public Builder setThreadFactory(ThreadFactory threadFactory) {
      configBuilder.setThreadFactory(threadFactory);
      return this;
    }

    public Builder setSslContext(SslContext sslContext) {
      configBuilder.setSslContext(sslContext);
      return this;
    }

    public Builder setSslEngineFactory(SslEngineFactory sslEngineFactory) {
      configBuilder.setSslEngineFactory(sslEngineFactory);
      return this;
    }

    public Builder setRealm(Realm realm) {
      configBuilder.setRealm(realm);
      return this;
    }

    public Builder setProxyAuthScheme(Realm.AuthScheme proxyAuthScheme) {
      this.proxyAuthScheme = proxyAuthScheme;
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
     *
     * @param behaviour the behaviour
     * @return this
     */
    public Builder setErrorDocumentBehaviour(ErrorDocumentBehaviour behaviour) {
      this.errorDocumentBehaviour = behaviour;
      return this;
    }

    /**
     * Enable resumable downloads for the SimpleAHC. Resuming downloads will only work for GET requests
     * with an instance of {@link ResumableBodyConsumer}.
     */
    @Override
    public Builder setResumableDownload(boolean enableResumableDownload) {
      this.enableResumableDownload = enableResumableDownload;
      return this;
    }

    /**
     * Set the listener to notify about connection progress.
     *
     * @param listener a listener
     * @return this
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

    public Builder setAcceptAnyCertificate(boolean acceptAnyCertificate) {
      configBuilder.setUseInsecureTrustManager(acceptAnyCertificate);
      return this;
    }

    public SimpleAsyncHttpClient build() {

      if (realmBuilder != null) {
        configBuilder.setRealm(realmBuilder.build());
      }

      if (proxyHost != null) {
        Realm realm = null;
        if (proxyPrincipal != null) {
          AuthScheme proxyAuthScheme = withDefault(this.proxyAuthScheme, AuthScheme.BASIC);
          realm = realm(proxyAuthScheme, proxyPrincipal, proxyPassword).build();
        }

        configBuilder.setProxyServer(proxyServer(proxyHost, proxyPort).setRealm(realm).build());
      }

      configBuilder.addIOExceptionFilter(new ResumableIOExceptionFilter());

      SimpleAsyncHttpClient sc = new SimpleAsyncHttpClient(configBuilder.build(), requestBuilder, defaultThrowableHandler,
              errorDocumentBehaviour, enableResumableDownload, ahc, listener);

      return sc;
    }
  }

  private final static class ResumableBodyConsumerAsyncHandler extends ResumableAsyncHandler implements ProgressAsyncHandler<Response> {

    private final ProgressAsyncHandler<Response> delegate;

    public ResumableBodyConsumerAsyncHandler(long byteTransferred, ProgressAsyncHandler<Response> delegate) {
      super(byteTransferred, delegate);
      this.delegate = delegate;
    }

    public AsyncHandler.State onHeadersWritten() {
      return delegate.onHeadersWritten();
    }

    public AsyncHandler.State onContentWritten() {
      return delegate.onContentWritten();
    }

    public AsyncHandler.State onContentWriteProgress(long amount, long current, long total) {
      return delegate.onContentWriteProgress(amount, current, total);
    }
  }

  private final static class BodyConsumerAsyncHandler extends AsyncCompletionHandlerBase {

    private final BodyConsumer bodyConsumer;
    private final ThrowableHandler exceptionHandler;
    private final ErrorDocumentBehaviour errorDocumentBehaviour;
    private final Uri uri;
    private final SimpleAHCTransferListener listener;

    private boolean accumulateBody = false;
    private boolean omitBody = false;
    private int amount = 0;
    private long total = -1;

    public BodyConsumerAsyncHandler(BodyConsumer bodyConsumer, ThrowableHandler exceptionHandler,
                                    ErrorDocumentBehaviour errorDocumentBehaviour, Uri uri, SimpleAHCTransferListener listener) {
      this.bodyConsumer = bodyConsumer;
      this.exceptionHandler = exceptionHandler;
      this.errorDocumentBehaviour = errorDocumentBehaviour;
      this.uri = uri;
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
    public State onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
      fireReceived(content);
      if (omitBody) {
        return State.CONTINUE;
      }

      if (!accumulateBody && bodyConsumer != null) {
        bodyConsumer.consume(content.getBodyByteBuffer());
      } else {
        return super.onBodyPartReceived(content);
      }
      return State.CONTINUE;
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
      if (bodyConsumer != null)
        closeSilently(bodyConsumer);
    }

    @Override
    public State onStatusReceived(HttpResponseStatus status) throws Exception {
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
    public State onHeadersReceived(HttpHeaders headers) throws Exception {
      calculateTotal(headers);

      fireHeaders(headers);

      return super.onHeadersReceived(headers);
    }

    private void calculateTotal(HttpHeaders headers) {
      String length = headers.get(CONTENT_LENGTH);

      try {
        total = Integer.valueOf(length);
      } catch (Exception e) {
        total = -1;
      }
    }

    @Override
    public State onContentWriteProgress(long amount, long current, long total) {
      fireSent(uri, amount, current, total);
      return super.onContentWriteProgress(amount, current, total);
    }

    private void fireStatus(HttpResponseStatus status) {
      if (listener != null) {
        listener.onStatus(uri, status.getStatusCode(), status.getStatusText());
      }
    }

    private void fireReceived(HttpResponseBodyPart content) {
      int remaining = content.getBodyByteBuffer().remaining();

      amount += remaining;

      if (listener != null) {
        listener.onBytesReceived(uri, amount, remaining, total);
      }
    }

    private void fireHeaders(HttpHeaders headers) {
      if (listener != null) {
        listener.onHeaders(uri, headers);
      }
    }

    private void fireSent(Uri uri, long amount, long current, long total) {
      if (listener != null) {
        listener.onBytesSent(uri, amount, current, total);
      }
    }

    private void fireCompleted(Response response) {
      if (listener != null) {
        listener.onCompleted(uri, response.getStatusCode(), response.getStatusText());
      }
    }
  }
}
