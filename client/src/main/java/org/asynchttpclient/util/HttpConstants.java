/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Constants for HTTP methods and response status codes.
 * <p>
 * This class provides convenient access to commonly used HTTP method names and
 * response status codes as defined in HTTP/1.1 specifications.
 * </p>
 */
public final class HttpConstants {

  private HttpConstants() {
  }

  /**
   * HTTP method name constants.
   * <p>
   * This class provides string constants for standard HTTP methods as defined in
   * RFC 7231 and related specifications.
   * </p>
   */
  public static final class Methods {
    /**
     * The CONNECT method establishes a tunnel to the server identified by the target resource.
     */
    public static final String CONNECT = HttpMethod.CONNECT.name();

    /**
     * The DELETE method deletes the specified resource.
     */
    public static final String DELETE = HttpMethod.DELETE.name();

    /**
     * The GET method requests a representation of the specified resource.
     */
    public static final String GET = HttpMethod.GET.name();

    /**
     * The HEAD method asks for a response identical to a GET request, but without the response body.
     */
    public static final String HEAD = HttpMethod.HEAD.name();

    /**
     * The OPTIONS method describes the communication options for the target resource.
     */
    public static final String OPTIONS = HttpMethod.OPTIONS.name();

    /**
     * The PATCH method applies partial modifications to a resource.
     */
    public static final String PATCH = HttpMethod.PATCH.name();

    /**
     * The POST method submits an entity to the specified resource.
     */
    public static final String POST = HttpMethod.POST.name();

    /**
     * The PUT method replaces all current representations of the target resource with the request payload.
     */
    public static final String PUT = HttpMethod.PUT.name();

    /**
     * The TRACE method performs a message loop-back test along the path to the target resource.
     */
    public static final String TRACE = HttpMethod.TRACE.name();

    private Methods() {
    }
  }

  /**
   * HTTP response status code constants.
   * <p>
   * This class provides integer constants for commonly used HTTP response status codes
   * as defined in RFC 7231 and related specifications.
   * </p>
   */
  public static final class ResponseStatusCodes {
    /**
     * 100 Continue - The server has received the request headers and the client should proceed to send the request body.
     */
    public static final int CONTINUE_100 = HttpResponseStatus.CONTINUE.code();

    /**
     * 101 Switching Protocols - The server is switching protocols as requested by the client.
     */
    public static final int SWITCHING_PROTOCOLS_101 = HttpResponseStatus.SWITCHING_PROTOCOLS.code();

    /**
     * 200 OK - The request has succeeded.
     */
    public static final int OK_200 = HttpResponseStatus.OK.code();

    /**
     * 301 Moved Permanently - The resource has been permanently moved to a new URI.
     */
    public static final int MOVED_PERMANENTLY_301 = HttpResponseStatus.MOVED_PERMANENTLY.code();

    /**
     * 302 Found - The resource temporarily resides under a different URI.
     */
    public static final int FOUND_302 = HttpResponseStatus.FOUND.code();

    /**
     * 303 See Other - The response can be found under a different URI using a GET method.
     */
    public static final int SEE_OTHER_303 = HttpResponseStatus.SEE_OTHER.code();

    /**
     * 307 Temporary Redirect - The resource temporarily resides under a different URI, and the request method should not change.
     */
    public static final int TEMPORARY_REDIRECT_307 = HttpResponseStatus.TEMPORARY_REDIRECT.code();

    /**
     * 308 Permanent Redirect - The resource has been permanently moved to a new URI, and the request method should not change.
     */
    public static final int PERMANENT_REDIRECT_308 = HttpResponseStatus.PERMANENT_REDIRECT.code();

    /**
     * 401 Unauthorized - The request requires user authentication.
     */
    public static final int UNAUTHORIZED_401 = HttpResponseStatus.UNAUTHORIZED.code();

    /**
     * 407 Proxy Authentication Required - The client must first authenticate itself with the proxy.
     */
    public static final int PROXY_AUTHENTICATION_REQUIRED_407 = HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED.code();

    private ResponseStatusCodes() {
    }
  }
}
