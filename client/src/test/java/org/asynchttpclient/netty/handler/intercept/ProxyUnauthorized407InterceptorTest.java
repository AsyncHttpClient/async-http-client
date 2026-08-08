/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequest;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * A 407 is an HTTP-proxy mechanism. A SOCKS proxy negotiates at the transport layer and never writes an
 * HTTP status line, so a 407 arriving over one was written by the ORIGIN - and answering it hands the
 * proxy's credentials to that origin.
 *
 * <p>The preemptive path is already gated by proxy type in NettyRequestFactory and NettyRequestSender (see
 * {@code SocksProxyCredentialLeakTest}), but this interceptor bypassed both: its NTLM and Kerberos/SPNEGO
 * branches write {@code Proxy-Authorization} straight onto the request headers, and {@code newNettyRequest}
 * copies request headers verbatim afterwards.
 */
public class ProxyUnauthorized407InterceptorTest {

  private Timer timer;
  private ChannelManager channelManager;
  private ProxyUnauthorized407Interceptor interceptor;
  private EmbeddedChannel channel;

  private static AsyncHttpClientState newClientState() throws Exception {
    Constructor<AsyncHttpClientState> constructor = AsyncHttpClientState.class.getDeclaredConstructor(AtomicBoolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(new AtomicBoolean(false));
  }

  private static NettyRequest newNettyRequest(HttpRequest httpRequest) throws Exception {
    Constructor<NettyRequest> constructor = NettyRequest.class.getDeclaredConstructor(HttpRequest.class,
            Class.forName("org.asynchttpclient.netty.request.body.NettyBody"));
    constructor.setAccessible(true);
    return constructor.newInstance(httpRequest, null);
  }

  @BeforeMethod
  public void setUp() throws Exception {
    AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
    timer = new HashedWheelTimer();
    channelManager = new ChannelManager(config, timer);
    NettyRequestSender requestSender = new NettyRequestSender(config, channelManager, timer, newClientState());
    interceptor = new ProxyUnauthorized407Interceptor(channelManager, requestSender);
    channel = new EmbeddedChannel();
  }

  @AfterMethod
  public void tearDown() {
    if (channel != null) {
      channel.finishAndReleaseAll();
    }
    if (channelManager != null) {
      channelManager.close();
    }
    if (timer != null) {
      timer.stop();
    }
  }

  private static Realm nonPreemptiveProxyRealm() {
    return basicAuthRealm("proxy-user", "proxy-secret").build();
  }

  private NettyResponseFuture<Response> newFuture(ProxyServer proxyServer, Realm proxyRealm) throws Exception {
    return newFuture(proxyServer, proxyRealm, "http://origin.example.com/resource", HttpMethod.GET);
  }

  private NettyResponseFuture<Response> newFuture(ProxyServer proxyServer,
                                                  Realm proxyRealm,
                                                  String url,
                                                  HttpMethod method) throws Exception {
    Request request = new RequestBuilder(method.name()).setUrl(url).build();
    HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method,
            method == HttpMethod.CONNECT ? "origin.example.com:443" : "/resource");
    AsyncHandler<Response> handler = new AsyncCompletionHandlerBase();

    NettyResponseFuture<Response> future = new NettyResponseFuture<>(request,
            handler,
            newNettyRequest(httpRequest),
            0,
            ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE,
            null,
            proxyServer);
    future.setProxyRealm(proxyRealm);
    return future;
  }

