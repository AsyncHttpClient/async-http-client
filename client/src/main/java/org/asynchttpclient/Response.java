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
 * Represents the asynchronous HTTP response callback for an {@link AsyncCompletionHandler}
 */
public interface Response {
  /**
   * Returns the status code for the request.
   *
   * @return The status code
   */
  int getStatusCode();

  /**
   * Returns the status text for the request.
   *
   * @return The status text
   */
  String getStatusText();

  /**
   * Return the entire response body as a byte[].
   *
   * @return the entire response body as a byte[].
   */
  byte[] getResponseBodyAsBytes();

  /**
   * Return the entire response body as a ByteBuffer.
   *
   * @return the entire response body as a ByteBuffer.
   */
  ByteBuffer getResponseBodyAsByteBuffer();

  /**
   * Returns an input stream for the response body. Note that you should not try to get this more than once, and that you should not close the stream.
   *
   * @return The input stream
   */
  InputStream getResponseBodyAsStream();

  /**
   * Return the entire response body as a String.
   *
   * @param charset the charset to use when decoding the stream
   * @return the entire response body as a String.
   */
  String getResponseBody(Charset charset);

  /**
   * Return the entire response body as a String.
   *
   * @return the entire response body as a String.
   */
  String getResponseBody();

  /**
   * Return the request {@link Uri}. Note that if the request got redirected, the value of the {@link Uri} will be the last valid redirect url.
   *
   * @return the request {@link Uri}.
   */
  Uri getUri();

  /**
   * Return the content-type header value.
   *
   * @return the content-type header value.
   */
  String getContentType();

  /**
   * @param name the header name
   * @return the first response header value
   */
  String getHeader(CharSequence name);

  /**
   * Return a {@link List} of the response header value.
   *
   * @param name the header name
   * @return the response header value
   */
  List<String> getHeaders(CharSequence name);

  HttpHeaders getHeaders();

  /**
   * Return true if the response redirects to another object.
   *
   * @return True if the response redirects to another object.
   */
  boolean isRedirected();

  /**
   * Subclasses SHOULD implement toString() in a way that identifies the response for logging.
   *
   * @return the textual representation
   */
  String toString();

  /**
   * @return the list of {@link Cookie}.
   */
  List<Cookie> getCookies();

  /**
   * Return true if the response's status has been computed by an {@link AsyncHandler}
   *
   * @return true if the response's status has been computed by an {@link AsyncHandler}
   */
  boolean hasResponseStatus();

  /**
   * Return true if the response's headers has been computed by an {@link AsyncHandler} It will return false if the either
   * {@link AsyncHandler#onStatusReceived(HttpResponseStatus)} or {@link AsyncHandler#onHeadersReceived(HttpHeaders)} returned {@link AsyncHandler.State#ABORT}
   *
   * @return true if the response's headers has been computed by an {@link AsyncHandler}
   */
  boolean hasResponseHeaders();

  /**
   * Return true if the response's body has been computed by an {@link AsyncHandler}. It will return false if the either {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}
   * or {@link AsyncHandler#onHeadersReceived(HttpHeaders)} returned {@link AsyncHandler.State#ABORT}
   *
   * @return true if the response's body has been computed by an {@link AsyncHandler}
   */
  boolean hasResponseBody();

  /**
   * Get remote address client initiated request to.
   *
   * @return remote address client initiated request to, may be {@code null} if asynchronous provider is unable to provide the remote address
   */
  SocketAddress getRemoteAddress();

  /**
   * Get local address client initiated request from.
   *
   * @return local address client initiated request from, may be {@code null} if asynchronous provider is unable to provide the local address
   */
  SocketAddress getLocalAddress();

  class ResponseBuilder {
    private final List<HttpResponseBodyPart> bodyParts = new ArrayList<>(1);
    private HttpResponseStatus status;
    private HttpHeaders headers;

    public void accumulate(HttpResponseStatus status) {
      this.status = status;
    }

    public void accumulate(HttpHeaders headers) {
      this.headers = this.headers == null ? headers : this.headers.add(headers);
    }

    /**
     * @param bodyPart a body part (possibly empty, but will be filtered out)
     */
    public void accumulate(HttpResponseBodyPart bodyPart) {
      if (bodyPart.length() > 0)
        bodyParts.add(bodyPart);
    }

    /**
     * Build a {@link Response} instance
     *
     * @return a {@link Response} instance
     */
    public Response build() {
      return status == null ? null : new NettyResponse(status, headers, bodyParts);
    }

    /**
     * Reset the internal state of this builder.
     */
    public void reset() {
      bodyParts.clear();
      status = null;
      headers = null;
    }
  }
}
