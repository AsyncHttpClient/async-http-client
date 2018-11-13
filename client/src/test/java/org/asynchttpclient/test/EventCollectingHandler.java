/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.test;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.asynchttpclient.netty.request.NettyRequest;
import org.testng.Assert;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EventCollectingHandler extends AsyncCompletionHandlerBase {

  public static final String COMPLETED_EVENT = "Completed";
  public static final String STATUS_RECEIVED_EVENT = "StatusReceived";
  public static final String HEADERS_RECEIVED_EVENT = "HeadersReceived";
  public static final String HEADERS_WRITTEN_EVENT = "HeadersWritten";
  private static final String CONTENT_WRITTEN_EVENT = "ContentWritten";
  private static final String CONNECTION_OPEN_EVENT = "ConnectionOpen";
  private static final String HOSTNAME_RESOLUTION_EVENT = "HostnameResolution";
  private static final String HOSTNAME_RESOLUTION_SUCCESS_EVENT = "HostnameResolutionSuccess";
  private static final String HOSTNAME_RESOLUTION_FAILURE_EVENT = "HostnameResolutionFailure";
  private static final String CONNECTION_SUCCESS_EVENT = "ConnectionSuccess";
  private static final String CONNECTION_FAILURE_EVENT = "ConnectionFailure";
  private static final String TLS_HANDSHAKE_EVENT = "TlsHandshake";
  private static final String TLS_HANDSHAKE_SUCCESS_EVENT = "TlsHandshakeSuccess";
  private static final String TLS_HANDSHAKE_FAILURE_EVENT = "TlsHandshakeFailure";
  public static final String CONNECTION_POOL_EVENT = "ConnectionPool";
  public static final String CONNECTION_POOLED_EVENT = "ConnectionPooled";
  public static final String CONNECTION_OFFER_EVENT = "ConnectionOffer";
  public static final String REQUEST_SEND_EVENT = "RequestSend";
  private static final String RETRY_EVENT = "Retry";

  public Queue<String> firedEvents = new ConcurrentLinkedQueue<>();
  private CountDownLatch completionLatch = new CountDownLatch(1);

  public void waitForCompletion(int timeout, TimeUnit unit) throws InterruptedException {
    if (!completionLatch.await(timeout, unit)) {
      Assert.fail("Timeout out");
    }
  }

  @Override
  public Response onCompleted(Response response) throws Exception {
    firedEvents.add(COMPLETED_EVENT);
    try {
      return super.onCompleted(response);
    } finally {
      completionLatch.countDown();
    }
  }

  @Override
  public State onStatusReceived(HttpResponseStatus status) throws Exception {
    firedEvents.add(STATUS_RECEIVED_EVENT);
    return super.onStatusReceived(status);
  }

  @Override
  public State onHeadersReceived(HttpHeaders headers) throws Exception {
    firedEvents.add(HEADERS_RECEIVED_EVENT);
    return super.onHeadersReceived(headers);
  }

  @Override
  public State onHeadersWritten() {
    firedEvents.add(HEADERS_WRITTEN_EVENT);
    return super.onHeadersWritten();
  }

  @Override
  public State onContentWritten() {
    firedEvents.add(CONTENT_WRITTEN_EVENT);
    return super.onContentWritten();
  }

  @Override
  public void onTcpConnectAttempt(InetSocketAddress address) {
    firedEvents.add(CONNECTION_OPEN_EVENT);
  }

  @Override
  public void onTcpConnectSuccess(InetSocketAddress address, Channel connection) {
    firedEvents.add(CONNECTION_SUCCESS_EVENT);
  }

  @Override
  public void onTcpConnectFailure(InetSocketAddress address, Throwable t) {
    firedEvents.add(CONNECTION_FAILURE_EVENT);
  }

  @Override
  public void onHostnameResolutionAttempt(String name) {
    firedEvents.add(HOSTNAME_RESOLUTION_EVENT);
  }

  @Override
  public void onHostnameResolutionSuccess(String name, List<InetSocketAddress> addresses) {
    firedEvents.add(HOSTNAME_RESOLUTION_SUCCESS_EVENT);
  }

  @Override
  public void onHostnameResolutionFailure(String name, Throwable cause) {
    firedEvents.add(HOSTNAME_RESOLUTION_FAILURE_EVENT);
  }

  @Override
  public void onTlsHandshakeAttempt() {
    firedEvents.add(TLS_HANDSHAKE_EVENT);
  }

  @Override
  public void onTlsHandshakeSuccess(SSLSession sslSession) {
    Assert.assertNotNull(sslSession);
    firedEvents.add(TLS_HANDSHAKE_SUCCESS_EVENT);
  }

  @Override
  public void onTlsHandshakeFailure(Throwable cause) {
    firedEvents.add(TLS_HANDSHAKE_FAILURE_EVENT);
  }

  @Override
  public void onConnectionPoolAttempt() {
    firedEvents.add(CONNECTION_POOL_EVENT);
  }

  @Override
  public void onConnectionPooled(Channel connection) {
    firedEvents.add(CONNECTION_POOLED_EVENT);
  }

  @Override
  public void onConnectionOffer(Channel connection) {
    firedEvents.add(CONNECTION_OFFER_EVENT);
  }

  @Override
  public void onRequestSend(NettyRequest request) {
    firedEvents.add(REQUEST_SEND_EVENT);
  }

  @Override
  public void onRetry() {
    firedEvents.add(RETRY_EVENT);
  }
}
