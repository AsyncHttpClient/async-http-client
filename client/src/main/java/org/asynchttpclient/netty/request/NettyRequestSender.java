/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.util.Timer;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.asynchttpclient.*;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.exception.PoolAlreadyClosedException;
import org.asynchttpclient.exception.RemotelyClosedException;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.handler.TransferCompletionHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.SimpleFutureListener;
import org.asynchttpclient.netty.channel.*;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.resolver.RequestHostnameResolver;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static java.util.Collections.singletonList;
import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.AuthenticatorUtils.perConnectionAuthorizationHeader;
import static org.asynchttpclient.util.AuthenticatorUtils.perConnectionProxyAuthorizationHeader;
import static org.asynchttpclient.util.HttpConstants.Methods.CONNECT;
import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.asynchttpclient.util.MiscUtils.getCause;
import static org.asynchttpclient.util.ProxyUtils.getProxyServer;

public final class NettyRequestSender {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);

  private final AsyncHttpClientConfig config;
  private final ChannelManager channelManager;
  private final ConnectionSemaphore connectionSemaphore;
  private final Timer nettyTimer;
  private final AsyncHttpClientState clientState;
  private final NettyRequestFactory requestFactory;

  public NettyRequestSender(AsyncHttpClientConfig config, //
                            ChannelManager channelManager, //
                            Timer nettyTimer, //
                            AsyncHttpClientState clientState) {
    this.config = config;
    this.channelManager = channelManager;
    this.connectionSemaphore = ConnectionSemaphore.newConnectionSemaphore(config);
    this.nettyTimer = nettyTimer;
    this.clientState = clientState;
    requestFactory = new NettyRequestFactory(config);
  }

  public <T> ListenableFuture<T> sendRequest(final Request request,
                                             final AsyncHandler<T> asyncHandler,
                                             NettyResponseFuture<T> future) {

    if (isClosed()) {
      throw new IllegalStateException("Closed");
    }

    validateWebSocketRequest(request, asyncHandler);

    ProxyServer proxyServer = getProxyServer(config, request);

    // WebSockets use connect tunneling to work with proxies
    if (proxyServer != null //
            && (request.getUri().isSecured() || request.getUri().isWebSocket()) //
            && !isConnectDone(request, future) //
            && proxyServer.getProxyType().isHttp()) {
      // Proxy with HTTPS or WebSocket: CONNECT for sure
      if (future != null && future.isConnectAllowed()) {
        // Perform CONNECT
        return sendRequestWithCertainForceConnect(request, asyncHandler, future, proxyServer, true);
      } else {
        // CONNECT will depend if we can pool or connection or if we have to open a new
        // one
        return sendRequestThroughSslProxy(request, asyncHandler, future, proxyServer);
      }
    } else {
      // no CONNECT for sure
      return sendRequestWithCertainForceConnect(request, asyncHandler, future, proxyServer, false);
    }
  }

  private boolean isConnectDone(Request request, NettyResponseFuture<?> future) {
    return future != null //
            && future.getNettyRequest() != null //
            && future.getNettyRequest().getHttpRequest().method() == HttpMethod.CONNECT //
            && !request.getMethod().equals(CONNECT);
  }

  /**
   * We know for sure if we have to force to connect or not, so we can build the
   * HttpRequest right away This reduces the probability of having a pooled
   * channel closed by the server by the time we build the request
   */
  private <T> ListenableFuture<T> sendRequestWithCertainForceConnect(//
                                                                     Request request, //
                                                                     AsyncHandler<T> asyncHandler, //
                                                                     NettyResponseFuture<T> future, //
                                                                     ProxyServer proxyServer, //
                                                                     boolean performConnectRequest) {

    NettyResponseFuture<T> newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer,
            performConnectRequest);

    Channel channel = getOpenChannel(future, request, proxyServer, asyncHandler);

    return Channels.isChannelActive(channel)
            ? sendRequestWithOpenChannel(newFuture, asyncHandler, channel)
            : sendRequestWithNewChannel(request, proxyServer, newFuture, asyncHandler);
  }

  /**
   * Using CONNECT depends on wither we can fetch a valid channel or not Loop
   * until we get a valid channel from the pool and it's still valid once the
   * request is built @
   */
  private <T> ListenableFuture<T> sendRequestThroughSslProxy(//
                                                             Request request, //
                                                             AsyncHandler<T> asyncHandler, //
                                                             NettyResponseFuture<T> future, //
                                                             ProxyServer proxyServer) {

    NettyResponseFuture<T> newFuture = null;
    for (int i = 0; i < 3; i++) {
      Channel channel = getOpenChannel(future, request, proxyServer, asyncHandler);

      if (channel == null) {
        // pool is empty
        break;
      }

      if (newFuture == null) {
        newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, false);
      }

      if (Channels.isChannelActive(channel)) {
        // if the channel is still active, we can use it,
        // otherwise, channel was closed by the time we computed the request, try again
        return sendRequestWithOpenChannel(newFuture, asyncHandler, channel);
      }
    }

    // couldn't poll an active channel
    newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, true);
    return sendRequestWithNewChannel(request, proxyServer, newFuture, asyncHandler);
  }

  private <T> NettyResponseFuture<T> newNettyRequestAndResponseFuture(final Request request,
                                                                      final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> originalFuture, ProxyServer proxy,
                                                                      boolean performConnectRequest) {

    Realm realm;
    if (originalFuture != null) {
      realm = originalFuture.getRealm();
    } else {
      realm = request.getRealm();
      if (realm == null) {
        realm = config.getRealm();
      }
    }

    Realm proxyRealm = null;
    if (originalFuture != null) {
      proxyRealm = originalFuture.getProxyRealm();
    } else if (proxy != null) {
      proxyRealm = proxy.getRealm();
    }

    NettyRequest nettyRequest = requestFactory.newNettyRequest(request, performConnectRequest, proxy, realm,
            proxyRealm);

    if (originalFuture == null) {
      NettyResponseFuture<T> future = newNettyResponseFuture(request, asyncHandler, nettyRequest, proxy);
      future.setRealm(realm);
      future.setProxyRealm(proxyRealm);
      return future;
    } else {
      originalFuture.setNettyRequest(nettyRequest);
      originalFuture.setCurrentRequest(request);
      return originalFuture;
    }
  }

  private Channel getOpenChannel(NettyResponseFuture<?> future, Request request, ProxyServer proxyServer,
                                 AsyncHandler<?> asyncHandler) {
    if (future != null && future.isReuseChannel() && Channels.isChannelActive(future.channel())) {
      return future.channel();
    } else {
      return pollPooledChannel(request, proxyServer, asyncHandler);
    }
  }

  private <T> ListenableFuture<T> sendRequestWithOpenChannel(NettyResponseFuture<T> future,
                                                             AsyncHandler<T> asyncHandler,
                                                             Channel channel) {

    try {
      asyncHandler.onConnectionPooled(channel);
    } catch (Exception e) {
      LOGGER.error("onConnectionPooled crashed", e);
      abort(channel, future, e);
      return future;
    }

    SocketAddress channelRemoteAddress = channel.remoteAddress();
    if (channelRemoteAddress != null) {
      // otherwise, bad luck, the channel was closed, see bellow
      scheduleRequestTimeout(future, (InetSocketAddress) channelRemoteAddress);
    }

    future.setChannelState(ChannelState.POOLED);
    future.attachChannel(channel, false);

    if (LOGGER.isDebugEnabled()) {
      HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
      LOGGER.debug("Using open Channel {} for {} '{}'", channel, httpRequest.method(), httpRequest.uri());
    }

    // channelInactive might be called between isChannelValid and writeRequest
    // so if we don't store the Future now, channelInactive won't perform
    // handleUnexpectedClosedChannel
    Channels.setAttribute(channel, future);

    if (Channels.isChannelActive(channel)) {
      writeRequest(future, channel);
    } else {
      // bad luck, the channel was closed in-between
      // there's a very good chance onClose was already notified but the
      // future wasn't already registered
      handleUnexpectedClosedChannel(channel, future);
    }

    return future;
  }

  private <T> ListenableFuture<T> sendRequestWithNewChannel(//
                                                            Request request, //
                                                            ProxyServer proxy, //
                                                            NettyResponseFuture<T> future, //
                                                            AsyncHandler<T> asyncHandler) {

    // some headers are only set when performing the first request
    HttpHeaders headers = future.getNettyRequest().getHttpRequest().headers();
    Realm realm = future.getRealm();
    Realm proxyRealm = future.getProxyRealm();
    requestFactory.addAuthorizationHeader(headers, perConnectionAuthorizationHeader(request, proxy, realm));
    requestFactory.setProxyAuthorizationHeader(headers, perConnectionProxyAuthorizationHeader(request, proxyRealm));

    future.setInAuth(realm != null && realm.isUsePreemptiveAuth() && realm.getScheme() != AuthScheme.NTLM);
    future.setInProxyAuth(
            proxyRealm != null && proxyRealm.isUsePreemptiveAuth() && proxyRealm.getScheme() != AuthScheme.NTLM);

    try {
      if (!channelManager.isOpen()) {
        throw PoolAlreadyClosedException.INSTANCE;
      }

      // Do not throw an exception when we need an extra connection for a
      // redirect.
      future.acquirePartitionLockLazily();
    } catch (Throwable t) {
      abort(null, future, getCause(t));
      // exit and don't try to resolve address
      return future;
    }

    resolveAddresses(request, proxy, future, asyncHandler)//
            .addListener(new SimpleFutureListener<List<InetSocketAddress>>() {

              @Override
              protected void onSuccess(List<InetSocketAddress> addresses) {
                NettyConnectListener<T> connectListener = new NettyConnectListener<>(future,
                        NettyRequestSender.this, channelManager, connectionSemaphore);
                NettyChannelConnector connector = new NettyChannelConnector(request.getLocalAddress(),
                        addresses, asyncHandler, clientState);
                if (!future.isDone()) {
                  // Do not throw an exception when we need an extra connection for a redirect
                  // FIXME why? This violate the max connection per host handling, right?
                  channelManager.getBootstrap(request.getUri(), request.getNameResolver(), proxy)
                          .addListener((Future<Bootstrap> whenBootstrap) -> {
                            if (whenBootstrap.isSuccess()) {
                              connector.connect(whenBootstrap.get(), connectListener);
                            } else {
                              abort(null, future, whenBootstrap.cause());
                            }
                          });
                }
              }

              @Override
              protected void onFailure(Throwable cause) {
                abort(null, future, getCause(cause));
              }
            });

    return future;
  }

  private <T> Future<List<InetSocketAddress>> resolveAddresses(Request request, //
                                                               ProxyServer proxy, //
                                                               NettyResponseFuture<T> future, //
                                                               AsyncHandler<T> asyncHandler) {

    Uri uri = request.getUri();
    final Promise<List<InetSocketAddress>> promise = ImmediateEventExecutor.INSTANCE.newPromise();

    if (proxy != null && !proxy.isIgnoredForHost(uri.getHost()) && proxy.getProxyType().isHttp()) {
      int port = uri.isSecured() ? proxy.getSecuredPort() : proxy.getPort();
      InetSocketAddress unresolvedRemoteAddress = InetSocketAddress.createUnresolved(proxy.getHost(), port);
      scheduleRequestTimeout(future, unresolvedRemoteAddress);
      return RequestHostnameResolver.INSTANCE.resolve(request.getNameResolver(), unresolvedRemoteAddress, asyncHandler);

    } else {
      int port = uri.getExplicitPort();

      if (request.getAddress() != null) {
        // bypass resolution
        InetSocketAddress inetSocketAddress = new InetSocketAddress(request.getAddress(), port);
        return promise.setSuccess(singletonList(inetSocketAddress));

      } else {
        InetSocketAddress unresolvedRemoteAddress = InetSocketAddress.createUnresolved(uri.getHost(), port);
        scheduleRequestTimeout(future, unresolvedRemoteAddress);
        return RequestHostnameResolver.INSTANCE.resolve(request.getNameResolver(), unresolvedRemoteAddress, asyncHandler);
      }
    }
  }

  private <T> NettyResponseFuture<T> newNettyResponseFuture(Request request, AsyncHandler<T> asyncHandler,
                                                            NettyRequest nettyRequest, ProxyServer proxyServer) {

    NettyResponseFuture<T> future = new NettyResponseFuture<>(//
            request, //
            asyncHandler, //
            nettyRequest, //
            config.getMaxRequestRetry(), //
            request.getChannelPoolPartitioning(), //
            connectionSemaphore, //
            proxyServer);

    String expectHeader = request.getHeaders().get(EXPECT);
    if (HttpHeaderValues.CONTINUE.contentEqualsIgnoreCase(expectHeader))
      future.setDontWriteBodyBecauseExpectContinue(true);
    return future;
  }

  public <T> void writeRequest(NettyResponseFuture<T> future, Channel channel) {

    NettyRequest nettyRequest = future.getNettyRequest();
    HttpRequest httpRequest = nettyRequest.getHttpRequest();
    AsyncHandler<T> asyncHandler = future.getAsyncHandler();

    // if the channel is dead because it was pooled and the remote server decided to
    // close it,
    // we just let it go and the channelInactive do its work
    if (!Channels.isChannelActive(channel))
      return;

    try {
      if (asyncHandler instanceof TransferCompletionHandler) {
        configureTransferAdapter(asyncHandler, httpRequest);
      }

      boolean writeBody = !future.isDontWriteBodyBecauseExpectContinue()
              && httpRequest.method() != HttpMethod.CONNECT && nettyRequest.getBody() != null;

      if (!future.isHeadersAlreadyWrittenOnContinue()) {
        try {
          asyncHandler.onRequestSend(nettyRequest);
        } catch (Exception e) {
          LOGGER.error("onRequestSend crashed", e);
          abort(channel, future, e);
          return;
        }

        // if the request has a body, we want to track progress
        if (writeBody) {
          // FIXME does this really work??? the promise is for the request without body!!!
          ChannelProgressivePromise promise = channel.newProgressivePromise();
          ChannelFuture f = channel.write(httpRequest, promise);
          f.addListener(new WriteProgressListener(future, true, 0L));
        } else {
          // we can just track write completion
          ChannelPromise promise = channel.newPromise();
          ChannelFuture f = channel.writeAndFlush(httpRequest, promise);
          f.addListener(new WriteCompleteListener(future));
        }
      }

      if (writeBody)
        nettyRequest.getBody().write(channel, future);

      // don't bother scheduling read timeout if channel became invalid
      if (Channels.isChannelActive(channel)) {
        scheduleReadTimeout(future);
      }

    } catch (Exception e) {
      LOGGER.error("Can't write request", e);
      abort(channel, future, e);
    }
  }

  private void configureTransferAdapter(AsyncHandler<?> handler, HttpRequest httpRequest) {
    HttpHeaders h = new DefaultHttpHeaders(false).set(httpRequest.headers());
    TransferCompletionHandler.class.cast(handler).headers(h);
  }

  private void scheduleRequestTimeout(NettyResponseFuture<?> nettyResponseFuture,
                                      InetSocketAddress originalRemoteAddress) {
    nettyResponseFuture.touch();
    TimeoutsHolder timeoutsHolder = new TimeoutsHolder(nettyTimer, nettyResponseFuture, this, config,
            originalRemoteAddress);
    nettyResponseFuture.setTimeoutsHolder(timeoutsHolder);
  }

  private void scheduleReadTimeout(NettyResponseFuture<?> nettyResponseFuture) {
    TimeoutsHolder timeoutsHolder = nettyResponseFuture.getTimeoutsHolder();
    if (timeoutsHolder != null) {
      // on very fast requests, it's entirely possible that the response has already
      // been completed
      // by the time we try to schedule the read timeout
      nettyResponseFuture.touch();
      timeoutsHolder.startReadTimeout();
    }
  }

  public void abort(Channel channel, NettyResponseFuture<?> future, Throwable t) {

    if (channel != null) {
      channelManager.closeChannel(channel);
    }

    if (!future.isDone()) {
      future.setChannelState(ChannelState.CLOSED);
      LOGGER.debug("Aborting Future {}\n", future);
      LOGGER.debug(t.getMessage(), t);
      future.abort(t);
    }
  }

  public void handleUnexpectedClosedChannel(Channel channel, NettyResponseFuture<?> future) {
    if (Channels.isActiveTokenSet(channel)) {
      if (future.isDone()) {
        channelManager.closeChannel(channel);
      } else if (future.incrementRetryAndCheck() && retry(future)) {
        future.pendingException = null;
      } else {
        abort(channel, future,
                future.pendingException != null ? future.pendingException : RemotelyClosedException.INSTANCE);
      }
    }
  }

  public boolean retry(NettyResponseFuture<?> future) {

    if (isClosed()) {
      return false;
    }

    if (future.isReplayPossible()) {
      future.setChannelState(ChannelState.RECONNECTED);

      LOGGER.debug("Trying to recover request {}\n", future.getNettyRequest().getHttpRequest());
      try {
        future.getAsyncHandler().onRetry();
      } catch (Exception e) {
        LOGGER.error("onRetry crashed", e);
        abort(future.channel(), future, e);
        return false;
      }

      try {
        sendNextRequest(future.getCurrentRequest(), future);
        return true;

      } catch (Exception e) {
        abort(future.channel(), future, e);
        return false;
      }
    } else {
      LOGGER.debug("Unable to recover future {}\n", future);
      return false;
    }
  }

  public boolean applyIoExceptionFiltersAndReplayRequest(NettyResponseFuture<?> future, IOException e,
                                                         Channel channel) {

    boolean replayed = false;

    @SuppressWarnings({"unchecked", "rawtypes"})
    FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler())
            .request(future.getCurrentRequest()).ioException(e).build();
    for (IOExceptionFilter asyncFilter : config.getIoExceptionFilters()) {
      try {
        fc = asyncFilter.filter(fc);
        assertNotNull(fc, "filterContext");
      } catch (FilterException efe) {
        abort(channel, future, efe);
      }
    }

    if (fc.replayRequest() && future.incrementRetryAndCheck() && future.isReplayPossible()) {
      future.setKeepAlive(false);
      replayRequest(future, fc, channel);
      replayed = true;
    }
    return replayed;
  }

  public <T> void sendNextRequest(final Request request, final NettyResponseFuture<T> future) {
    sendRequest(request, future.getAsyncHandler(), future);
  }

  private void validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
    Uri uri = request.getUri();
    boolean isWs = uri.isWebSocket();
    if (asyncHandler instanceof WebSocketUpgradeHandler) {
      if (!isWs) {
        throw new IllegalArgumentException(
                "WebSocketUpgradeHandler but scheme isn't ws or wss: " + uri.getScheme());
      } else if (!request.getMethod().equals(GET) && !request.getMethod().equals(CONNECT)) {
        throw new IllegalArgumentException(
                "WebSocketUpgradeHandler but method isn't GET or CONNECT: " + request.getMethod());
      }
    } else if (isWs) {
      throw new IllegalArgumentException("No WebSocketUpgradeHandler but scheme is " + uri.getScheme());
    }
  }

  private Channel pollPooledChannel(Request request, ProxyServer proxy, AsyncHandler<?> asyncHandler) {
    try {
      asyncHandler.onConnectionPoolAttempt();
    } catch (Exception e) {
      LOGGER.error("onConnectionPoolAttempt crashed", e);
    }

    Uri uri = request.getUri();
    String virtualHost = request.getVirtualHost();
    final Channel channel = channelManager.poll(uri, virtualHost, proxy, request.getChannelPoolPartitioning());

    if (channel != null) {
      LOGGER.debug("Using pooled Channel '{}' for '{}' to '{}'", channel, request.getMethod(), uri);
    }
    return channel;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) {

    Request newRequest = fc.getRequest();
    future.setAsyncHandler(fc.getAsyncHandler());
    future.setChannelState(ChannelState.NEW);
    future.touch();

    LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
    try {
      future.getAsyncHandler().onRetry();
    } catch (Exception e) {
      LOGGER.error("onRetry crashed", e);
      abort(channel, future, e);
      return;
    }

    channelManager.drainChannelAndOffer(channel, future);
    sendNextRequest(newRequest, future);
  }

  public boolean isClosed() {
    return clientState.isClosed();
  }

  public void drainChannelAndExecuteNextRequest(final Channel channel, final NettyResponseFuture<?> future,
                                                Request nextRequest) {
    Channels.setAttribute(channel, new OnLastHttpContentCallback(future) {
      @Override
      public void call() {
        sendNextRequest(nextRequest, future);
      }
    });
  }
}
