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
import io.netty.handler.codec.http.HttpUtil;
import org.asynchttpclient.Request;

import java.net.InetSocketAddress;

import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;

/**
 * Default keep-alive strategy implementing standard HTTP 1.0/1.1 connection persistence behavior.
 * <p>
 * This implementation follows RFC 7230 section 6.1 for determining connection persistence.
 * It examines both request and response headers to determine if the connection should be
 * kept alive for reuse. The strategy considers:
 * </p>
 * <ul>
 *   <li>The {@code Connection} header in both request and response</li>
 *   <li>HTTP version (1.0 vs 1.1 default behavior)</li>
 *   <li>Non-standard {@code Proxy-Connection} header for compatibility</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create and use the default strategy
 * KeepAliveStrategy strategy = new DefaultKeepAliveStrategy();
 *
 * // In request processing
 * boolean shouldKeepAlive = strategy.keepAlive(
 *     remoteAddress,
 *     ahcRequest,
 *     nettyRequest,
 *     nettyResponse
 * );
 *
 * if (shouldKeepAlive) {
 *     // Connection will be reused - return to pool
 *     channelPool.offer(channel, partitionKey);
 * } else {
 *     // Connection will be closed
 *     channel.close();
 * }
 * }</pre>
 *
 * @see <a href="https://tools.ietf.org/html/rfc7230#section-6.1">RFC 7230 Section 6.1</a>
 */
public class DefaultKeepAliveStrategy implements KeepAliveStrategy {

  /**
   * Determines if the connection should be kept alive based on HTTP headers.
   * <p>
   * This implementation is in accordance with RFC 7230 section 6.1. The connection
   * is kept alive only if:
   * </p>
   * <ol>
   *   <li>The response indicates keep-alive support (via {@code Connection} header or HTTP/1.1 default)</li>
   *   <li>The request indicates keep-alive support</li>
   *   <li>The non-standard {@code Proxy-Connection} header does not indicate "close"</li>
   * </ol>
   *
   * @param remoteAddress the remote {@link InetSocketAddress} associated with the request
   * @param ahcRequest the {@link Request} object, as built by AsyncHttpClient
   * @param request the {@link HttpRequest} sent to Netty
   * @param response the {@link HttpResponse} received from Netty
   * @return {@code true} if the connection should be kept alive for reuse,
   *         {@code false} if it should be closed
   * @see <a href="https://tools.ietf.org/html/rfc7230#section-6.1">RFC 7230 Section 6.1</a>
   */
  @Override
  public boolean keepAlive(InetSocketAddress remoteAddress, Request ahcRequest, HttpRequest request, HttpResponse response) {
    return HttpUtil.isKeepAlive(response)
            && HttpUtil.isKeepAlive(request)
            // support non standard Proxy-Connection
            && !response.headers().contains("Proxy-Connection", CLOSE, true);
  }
}
