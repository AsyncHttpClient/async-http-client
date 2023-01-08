/*
 *    Copyright (c) 2016-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.testserver;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.Closeable;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;

import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static org.asynchttpclient.test.TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_ISO_8859_1_CHARSET;
import static org.asynchttpclient.test.TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;

public class HttpServer implements Closeable {

    private final ConcurrentLinkedQueue<Handler> handlers = new ConcurrentLinkedQueue<>();
    private int httpPort;
    private int httpsPort;
    private Server server;

    public HttpServer() {
    }

    public HttpServer(int httpPort, int httpsPort) {
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;
    }

    public void start() throws Exception {
        server = new Server();

        ServerConnector httpConnector = addHttpConnector(server);
        if (httpPort != 0) {
            httpConnector.setPort(httpPort);
        }

        server.setHandler(new QueueHandler());
        ServerConnector httpsConnector = addHttpsConnector(server);
        if (httpsPort != 0) {
            httpsConnector.setPort(httpsPort);
        }

        server.start();

        httpPort = httpConnector.getLocalPort();
        httpsPort = httpsConnector.getLocalPort();
    }

    public void enqueue(Handler handler) {
        handlers.offer(handler);
    }

    public void enqueueOk() {
        enqueueResponse(response -> response.setStatus(200));
    }

    public void enqueueResponse(HttpServletResponseConsumer c) {
        handlers.offer(new ConsumerHandler(c));
    }

    public void enqueueEcho() {
        handlers.offer(new EchoHandler());
    }

    public void enqueueRedirect(int status, String location) {
        enqueueResponse(response -> {
            response.setStatus(status);
            response.setHeader(LOCATION.toString(), location);
        });
    }

    public int getHttpPort() {
        return httpPort;
    }

    public int getsHttpPort() {
        return httpsPort;
    }

    public String getHttpUrl() {
        return "http://localhost:" + httpPort;
    }

    public String getHttpsUrl() {
        return "https://localhost:" + httpsPort;
    }

    public void reset() {
        handlers.clear();
    }

    @Override
    public void close() throws IOException {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    @FunctionalInterface
    public interface HttpServletResponseConsumer {

        void apply(HttpServletResponse response) throws IOException, ServletException;
    }

    public abstract static class AutoFlushHandler extends AbstractHandler {

        private final boolean closeAfterResponse;

        AutoFlushHandler() {
            this(false);
        }

        AutoFlushHandler(boolean closeAfterResponse) {
            this.closeAfterResponse = closeAfterResponse;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            handle0(target, baseRequest, request, response);
            response.getOutputStream().flush();
            if (closeAfterResponse) {
                response.getOutputStream().close();
            }
        }

        protected abstract void handle0(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    }

    private static class ConsumerHandler extends AutoFlushHandler {

        private final HttpServletResponseConsumer c;

        ConsumerHandler(HttpServletResponseConsumer c) {
            this(c, false);
        }

        ConsumerHandler(HttpServletResponseConsumer c, boolean closeAfterResponse) {
            super(closeAfterResponse);
            this.c = c;
        }

        @Override
        protected void handle0(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            c.apply(response);
        }
    }

    public static class EchoHandler extends AutoFlushHandler {

        @Override
        protected void handle0(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            String delay = request.getHeader("X-Delay");
            if (delay != null) {
                try {
                    Thread.sleep(Long.parseLong(delay));
                } catch (NumberFormatException | InterruptedException e1) {
                    throw new ServletException(e1);
                }
            }

            response.setStatus(200);

            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.addHeader("Allow", "GET,HEAD,POST,OPTIONS,TRACE");
            }

            response.setContentType(request.getHeader("X-IsoCharset") != null ? TEXT_HTML_CONTENT_TYPE_WITH_ISO_8859_1_CHARSET : TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);

            response.addHeader("X-ClientPort", String.valueOf(request.getRemotePort()));

            String pathInfo = request.getPathInfo();
            if (pathInfo != null) {
                response.addHeader("X-PathInfo", pathInfo);
            }

            String queryString = request.getQueryString();
            if (queryString != null) {
                response.addHeader("X-QueryString", queryString);
            }

            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                response.addHeader("X-" + headerName, request.getHeader(headerName));
            }

            StringBuilder requestBody = new StringBuilder();
            for (Entry<String, String[]> e : baseRequest.getParameterMap().entrySet()) {
                response.addHeader("X-" + e.getKey(), URLEncoder.encode(e.getValue()[0], StandardCharsets.UTF_8));
            }

            Cookie[] cs = request.getCookies();
            if (cs != null) {
                for (Cookie c : cs) {
                    response.addCookie(c);
                }
            }

            if (requestBody.length() > 0) {
                response.getOutputStream().write(requestBody.toString().getBytes());
            }

            int size = 16384;
            if (request.getContentLength() > 0) {
                size = request.getContentLength();
            }
            if (size > 0) {
                int read = 0;
                while (read > -1) {
                    byte[] bytes = new byte[size];
                    read = request.getInputStream().read(bytes);
                    if (read > 0) {
                        response.getOutputStream().write(bytes, 0, read);
                    }
                }
            }
        }
    }

    private class QueueHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            Handler handler = handlers.poll();
            if (handler == null) {
                response.sendError(500, "No handler enqueued");
                response.getOutputStream().flush();
                response.getOutputStream().close();

            } else {
                handler.handle(target, baseRequest, request, response);
            }
        }
    }
}
