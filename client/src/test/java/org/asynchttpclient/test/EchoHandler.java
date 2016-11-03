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

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Enumeration;

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

        Enumeration<?> e = httpRequest.getHeaderNames();
        String param;
        while (e.hasMoreElements()) {
            param = e.nextElement().toString();

            if (param.startsWith("LockThread")) {
                try {
                    Thread.sleep(40 * 1000);
                } catch (InterruptedException ex) {
                }
            }

            if (param.startsWith("X-redirect")) {
                httpResponse.sendRedirect(httpRequest.getHeader("X-redirect"));
                return;
            }
            httpResponse.addHeader("X-" + param, httpRequest.getHeader(param));
        }

        Enumeration<?> i = httpRequest.getParameterNames();

        StringBuilder requestBody = new StringBuilder();
        while (i.hasMoreElements()) {
            param = i.nextElement().toString();
            httpResponse.addHeader("X-" + param, httpRequest.getParameter(param));
            requestBody.append(param);
            requestBody.append("_");
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

        if (requestBody.length() > 0) {
            httpResponse.getOutputStream().write(requestBody.toString().getBytes());
        }

        int size = 16384;
        if (httpRequest.getContentLength() > 0) {
            size = httpRequest.getContentLength();
        }
        byte[] bytes = new byte[size];
        if (bytes.length > 0) {
            int read = 0;
            while (read > -1) {
                read = httpRequest.getInputStream().read(bytes);
                if (read > 0) {
                    httpResponse.getOutputStream().write(bytes, 0, read);
                }
            }
        }

        request.setHandled(true);
        httpResponse.getOutputStream().flush();
        httpResponse.getOutputStream().close();
    }
}