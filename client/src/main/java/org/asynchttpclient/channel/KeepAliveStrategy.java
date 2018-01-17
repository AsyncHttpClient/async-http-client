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

public interface KeepAliveStrategy {

  /**
   * Determines whether the connection should be kept alive after this HTTP message exchange.
   *
   * @param ahcRequest    the Request, as built by AHC
   * @param nettyRequest  the HTTP request sent to Netty
   * @param nettyResponse the HTTP response received from Netty
   * @return true if the connection should be kept alive, false if it should be closed.
   */
  boolean keepAlive(Request ahcRequest, HttpRequest nettyRequest, HttpResponse nettyResponse);
}