  private static HttpResponse new407() {
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED);
    response.headers().set(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic realm=\"proxy\"");
    return response;
  }

  private boolean handle407(ProxyServer proxyServer, Realm proxyRealm) throws Exception {
    NettyResponseFuture<Response> future = newFuture(proxyServer, proxyRealm);
    return interceptor.exitAfterHandling407(channel,
            future,
            new407(),
            future.getCurrentRequest(),
            proxyServer,
            future.getNettyRequest().getHttpRequest());
  }

  @Test
  public void socks5Proxy407IsNotAnswered() throws Exception {
    Realm proxyRealm = nonPreemptiveProxyRealm();
    ProxyServer socks = proxyServer("proxy.example.com", 1080).setProxyType(ProxyType.SOCKS_V5).setRealm(proxyRealm).build();

    assertFalse(handle407(socks, proxyRealm),
            "a 407 over a SOCKS proxy came from the origin and must not be answered with proxy credentials");
  }

  @Test
  public void socks4Proxy407IsNotAnswered() throws Exception {
    Realm proxyRealm = nonPreemptiveProxyRealm();
    ProxyServer socks = proxyServer("proxy.example.com", 1080).setProxyType(ProxyType.SOCKS_V4).setRealm(proxyRealm).build();

    assertFalse(handle407(socks, proxyRealm),
            "a 407 over a SOCKS proxy came from the origin and must not be answered with proxy credentials");
  }

  @Test
  public void noProxyAtAllMeans407CameFromTheOrigin() throws Exception {
    // A realm can be carried on the future without a ProxyServer being in play at all.
    assertFalse(handle407(null, nonPreemptiveProxyRealm()),
            "with no proxy configured a 407 can only have come from the origin");
  }

  /**
   * The regression guard: an HTTP proxy's 407 is still answered, and the proxy realm is switched to
   * preemptive so the retried request carries Proxy-Authorization.
   */
  @Test
  public void httpProxy407IsStillAnswered() throws Exception {
    Realm proxyRealm = nonPreemptiveProxyRealm();
    ProxyServer http = proxyServer("proxy.example.com", 8080).setRealm(proxyRealm).build();
    NettyResponseFuture<Response> future = newFuture(http, proxyRealm);

    boolean handled = interceptor.exitAfterHandling407(channel,
            future,
            new407(),
            future.getCurrentRequest(),
            http,
            future.getNettyRequest().getHttpRequest());

    assertTrue(handled, "an HTTP proxy's 407 must still be answered");
    assertTrue(future.getProxyRealm().isUsePreemptiveAuth(),
            "the retried request must carry the proxy credentials");
  }

  /**
   * Once a CONNECT has succeeded the peer on the far end of the socket is the ORIGIN, not the proxy, so a
   * 407 arriving there was written by the origin. The proxy type is still HTTP, which is why asking what
   * kind of proxy is configured does not answer the question that matters - who wrote this response.
   */
  @Test
  public void origin407InsideAnEstablishedTunnelIsNotAnswered() throws Exception {
    Realm proxyRealm = nonPreemptiveProxyRealm();
    ProxyServer http = proxyServer("proxy.example.com", 8080).setRealm(proxyRealm).build();
    NettyResponseFuture<Response> future = newFuture(http, proxyRealm, "https://origin.example.com/resource", HttpMethod.GET);
    future.setTunnelEstablished(true);

    boolean handled = interceptor.exitAfterHandling407(channel,
            future,
            new407(),
            future.getCurrentRequest(),
            http,
            future.getNettyRequest().getHttpRequest());

    assertFalse(handled,
            "a 407 seen inside an established tunnel was written by the origin and must not be answered "
                    + "with the proxy's credentials");
    assertFalse(future.getProxyRealm().isUsePreemptiveAuth(),
            "the proxy realm must not be armed to send Proxy-Authorization to the origin");
  }

  /**
   * The same request one exchange later. When the tunnelled channel comes from the pool the tunnel was
   * established by an EARLIER future, so tunnelEstablished is false on this one - but the target still
   * says what the socket must be: a secured or WebSocket target behind an HTTP proxy is only ever reached
   * through a CONNECT, so anything that is not the CONNECT itself is talking to the origin.
   */
  @Test
  public void origin407OnATunnelInheritedFromThePoolIsNotAnswered() throws Exception {
    Realm proxyRealm = nonPreemptiveProxyRealm();
    ProxyServer http = proxyServer("proxy.example.com", 8080).setRealm(proxyRealm).build();
    NettyResponseFuture<Response> future = newFuture(http, proxyRealm, "https://origin.example.com/resource", HttpMethod.GET);

    assertFalse(future.isTunnelEstablished(), "this exchange did not build the tunnel itself");
    assertFalse(interceptor.exitAfterHandling407(channel,
                    future,
                    new407(),
                    future.getCurrentRequest(),
                    http,
                    future.getNettyRequest().getHttpRequest()),
            "a secured target behind an HTTP proxy is reached through a tunnel, so a 407 on anything but "
                    + "the CONNECT came from the origin");
  }

  /**
   * The regression guard for the flow the 407 mechanism exists for: the proxy challenges the CONNECT
   * itself, which is addressed to it and travels in the clear.
   */
  @Test
  public void proxy407OnTheConnectIsStillAnswered() throws Exception {
    Realm proxyRealm = nonPreemptiveProxyRealm();
    ProxyServer http = proxyServer("proxy.example.com", 8080).setRealm(proxyRealm).build();
    NettyResponseFuture<Response> future = newFuture(http, proxyRealm, "https://origin.example.com/resource", HttpMethod.CONNECT);

    assertTrue(interceptor.exitAfterHandling407(channel,
                    future,
                    new407(),
                    future.getCurrentRequest(),
                    http,
                    future.getNettyRequest().getHttpRequest()),
            "the proxy's challenge to the CONNECT must still be answered");
    assertTrue(future.getProxyRealm().isUsePreemptiveAuth(),
            "the retried CONNECT must carry the proxy credentials");
  }

  /**
   * A 407 this interceptor refuses to answer must not consume the exchange's one-shot proxy-auth latch
   * either. Burning it means a later, legitimate proxy challenge on the same exchange is declined with
   * "auth was already performed" when no proxy auth was ever performed at all.
   */
  @Test
  public void a407ThisInterceptorRefusesDoesNotBurnTheProxyAuthLatch() throws Exception {
    Realm proxyRealm = nonPreemptiveProxyRealm();
    ProxyServer socks = proxyServer("proxy.example.com", 1080).setProxyType(ProxyType.SOCKS_V5).setRealm(proxyRealm).build();
    NettyResponseFuture<Response> future = newFuture(socks, proxyRealm);

    assertFalse(interceptor.exitAfterHandling407(channel,
            future,
            new407(),
            future.getCurrentRequest(),
            socks,
            future.getNettyRequest().getHttpRequest()));

    assertFalse(future.isInProxyAuth(),
            "declining a 407 must leave the proxy-auth latch untouched, since no proxy auth was performed");
  }
}
