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
package org.asynchttpclient.test;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.Deflater;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpHeaderValues.DEFLATE;

public class EchoHandler extends AbstractHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(EchoHandler.class);

  @Override
  public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

    LOGGER.debug("Echo received request {} on path {}", request, pathInContext);

    if (httpRequest.getHeader("X-HEAD") != null) {
      httpResponse.setContentLength(1);
    }

    if (httpRequest.getHeader("X-ISO") != null) {
      httpResponse.setContentType(TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_ISO_8859_1_CHARSET);
    } else {
      httpResponse.setContentType(TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
    }

    if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
      httpResponse.addHeader("Allow", "GET,HEAD,POST,OPTIONS,TRACE");
    }

    Enumeration<String> e = httpRequest.getHeaderNames();
    String headerName;
    while (e.hasMoreElements()) {
      headerName = e.nextElement();
      if (headerName.startsWith("LockThread")) {
        final int sleepTime = httpRequest.getIntHeader(headerName);
        try {
          Thread.sleep(sleepTime == -1 ? 40 : sleepTime * 1000);
        } catch (InterruptedException ex) {
          //
        }
      }

      if (headerName.startsWith("X-redirect")) {
        httpResponse.sendRedirect(httpRequest.getHeader("X-redirect"));
        return;
      }
      if (headerName.startsWith("X-fail")) {
        byte[] body = "custom error message".getBytes(StandardCharsets.US_ASCII);
        httpResponse.addHeader(CONTENT_LENGTH.toString(), String.valueOf(body.length));
        httpResponse.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
        httpResponse.getOutputStream().write(body);
        httpResponse.getOutputStream().flush();
        httpResponse.getOutputStream().close();
        return;
      }
      httpResponse.addHeader("X-" + headerName, httpRequest.getHeader(headerName));
    }

    String pathInfo = httpRequest.getPathInfo();
    if (pathInfo != null)
      httpResponse.addHeader("X-pathInfo", pathInfo);

    String queryString = httpRequest.getQueryString();
    if (queryString != null)
      httpResponse.addHeader("X-queryString", queryString);

    httpResponse.addHeader("X-KEEP-ALIVE", httpRequest.getRemoteAddr() + ":" + httpRequest.getRemotePort());

    Cookie[] cs = httpRequest.getCookies();
    if (cs != null) {
      for (Cookie c : cs) {
        httpResponse.addCookie(c);
      }
    }

    Enumeration<String> i = httpRequest.getParameterNames();
    if (i.hasMoreElements()) {
      StringBuilder requestBody = new StringBuilder();
      while (i.hasMoreElements()) {
        headerName = i.nextElement();
        httpResponse.addHeader("X-" + headerName, httpRequest.getParameter(headerName));
        requestBody.append(headerName);
        requestBody.append("_");
      }

      if (requestBody.length() > 0) {
        String body = requestBody.toString();
        httpResponse.getOutputStream().write(body.getBytes());
      }
    }

    if (httpRequest.getHeader("X-COMPRESS") != null) {
      byte[] compressed = deflate(IOUtils.toByteArray(httpRequest.getInputStream()));
      httpResponse.addIntHeader(CONTENT_LENGTH.toString(), compressed.length);
      httpResponse.addHeader(CONTENT_ENCODING.toString(), DEFLATE.toString());
      httpResponse.getOutputStream().write(compressed, 0, compressed.length);

    } else {
      httpResponse.addHeader(TRANSFER_ENCODING.toString(), CHUNKED.toString());
      int size = 16384;
      if (httpRequest.getContentLength() > 0) {
        size = httpRequest.getContentLength();
      }
      if (size > 0) {
        int read = 0;
        while (read > -1) {
          byte[] bytes = new byte[size];
          read = httpRequest.getInputStream().read(bytes);
          if (read > 0) {
            httpResponse.getOutputStream().write(bytes, 0, read);
          }
        }
      }
    }

    request.setHandled(true);
    httpResponse.getOutputStream().flush();
    // FIXME don't always close, depends on the test, cf ReactiveStreamsTest
    httpResponse.getOutputStream().close();
  }

  private static byte[] deflate(byte[] input) throws IOException {
    Deflater compressor = new Deflater();
    compressor.setLevel(Deflater.BEST_COMPRESSION);

    compressor.setInput(input);
    compressor.finish();

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length)) {
      byte[] buf = new byte[1024];
      while (!compressor.finished()) {
        int count = compressor.deflate(buf);
        bos.write(buf, 0, count);
      }
      return bos.toByteArray();
    }
  }
}
