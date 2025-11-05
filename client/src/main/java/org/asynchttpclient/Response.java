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

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.netty.NettyResponse;
import org.asynchttpclient.uri.Uri;

import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a complete HTTP response received from the server.
 * <p>
 * This interface provides access to all components of an HTTP response including
 * status code, headers, body, and metadata. Response instances are typically obtained
 * through {@link AsyncCompletionHandler} or by calling {@code Future.get()} on a request execution.
 * </p>
 * <p>
 * <b>Note:</b> When using {@link AsyncCompletionHandlerBase} or similar handlers, the entire
 * response body is buffered in memory. For streaming large responses, use {@link AsyncHandler}
 * with {@link HttpResponseBodyPart} callbacks instead.
 * </p>
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 * Future<Response> future = client.prepareGet("http://example.com").execute();
 * Response response = future.get();
 *
 * int statusCode = response.getStatusCode();
 * String contentType = response.getContentType();
 * String body = response.getResponseBody();
 * List<Cookie> cookies = response.getCookies();
 * }</pre>
 *
 * @see AsyncCompletionHandler
 * @see AsyncHttpClient
 */
public interface Response {
  /**
   * Returns the HTTP status code of the response.
   *
   * @return the HTTP status code (e.g., 200, 404, 500)
   */
  int getStatusCode();

  /**
   * Returns the HTTP status text (reason phrase) of the response.
   *
   * @return the status text (e.g., "OK", "Not Found", "Internal Server Error")
   */
  String getStatusText();

  /**
   * Returns the entire response body as a byte array.
   *
   * @return the complete response body as a byte array
   */
  byte[] getResponseBodyAsBytes();

  /**
   * Returns the entire response body as a ByteBuffer.
   *
   * @return the complete response body as a ByteBuffer
   */
  ByteBuffer getResponseBodyAsByteBuffer();

  /**
   * Returns an input stream for reading the response body.
   * <p>
   * <b>Important:</b> This stream should only be obtained once and should not be closed
   * by the caller as it is managed internally.
   * </p>
   *
   * @return an InputStream for reading the response body
   */
  InputStream getResponseBodyAsStream();

  /**
   * Returns the entire response body decoded as a string using the specified charset.
   *
   * @param charset the charset to use for decoding the response body
   * @return the response body as a string
   */
  String getResponseBody(Charset charset);

  /**
   * Returns the entire response body decoded as a string.
   * The charset is determined from the Content-Type header, or UTF-8 is used as default.
   *
   * @return the response body as a string
   */
  String getResponseBody();

  /**
   * Returns the final URI of the request.
   * <p>
   * If the request was redirected, this returns the URI of the final destination,
   * not the original request URI.
   * </p>
   *
   * @return the final request {@link Uri}
   */
  Uri getUri();

  /**
   * Returns the value of the Content-Type header.
   *
   * @return the Content-Type header value, or null if not present
   */
  String getContentType();

  /**
   * Returns the first value of the specified response header.
   *
   * @param name the header name (case-insensitive)
   * @return the first header value, or null if the header is not present
   */
  String getHeader(CharSequence name);

  /**
   * Returns all values of the specified response header.
   *
   * @param name the header name (case-insensitive)
   * @return a list of all header values, or an empty list if the header is not present
   */
  List<String> getHeaders(CharSequence name);

  /**
   * Returns all response headers.
   *
   * @return the complete collection of HTTP response headers
   */
  HttpHeaders getHeaders();

  /**
   * Indicates whether the response was the result of a redirect.
   *
   * @return true if the request was redirected, false otherwise
   */
  boolean isRedirected();

  /**
   * Returns a string representation of this response suitable for logging.
   * Implementations should include key information like status code and URI.
   *
   * @return the textual representation of this response
   */
  String toString();

  /**
   * Returns all cookies received in the response.
   *
   * @return the list of cookies, or an empty list if none
   */
  List<Cookie> getCookies();

  /**
   * Indicates whether the response status was received and processed.
   *
   * @return true if the status line was received, false if processing was aborted before receiving it
   */
  boolean hasResponseStatus();

  /**
   * Indicates whether the response headers were received and processed.
   * Returns false if processing was aborted during
   * {@link AsyncHandler#onStatusReceived(HttpResponseStatus)} or
   * {@link AsyncHandler#onHeadersReceived(HttpHeaders)}.
   *
   * @return true if the headers were received, false if processing was aborted before or during header reception
   */
  boolean hasResponseHeaders();

  /**
   * Indicates whether the response body was received and processed.
   * <p>
   * Returns false if:
   * <ul>
   *   <li>processing was aborted during {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}, or</li>
   *   <li>processing was aborted during {@link AsyncHandler#onHeadersReceived(HttpHeaders)}, or</li>
   *   <li>the response body was empty</li>
   * </ul>
   * </p>
   *
   * @return true if a non-empty response body was received, false otherwise
   */
  boolean hasResponseBody();

  /**
   * Returns the remote socket address that the client connected to.
   *
   * @return the remote socket address, or null if not available
   */
  SocketAddress getRemoteAddress();

  /**
   * Returns the local socket address that the client connected from.
   *
   * @return the local socket address, or null if not available
   */
  SocketAddress getLocalAddress();

  /**
   * Builder for accumulating response components and constructing a complete {@link Response}.
   * <p>
   * This builder is typically used internally by {@link AsyncCompletionHandler} to accumulate
   * the HTTP status, headers, and body parts as they arrive, then build the final Response object.
   * </p>
   */
  class ResponseBuilder {
    private final List<HttpResponseBodyPart> bodyParts = new ArrayList<>(1);
    private HttpResponseStatus status;
    private HttpHeaders headers;

    /**
     * Accumulates the HTTP response status.
     *
     * @param status the HTTP response status to store
     */
    public void accumulate(HttpResponseStatus status) {
      this.status = status;
    }

    /**
     * Accumulates HTTP response headers.
     * If headers were previously accumulated, the new headers are added to them.
     *
     * @param headers the HTTP headers to accumulate
     */
    public void accumulate(HttpHeaders headers) {
      this.headers = this.headers == null ? headers : this.headers.add(headers);
    }

    /**
     * Accumulates a response body part.
     * Empty body parts are filtered out and not stored.
     *
     * @param bodyPart the body part to accumulate (empty parts are ignored)
     */
    public void accumulate(HttpResponseBodyPart bodyPart) {
      if (bodyPart.length() > 0)
        bodyParts.add(bodyPart);
    }

    /**
     * Builds a complete {@link Response} from the accumulated components.
     *
     * @return a Response instance, or null if no status was accumulated
     */
    public Response build() {
      return status == null ? null : new NettyResponse(status, headers, bodyParts);
    }

    /**
     * Resets this builder to its initial empty state.
     * Clears all accumulated status, headers, and body parts.
     */
    public void reset() {
      bodyParts.clear();
      status = null;
      headers = null;
    }
  }
}
