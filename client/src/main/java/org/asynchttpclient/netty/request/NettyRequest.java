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

import io.netty.handler.codec.http.HttpRequest;
import org.asynchttpclient.netty.request.body.NettyBody;

/**
 * Represents a complete Netty HTTP request with optional body.
 * <p>
 * This class wraps a Netty {@link HttpRequest} and its associated {@link NettyBody},
 * providing a unified representation of the request to be written to the channel.
 * </p>
 */
public final class NettyRequest {

  private final HttpRequest httpRequest;
  private final NettyBody body;

  /**
   * Constructs a new NettyRequest.
   *
   * @param httpRequest the Netty HTTP request
   * @param body the request body, or null for requests without a body
   */
  NettyRequest(HttpRequest httpRequest, NettyBody body) {
    this.httpRequest = httpRequest;
    this.body = body;
  }

  /**
   * Returns the Netty HTTP request.
   *
   * @return the HTTP request containing headers and metadata
   */
  public HttpRequest getHttpRequest() {
    return httpRequest;
  }

  /**
   * Returns the request body.
   *
   * @return the body to be written, or null if no body
   */
  public NettyBody getBody() {
    return body;
  }
}
