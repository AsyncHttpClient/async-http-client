/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.webdav;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.apache.catalina.Context;
import org.apache.catalina.servlets.WebdavServlet;
import org.apache.catalina.startup.Tomcat;
import org.asynchttpclient.AsyncHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.util.Enumeration;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.delete;

public class WebdavTest {

    private Tomcat tomcat;
    private int port1;

    @SuppressWarnings("serial")
    @BeforeEach
    public void setUpGlobal() throws Exception {
        String path = new File(".").getAbsolutePath() + "/target";

        tomcat = new Tomcat();
        tomcat.setHostname("localhost");
        tomcat.setPort(0);
        tomcat.setBaseDir(path);
        Context ctx = tomcat.addContext("", path);

        Tomcat.addServlet(ctx, "webdav", new WebdavServlet() {
            @Override
            public void init(ServletConfig config) throws ServletException {

                super.init(new ServletConfig() {

                    @Override
                    public String getServletName() {
                        return config.getServletName();
                    }

                    @Override
                    public ServletContext getServletContext() {
                        return config.getServletContext();
                    }

                    @Override
                    public Enumeration<String> getInitParameterNames() {
                        // FIXME
                        return config.getInitParameterNames();
                    }

                    @Override
                    public String getInitParameter(String name) {
                        switch (name) {
                            case "readonly":
                                return "false";
                            case "listings":
                                return "true";
                            default:
                                return config.getInitParameter(name);
                        }
                    }
                });
            }

        });
        ctx.addServletMappingDecoded("/*", "webdav");
        tomcat.start();
        port1 = tomcat.getConnector().getLocalPort();
    }

    @AfterEach
    public void tearDownGlobal() throws Exception {
        tomcat.stop();
    }

    private String getTargetUrl() {
        return String.format("http://localhost:%s/folder1", port1);
    }

    @AfterEach
    public void clean() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            client.executeRequest(delete(getTargetUrl())).get();
        }
    }

//    @RepeatedIfExceptionsTest(repeats = 5)
//    public void mkcolWebDavTest1() throws Exception {
//        try (AsyncHttpClient client = asyncHttpClient()) {
//            Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
//            Response response = client.executeRequest(mkcolRequest).get();
//            assertEquals(201, response.getStatusCode());
//        }
//    }
//
//    @RepeatedIfExceptionsTest(repeats = 5)
//    public void mkcolWebDavTest2() throws Exception {
//        try (AsyncHttpClient client = asyncHttpClient()) {
//            Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl() + "/folder2").build();
//            Response response = client.executeRequest(mkcolRequest).get();
//            assertEquals(409, response.getStatusCode());
//        }
//    }
//
//    @RepeatedIfExceptionsTest(repeats = 5)
//    public void basicPropFindWebDavTest() throws Exception {
//        try (AsyncHttpClient client = asyncHttpClient()) {
//            Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl()).build();
//            Response response = client.executeRequest(propFindRequest).get();
//
//            assertEquals(404, response.getStatusCode());
//        }
//    }
//
//    @RepeatedIfExceptionsTest(repeats = 5)
//    public void propFindWebDavTest() throws Exception {
//        try (AsyncHttpClient client = asyncHttpClient()) {
//            Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
//            Response response = client.executeRequest(mkcolRequest).get();
//            assertEquals(201, response.getStatusCode());
//
//            Request putRequest = put(getTargetUrl() + "/Test.txt").setBody("this is a test").build();
//            response = client.executeRequest(putRequest).get();
//            assertEquals(201, response.getStatusCode());
//
//            Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl() + "/Test.txt").build();
//            response = client.executeRequest(propFindRequest).get();
//
//            assertEquals(207, response.getStatusCode());
//            String body = response.getResponseBody();
//            assertTrue(body.contains("HTTP/1.1 200"), "Got " + body);
//        }
//    }
//
//    @RepeatedIfExceptionsTest(repeats = 5)
//    public void propFindCompletionHandlerWebDavTest() throws Exception {
//        try (AsyncHttpClient c = asyncHttpClient()) {
//            Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
//            Response response = c.executeRequest(mkcolRequest).get();
//            assertEquals(201, response.getStatusCode());
//
//            Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl()).build();
//            WebDavResponse webDavResponse = c.executeRequest(propFindRequest, new WebDavCompletionHandlerBase<WebDavResponse>() {
//
//                @Override
//                public void onThrowable(Throwable t) {
//                    t.printStackTrace();
//                }
//
//                @Override
//                public WebDavResponse onCompleted(WebDavResponse response) {
//                    return response;
//                }
//            }).get();
//
//            assertEquals(207, webDavResponse.getStatusCode());
//            String body = webDavResponse.getResponseBody();
//            assertTrue(body.contains("HTTP/1.1 200"), "Got " + body);
//        }
//    }
}
