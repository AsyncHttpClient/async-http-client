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
package org.asynchttpclient.netty.handler;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequest;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.ws.NettyWebSocket;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketListener;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.asynchttpclient.ws.WebSocketUtils;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.SEC_WEBSOCKET_ACCEPT;
import static io.netty.handler.codec.http.HttpHeaderNames.SEC_WEBSOCKET_KEY;
import static io.netty.handler.codec.http.HttpHeaderNames.UPGRADE;
import static io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS;
import static org.asynchttpclient.ws.WebSocketUtils.getAcceptKey;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * A WebSocket upgrade response that lands on a future which is already finished must be dropped.
 * {@link NettyResponseFuture#abort} and {@link NettyResponseFuture#cancel} both mark the future done before
 * (cancel) or without (a direct abort) marking the channel discarded, so the event loop can still find the
 * future as the channel attribute and deliver the 101 into {@code handleRead}. Without a guard the handler
 * upgrades the pipeline and fires {@code onOpen} on a listener that has already had {@code onThrowable}:
 * the caller is told the request failed and then handed a live WebSocket.
 *
 * <p>The window is a race in production, so it is reproduced here at the handler seam, where the state can
 * be set up exactly. The two upgrade-still-works cases are not decoration: the guard MUST stay scoped to
 * the {@code HttpResponse} branch, because {@code upgrade()} ends with {@code future.done()} and a
 * whole-method guard would therefore drop every frame of every healthy WebSocket.
 */
public class WebSocketHandlerDoneFutureTest {

  private Timer timer;
  private ChannelManager channelManager;
  private WebSocketHandler handler;
  private EmbeddedChannel channel;
  private String webSocketKey;

  private final List<String> events = new CopyOnWriteArrayList<>();

  private final WebSocketListener listener = new WebSocketListener() {
    @Override
    public void onOpen(WebSocket websocket) {
      events.add("onOpen");
    }

    @Override
    public void onClose(WebSocket websocket, int code, String reason) {
      events.add("onClose");
    }

    @Override
    public void onError(Throwable t) {
      events.add("onError");
    }

    @Override
    public void onTextFrame(String payload, boolean finalFragment, int rsv) {
      events.add("onTextFrame:" + payload);
    }
  };

  // NettyRequest and AsyncHttpClientState are package-private to their own packages; a WebSocketHandler
  // cannot be exercised in isolation without them.
  private static NettyRequest newNettyRequest(HttpRequest httpRequest) throws Exception {
    Constructor<NettyRequest> constructor = NettyRequest.class.getDeclaredConstructor(HttpRequest.class,
            Class.forName("org.asynchttpclient.netty.request.body.NettyBody"));
    constructor.setAccessible(true);
    return constructor.newInstance(httpRequest, null);
  }

  private static AsyncHttpClientState newClientState() throws Exception {
    Constructor<AsyncHttpClientState> constructor = AsyncHttpClientState.class.getDeclaredConstructor(AtomicBoolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(new AtomicBoolean(false));
  }

  @BeforeMethod
  public void setUp() throws Exception {
    AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
    timer = new HashedWheelTimer();
    channelManager = new ChannelManager(config, timer);
    NettyRequestSender requestSender = new NettyRequestSender(config, channelManager, timer, newClientState());
    handler = new WebSocketHandler(config, channelManager, requestSender);

    channel = new EmbeddedChannel();
    // upgradePipelineForWebSockets() inserts the WebSocket codecs relative to the HTTP codec.
    channel.pipeline().addLast(ChannelManager.HTTP_CLIENT_CODEC, new HttpClientCodec());

    webSocketKey = WebSocketUtils.getWebSocketKey();
    events.clear();
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

  private NettyResponseFuture<NettyWebSocket> newFuture() throws Exception {
    HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    httpRequest.headers().set(SEC_WEBSOCKET_KEY, webSocketKey);

    Request request = new RequestBuilder("GET").setUrl("ws://localhost:8080/").build();
    WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler.Builder()
            .addWebSocketListener(listener)
            .build();

    NettyResponseFuture<NettyWebSocket> future = new NettyResponseFuture<>(request,
            upgradeHandler,
            newNettyRequest(httpRequest),
            0,
            ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE,
            null,
            null);
    Channels.setAttribute(channel, future);
    return future;
  }

  /**
   * A textbook-valid 101: nothing but the state of the future may stop the upgrade.
   */
  private HttpResponse newUpgradeResponse() {
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, SWITCHING_PROTOCOLS);
    response.headers()
            .set(UPGRADE, "websocket")
            .set(CONNECTION, "Upgrade")
            .set(SEC_WEBSOCKET_ACCEPT, getAcceptKey(webSocketKey));
    return response;
  }

  @Test
  public void doesNotUpgradeWhenTheFutureIsAlreadyDone() throws Exception {
    NettyResponseFuture<NettyWebSocket> future = newFuture();

    // What a request timeout leaves behind: the exchange is over and the listener has been told.
    future.abort(new TimeoutException("Request timeout"));
    assertTrue(future.isDone());
    assertEquals(events, java.util.Collections.singletonList("onError"));

    handler.handleRead(channel, future, newUpgradeResponse());

    assertFalse(events.contains("onOpen"),
            "onOpen must not fire after the future was aborted, but got: " + events);
    assertFalse(channel.isOpen(), "the orphaned channel must be closed rather than left upgraded");
  }

  @Test
  public void stillUpgradesWhenTheFutureIsLive() throws Exception {
    NettyResponseFuture<NettyWebSocket> future = newFuture();

    handler.handleRead(channel, future, newUpgradeResponse());

    assertTrue(events.contains("onOpen"), "a valid 101 on a live future must still upgrade, got: " + events);
    assertFalse(events.contains("onError"), "the successful upgrade must not report an error: " + events);
  }

  /**
   * upgrade() completes the future, so isDone() is true for the entire life of a healthy WebSocket. The
   * guard must not reach the frame branch.
   */
  @Test
  public void stillDeliversFramesAfterTheUpgradeCompletedTheFuture() throws Exception {
    NettyResponseFuture<NettyWebSocket> future = newFuture();
    handler.handleRead(channel, future, newUpgradeResponse());
    assertTrue(future.isDone(), "upgrade() is expected to complete the future");

    handler.handleRead(channel, future, new TextWebSocketFrame("hello"));

    assertTrue(events.contains("onTextFrame:hello"),
            "frames on an established WebSocket must still be delivered, got: " + events);
  }
}
