/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.test;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.Deflater;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
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

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            httpResponse.addHeader("Allow", "GET,HEAD,POST,OPTIONS,TRACE");
        }

        Enumeration<String> e = httpRequest.getHeaderNames();
        String headerName;
        while (e.hasMoreElements()) {
            headerName = e.nextElement();
            if (headerName.startsWith("LockThread")) {
                final int sleepTime = httpRequest.getIntHeader(headerName);
                try {
                    Thread.sleep(sleepTime == -1 ? 40 : sleepTime * 1000L);
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
        if (pathInfo != null) {
            httpResponse.addHeader("X-pathInfo", pathInfo);
        }

        String queryString = httpRequest.getQueryString();
        if (queryString != null) {
            httpResponse.addHeader("X-queryString", queryString);
        }

        httpResponse.addHeader("X-KEEP-ALIVE", httpRequest.getRemoteAddr() + ':' + httpRequest.getRemotePort());

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
                requestBody.append('_');
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
