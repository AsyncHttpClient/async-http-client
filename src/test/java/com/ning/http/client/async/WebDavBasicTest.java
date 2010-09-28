package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.webdav.WebDavCompletionHandlerBase;
import com.ning.http.client.webdav.WebDavResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Embedded;
import org.apache.coyote.http11.Http11NioProtocol;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class WebDavBasicTest extends AbstractBasicTest {

    public Embedded embedded;

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {

        System.setProperty("org.atmosphere.useNative", "true");

        int port = 8080;
        embedded = new Embedded();
        String path = new File(".").getAbsolutePath();
        embedded.setCatalinaHome(path);

        Engine engine = embedded.createEngine();
        engine.setDefaultHost("127.0.0.1");

        Host host = embedded.createHost("127.0.0.1", path);
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

        Connector connector = embedded.createConnector("127.0.0.1", port, Http11NioProtocol.class.getName());
        connector.setContainer(host);
        embedded.addEngine(engine);
        embedded.addConnector(connector);
        embedded.start();
    }

    @AfterMethod(alwaysRun = true)
    public void clean() throws InterruptedException, Exception {
        AsyncHttpClient c = new AsyncHttpClient();

        Request deleteRequest = new RequestBuilder("DELETE").setUrl("http://127.0.0.1:8080/folder1").build();
        c.executeRequest(deleteRequest).get();
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws InterruptedException, Exception {
        embedded.stop();
    }

    @Test
    public void mkcolWebDavTest1() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();
        Request mkcolRequest = new RequestBuilder("MKCOL").setUrl("http://127.0.0.1:8080/folder1").build();
        Response response =  c.executeRequest(mkcolRequest).get();

        assertEquals(response.getStatusCode(), 201);

    }

    @Test
    public void mkcolWebDavTest2() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();

        Request mkcolRequest = new RequestBuilder("MKCOL").setUrl("http://127.0.0.1:8080/folder1/folder2").build();
        Response response =  c.executeRequest(mkcolRequest).get();
        assertEquals(response.getStatusCode(), 409);
    }

    @Test
    public void basicPropFindWebDavTest() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();
        Request propFindRequest = new RequestBuilder("PROPFIND").setUrl("http://127.0.0.1:8080/folder1").build();
        Response response =  c.executeRequest(propFindRequest).get();

        assertEquals(response.getStatusCode(), 404);
    }

    @Test
    public void propFindWebDavTest() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();

        Request mkcolRequest = new RequestBuilder("MKCOL").setUrl("http://127.0.0.1:8080/folder1").build();
        Response response =  c.executeRequest(mkcolRequest).get();
        assertEquals(response.getStatusCode(), 201);

        Request putRequest = new RequestBuilder("PUT").setUrl("http://127.0.0.1:8080/folder1/Test.txt").setBody("this is a test").build();
        response =  c.executeRequest(putRequest).get();
        assertEquals(response.getStatusCode(), 201);
        
        Request propFindRequest = new RequestBuilder("PROPFIND").setUrl("http://127.0.0.1:8080/folder1/Test.txt").build();
        response =  c.executeRequest(propFindRequest).get();

        assertEquals(response.getStatusCode(), 207);
        assertTrue(response.getResponseBody().contains("<status>HTTP/1.1 200 OK</status>"));

    }

    @Test
    public void propFindCompletionHandlerWebDavTest() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();

        Request mkcolRequest = new RequestBuilder("MKCOL").setUrl("http://127.0.0.1:8080/folder1").build();
        Response response =  c.executeRequest(mkcolRequest).get();
        assertEquals(response.getStatusCode(), 201);

        Request propFindRequest = new RequestBuilder("PROPFIND").setUrl("http://127.0.0.1:8080/folder1/").build();
        WebDavResponse webDavResponse =  c.executeRequest(propFindRequest, new WebDavCompletionHandlerBase<WebDavResponse>() {

            @Override
            public WebDavResponse onCompleted(WebDavResponse response) throws Exception {
                return response;
            }
        }).get();

        assertEquals(webDavResponse.getStatusCode(), 200);
    }

}
