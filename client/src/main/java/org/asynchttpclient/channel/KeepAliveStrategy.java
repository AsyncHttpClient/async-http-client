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
package org.asynchttpclient.channel;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.asynchttpclient.Request;

import java.net.InetSocketAddress;

/**
 * Strategy interface for determining whether HTTP connections should be kept alive.
 * <p>
 * Keep-alive strategies control connection reuse by deciding whether a connection
 * should remain open after completing an HTTP request-response exchange. This allows
 * for custom policies beyond the standard HTTP keep-alive behavior, such as
 * considering server load, connection age, or application-specific requirements.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Using the default keep-alive strategy
 * KeepAliveStrategy strategy = new DefaultKeepAliveStrategy();
 *
 * // Check if connection should be kept alive
 * InetSocketAddress remoteAddress = ...;
 * Request ahcRequest = ...;
 * HttpRequest nettyRequest = ...;
 * HttpResponse nettyResponse = ...;
 *
 * boolean shouldKeepAlive = strategy.keepAlive(
 *     remoteAddress,
 *     ahcRequest,
 *     nettyRequest,
 *     nettyResponse
 * );
 *
 * if (shouldKeepAlive) {
 *     // Return channel to pool
 * } else {
 *     // Close the channel
 * }
 *
 * // Custom keep-alive strategy
 * KeepAliveStrategy customStrategy = (addr, ahcReq, nettyReq, nettyResp) -> {
 *     // Custom logic, e.g., never keep alive for certain hosts
 *     if (addr.getHostString().equals("no-keepalive.example.com")) {
 *         return false;
 *     }
 *     // Default to standard keep-alive behavior
 *     return HttpUtil.isKeepAlive(nettyResp) && HttpUtil.isKeepAlive(nettyReq);
 * };
 * }</pre>
 */
public interface KeepAliveStrategy {

  /**
   * Determines whether the connection should be kept alive after this HTTP message exchange.
   * <p>
   * Implementations should examine the request and response headers, connection state,
   * and any other relevant factors to decide if the connection can be safely reused
   * for subsequent requests. This method is called after each HTTP response is received
   * and before deciding whether to return the channel to the pool or close it.
   * </p>
   *
   * @param remoteAddress the remote {@link InetSocketAddress} associated with the request
   * @param ahcRequest the {@link Request} object, as built by AsyncHttpClient
   * @param nettyRequest the {@link HttpRequest} sent to Netty
   * @param nettyResponse the {@link HttpResponse} received from Netty
   * @return {@code true} if the connection should be kept alive for reuse,
   *         {@code false} if it should be closed
   */
  boolean keepAlive(InetSocketAddress remoteAddress, Request ahcRequest, HttpRequest nettyRequest, HttpResponse nettyResponse);
}
