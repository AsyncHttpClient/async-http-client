/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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

import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.asynchttpclient.channel.ChannelPool;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.handler.resumable.ResumableAsyncHandler;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.asynchttpclient.util.Assertions.assertNotNull;

/**
 * Default and threadsafe implementation of {@link AsyncHttpClient}.
 */
public class DefaultAsyncHttpClient implements AsyncHttpClient {

  private final static Logger LOGGER = LoggerFactory.getLogger(DefaultAsyncHttpClient.class);
  private final AsyncHttpClientConfig config;
  private final boolean noRequestFilters;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final ChannelManager channelManager;
  private final NettyRequestSender requestSender;
  private final boolean allowStopNettyTimer;
  private final Timer nettyTimer;

  /**
   * Default signature calculator to use for all requests constructed by this
   * client instance.
   */
  private SignatureCalculator signatureCalculator;

  /**
   * Create a new HTTP Asynchronous Client using the default
   * {@link DefaultAsyncHttpClientConfig} configuration. The default
   * {@link AsyncHttpClient} that will be used will be based on the classpath
   * configuration.
   * <p>
   * If none of those providers are found, then the engine will throw an
   * IllegalStateException.
   */
  public DefaultAsyncHttpClient() {
    this(new DefaultAsyncHttpClientConfig.Builder().build());
  }

  /**
   * Create a new HTTP Asynchronous Client using the specified
   * {@link DefaultAsyncHttpClientConfig} configuration. This configuration
   * will be passed to the default {@link AsyncHttpClient} that will be
   * selected based on the classpath configuration.
   *
   * @param config a {@link DefaultAsyncHttpClientConfig}
   */
  public DefaultAsyncHttpClient(AsyncHttpClientConfig config) {

    this.config = config;
    this.noRequestFilters = config.getRequestFilters().isEmpty();
    allowStopNettyTimer = config.getNettyTimer() == null;
    nettyTimer = allowStopNettyTimer ? newNettyTimer(config) : config.getNettyTimer();

    channelManager = new ChannelManager(config, nettyTimer);
    requestSender = new NettyRequestSender(config, channelManager, nettyTimer, new AsyncHttpClientState(closed));
    channelManager.configureBootstraps(requestSender);
  }

  private Timer newNettyTimer(AsyncHttpClientConfig config) {
    ThreadFactory threadFactory = config.getThreadFactory() != null ? config.getThreadFactory() : new DefaultThreadFactory(config.getThreadPoolName() + "-timer");

    HashedWheelTimer timer = new HashedWheelTimer(threadFactory);
    timer.start();
    return timer;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        channelManager.close();
      } catch (Throwable t) {
        LOGGER.warn("Unexpected error on ChannelManager close", t);
      }
      if (allowStopNettyTimer) {
        try {
          nettyTimer.stop();
        } catch (Throwable t) {
          LOGGER.warn("Unexpected error on HashedWheelTimer close", t);
        }
      }
    }
  }

  @Override
  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public DefaultAsyncHttpClient setSignatureCalculator(SignatureCalculator signatureCalculator) {
    this.signatureCalculator = signatureCalculator;
    return this;
  }

  @Override
  public BoundRequestBuilder prepare(String method, String url) {
    return requestBuilder(method, url);
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
  public BoundRequestBuilder prepareRequest(RequestBuilder requestBuilder) {
    return prepareRequest(requestBuilder.build());
  }

  @Override
  public <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler) {
    if (config.getCookieStore() != null) {
      try {
        List<Cookie> cookies = config.getCookieStore().get(request.getUri());
        if (!cookies.isEmpty()) {
          RequestBuilder requestBuilder = new RequestBuilder(request);
          for (Cookie cookie : cookies) {
            requestBuilder.addOrReplaceCookie(cookie);
          }
          request = requestBuilder.build();
        }
      } catch (Exception e) {
        handler.onThrowable(e);
        return new ListenableFuture.CompletedFailure<>("Failed to set cookies of request", e);
      }
    }

    if (noRequestFilters) {
      return execute(request, handler);
    } else {
      FilterContext<T> fc = new FilterContext.FilterContextBuilder<T>().asyncHandler(handler).request(request).build();
      try {
        fc = preProcessRequest(fc);
      } catch (Exception e) {
        handler.onThrowable(e);
        return new ListenableFuture.CompletedFailure<>("preProcessRequest failed", e);
      }

      return execute(fc.getRequest(), fc.getAsyncHandler());
    }
  }

  @Override
  public <T> ListenableFuture<T> executeRequest(RequestBuilder requestBuilder, AsyncHandler<T> handler) {
    return executeRequest(requestBuilder.build(), handler);
  }

  @Override
  public ListenableFuture<Response> executeRequest(Request request) {
    return executeRequest(request, new AsyncCompletionHandlerBase());
  }

  @Override
  public ListenableFuture<Response> executeRequest(RequestBuilder requestBuilder) {
    return executeRequest(requestBuilder.build());
  }

  private <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) {
    try {
      return requestSender.sendRequest(request, asyncHandler, null);
    } catch (Exception e) {
      asyncHandler.onThrowable(e);
      return new ListenableFuture.CompletedFailure<>(e);
    }
  }

  /**
   * Configure and execute the associated {@link RequestFilter}. This class
   * may decorate the {@link Request} and {@link AsyncHandler}
   *
   * @param fc {@link FilterContext}
   * @return {@link FilterContext}
   */
  private <T> FilterContext<T> preProcessRequest(FilterContext<T> fc) throws FilterException {
    for (RequestFilter asyncFilter : config.getRequestFilters()) {
      fc = asyncFilter.filter(fc);
      assertNotNull(fc, "filterContext");
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
    fc = new FilterContext.FilterContextBuilder<>(fc).request(request).build();
    return fc;
  }

  public ChannelPool getChannelPool() {
    return channelManager.getChannelPool();
  }

  public EventLoopGroup getEventLoopGroup() {
    return channelManager.getEventLoopGroup();
  }

  @Override
  public ClientStats getClientStats() {
    return channelManager.getClientStats();
  }

  @Override
  public void flushChannelPoolPartitions(Predicate<Object> predicate) {
    getChannelPool().flushPartitions(predicate);
  }

  protected BoundRequestBuilder requestBuilder(String method, String url) {
    return new BoundRequestBuilder(this, method, config.isDisableUrlEncodingForBoundRequests()).setUrl(url).setSignatureCalculator(signatureCalculator);
  }

  protected BoundRequestBuilder requestBuilder(Request prototype) {
    return new BoundRequestBuilder(this, prototype).setSignatureCalculator(signatureCalculator);
  }

  @Override
  public AsyncHttpClientConfig getConfig() {
    return this.config;
  }
}
