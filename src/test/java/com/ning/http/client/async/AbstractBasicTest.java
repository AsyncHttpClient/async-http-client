/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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
 */
package com.ning.http.client.async;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;

public class AbstractBasicTest {
  
    protected final static int PORT = 19999;
    protected Server server;
    protected final static Logger log = Logger.getLogger(AbstractBasicTest.class);

    public final static int TIMEOUT = 30;

    protected final static String TARGET_URL = "http://127.0.0.1:19999/foo/test";

    public static class EchoHandler extends AbstractHandler {

        /* @Override */
        public void handle(String pathInContext,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse,
                           int dispatch) throws ServletException, IOException {

            if (httpRequest.getHeader("X-HEAD") != null){
                httpResponse.setContentLength(1);
            }

            httpResponse.setContentType("text/html; charset=utf-8");
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

                if (param.startsWith("X-redirect")){
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

            javax.servlet.http.Cookie[] cs = httpRequest.getCookies();
            if (cs != null) {
                for (javax.servlet.http.Cookie c : cs) {
                    httpResponse.addCookie(c);
                }
            }

            if (requestBody.length() > 0) {
                httpResponse.getOutputStream().write(requestBody.toString().getBytes());
            }

            int size = 10 * 1024;
            if (httpRequest.getInputStream().available() > 0) {
                size = httpRequest.getInputStream().available();
            }
            byte[] bytes = new byte[size];
            if (bytes.length > 0) {
                httpRequest.getInputStream().read(bytes);
                httpResponse.getOutputStream().write(bytes);
            }


            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();

        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws InterruptedException, Exception {
        BasicConfigurator.resetConfiguration();
        server.stop();
    }

    public AbstractHandler configureHandler() throws Exception {
        return new EchoHandler();
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();
        BasicConfigurator.configure();        

        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(PORT);
                                                                                                 
        server.addConnector(listener);

        listener = new SelectChannelConnector();
        listener.setHost("127.0.0.1");
        listener.setPort(38080);

        server.addConnector(listener);

        server.setHandler(configureHandler());
        server.start();
        log.info("Local HTTP server started successfully");
    }

    public static class AsyncCompletionHandlerAdapter extends AsyncCompletionHandler<Response> {

        @Override
        public Response onCompleted(Response response) throws Exception {
            return response;
        }

        /* @Override */
        public void onThrowable(Throwable t) {
            t.printStackTrace();
            Assert.fail("Unexpected exception", t);
        }

    }

    public static class AsyncHandlerAdapter implements AsyncHandler<String> {


        /* @Override */
        public void onThrowable(Throwable t) {
            t.printStackTrace();
            Assert.fail("Unexpected exception", t);
        }

        /* @Override */
        public STATE onBodyPartReceived(final HttpResponseBodyPart<String> content) throws Exception {
            return STATE.CONTINUE;
        }

        /* @Override */
        public STATE onStatusReceived(final HttpResponseStatus<String> responseStatus) throws Exception {
            return STATE.CONTINUE;
        }

        /* @Override */
        public STATE onHeadersReceived(final HttpResponseHeaders<String> headers) throws Exception {
            return STATE.CONTINUE;
        }

        /* @Override */
        public String onCompleted() throws Exception {
            return "";
        }

    }
}
