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

import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Embedded;
import org.apache.coyote.http11.Http11NioProtocol;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class WebDavBasicTest extends AbstractBasicTest {

    protected Embedded embedded;

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {

        embedded = new Embedded();
        String path = new File(".").getAbsolutePath();
        embedded.setCatalinaHome(path);

        Engine engine = embedded.createEngine();
        engine.setDefaultHost("localhost");

        Host host = embedded.createHost("localhost", path);
        engine.addChild(host);

        Context c = embedded.createContext("/", path);
        c.setReloadable(false);
        Wrapper w = c.createWrapper();
        w.addMapping("/*");
        w.setServletClass(org.apache.catalina.servlets.WebdavServlet.class.getName());
        w.addInitParameter("readonly", "false");
        w.addInitParameter("listings", "true");

        w.setLoadOnStartup(0);

        c.addChild(w);
        host.addChild(c);

        Connector connector = embedded.createConnector("localhost", 0, Http11NioProtocol.class.getName());
        connector.setContainer(host);
        embedded.addEngine(engine);
        embedded.addConnector(connector);
        embedded.start();
        port1 = connector.getLocalPort();
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws InterruptedException, Exception {
        embedded.stop();
    }

    protected String getTargetUrl() {
        return String.format("http://localhost:%s/folder1", port1);
    }

    @AfterMethod(alwaysRun = true)
    // FIXME not sure that's threadsafe
    public void clean() throws InterruptedException, Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            c.executeRequest(delete(getTargetUrl())).get();
        }
    }

    @Test(groups = "standalone")
    public void mkcolWebDavTest1() throws InterruptedException, IOException, ExecutionException {
        try (AsyncHttpClient c = asyncHttpClient()) {
            Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
            Response response = c.executeRequest(mkcolRequest).get();
            assertEquals(response.getStatusCode(), 201);
        }
    }

    @Test(groups = "standalone")
    public void mkcolWebDavTest2() throws InterruptedException, IOException, ExecutionException {
        try (AsyncHttpClient c = asyncHttpClient()) {
            Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl() + "/folder2").build();
            Response response = c.executeRequest(mkcolRequest).get();
            assertEquals(response.getStatusCode(), 409);
        }
    }

    @Test(groups = "standalone")
    public void basicPropFindWebDavTest() throws InterruptedException, IOException, ExecutionException {
        try (AsyncHttpClient c = asyncHttpClient()) {
            Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl()).build();
            Response response = c.executeRequest(propFindRequest).get();

            assertEquals(response.getStatusCode(), 404);
        }
    }

    @Test(groups = "standalone")
    public void propFindWebDavTest() throws InterruptedException, IOException, ExecutionException {
        try (AsyncHttpClient c = asyncHttpClient()) {
            Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
            Response response = c.executeRequest(mkcolRequest).get();
            assertEquals(response.getStatusCode(), 201);

            Request putRequest = put(String.format("http://localhost:%s/folder1/Test.txt", port1)).setBody("this is a test").build();
            response = c.executeRequest(putRequest).get();
            assertEquals(response.getStatusCode(), 201);

            Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(String.format("http://localhost:%s/folder1/Test.txt", port1)).build();
            response = c.executeRequest(propFindRequest).get();

            assertEquals(response.getStatusCode(), 207);
            assertTrue(response.getResponseBody().contains("HTTP/1.1 200 OK"), "Got " + response.getResponseBody());
        }
    }

    @Test(groups = "standalone")
    public void propFindCompletionHandlerWebDavTest() throws InterruptedException, IOException, ExecutionException {
        try (AsyncHttpClient c = asyncHttpClient()) {
            Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
            Response response = c.executeRequest(mkcolRequest).get();
            assertEquals(response.getStatusCode(), 201);

            Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl()).build();
            WebDavResponse webDavResponse = c.executeRequest(propFindRequest, new WebDavCompletionHandlerBase<WebDavResponse>() {
                /**
                 * {@inheritDoc}
                 */
                @Override
                public void onThrowable(Throwable t) {

                    t.printStackTrace();
                }

                @Override
                public WebDavResponse onCompleted(WebDavResponse response) throws Exception {
                    return response;
                }
            }).get();

            assertEquals(webDavResponse.getStatusCode(), 207);
            assertTrue(webDavResponse.getResponseBody().contains("HTTP/1.1 200 OK"), "Got " + response.getResponseBody());
        }
    }
}
