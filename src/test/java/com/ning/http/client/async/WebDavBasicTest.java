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
import static org.testng.Assert.assertNotNull;


public class WebDavBasicTest extends AbstractBasicTest {

    public Embedded embedded;

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {

        setUpLogger();

        port1 = findFreePort();
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

        Connector connector = embedded.createConnector("127.0.0.1", port1, Http11NioProtocol.class.getName());
        connector.setContainer(host);
        embedded.addEngine(engine);
        embedded.addConnector(connector);
        embedded.start();
    }
    
    protected String getTargetUrl() {
        return String.format("http://127.0.0.1:%s/folder1", port1);
    }

    @AfterMethod(alwaysRun = true)
    public void clean() throws InterruptedException, Exception {
        AsyncHttpClient c = new AsyncHttpClient();

        Request deleteRequest = new RequestBuilder("DELETE").setUrl(getTargetUrl()).build();
        c.executeRequest(deleteRequest).get();
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws InterruptedException, Exception {
        embedded.stop();
    }

    @Test(groups = "standalone")
    public void mkcolWebDavTest1() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();
        Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
        Response response =  c.executeRequest(mkcolRequest).get();

        assertEquals(response.getStatusCode(), 201);

    }

    @Test(groups = "standalone")
    public void mkcolWebDavTest2() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();

        Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl() +"/folder2").build();
        Response response =  c.executeRequest(mkcolRequest).get();
        assertEquals(response.getStatusCode(), 409);
    }

    @Test(groups = "standalone")
    public void basicPropFindWebDavTest() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();
        Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl()).build();
        Response response =  c.executeRequest(propFindRequest).get();

        assertEquals(response.getStatusCode(), 404);
    }

    @Test(groups = "standalone")
    public void propFindWebDavTest() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();

        Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
        Response response =  c.executeRequest(mkcolRequest).get();
        assertEquals(response.getStatusCode(), 201);

        Request putRequest = new RequestBuilder("PUT").setUrl(String.format("http://127.0.0.1:%s/folder1/Test.txt", port1)).setBody("this is a test").build();
        response =  c.executeRequest(putRequest).get();
        assertEquals(response.getStatusCode(), 201);

        Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(String.format("http://127.0.0.1:%s/folder1/Test.txt", port1)).build();
        response =  c.executeRequest(propFindRequest).get();

        assertEquals(response.getStatusCode(), 207);
        assertTrue(response.getResponseBody().contains("<status>HTTP/1.1 200 OK</status>"));

    }

    @Test(groups = "standalone")
    public void propFindCompletionHandlerWebDavTest() throws InterruptedException, IOException, ExecutionException {

        AsyncHttpClient c = new AsyncHttpClient();

        Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
        Response response =  c.executeRequest(mkcolRequest).get();
        assertEquals(response.getStatusCode(), 201);

        Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl()).build();
        WebDavResponse webDavResponse =  c.executeRequest(propFindRequest, new WebDavCompletionHandlerBase<WebDavResponse>() {
            /**
             * {@inheritDoc}
             */
            /* @Override */
            public void onThrowable(Throwable t) {
                t.printStackTrace();
            }

            @Override
            public WebDavResponse onCompleted(WebDavResponse response) throws Exception {
                return response;
            }
        }).get();

        assertNotNull(webDavResponse);
        assertEquals(webDavResponse.getStatusCode(), 200);
    }

}
