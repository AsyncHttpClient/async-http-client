package com.ning.http.client.fancy;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.apache.log4j.BasicConfigurator;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

import static org.testng.AssertJUnit.assertEquals;


public class TestFancyClientBuilder
{
    private AsyncHttpClient asyncClient;
    private FancyClientBuilder builder;
    private Server server;
    private final Map<String, MiniHandler> results = Collections.synchronizedMap(new HashMap<String, MiniHandler>());
    private FooClient client;

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception
    {
        server = new Server();

        BasicConfigurator.configure();

        Connector listener = new SelectChannelConnector();
        listener.setHost("127.0.0.1");
        listener.setPort(12345);

        server.addConnector(listener);


        server.addConnector(listener);

        server.setHandler(new AbstractHandler() {

            @Override
            public void handle(String path, HttpServletRequest req, HttpServletResponse res, int dispatch)
                throws IOException, ServletException
            {
                res.setContentType("text/plain");
                if (results.containsKey(path)) {
                    res.setStatus(200);
                    results.get(path).handle(req, res);
                }
                else {
                    res.setStatus(404);
                }

                res.getOutputStream().flush();
            }
        });
        server.start();


        asyncClient = new AsyncHttpClient();
        builder = new FancyClientBuilder(asyncClient);

    }

    private interface MiniHandler {
        void handle(HttpServletRequest req, HttpServletResponse res) throws IOException;
    }

    private static class StringHandler implements MiniHandler{
        private final String res;

        private StringHandler(String res) {
            this.res = res;
        }

        @Override
        public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException
        {
            res.getOutputStream().write(this.res.getBytes());
        }
    }


    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @BeforeMethod(alwaysRun = true)
    public void setUp2() throws Exception
    {
        results.clear();
        client = builder.build(FooClient.class);
    }

    @Test
    public void testStuffWorks() throws Exception
    {
        results.put("/hello", new StringHandler("world"));
        Response r = asyncClient.prepareGet("http://localhost:12345/hello").execute().get();

        String rs = r.getResponseBody();
        assertEquals("world".length(), rs.length());
        assertEquals("world", rs);
    }

    @Test
    public void testReturnResponse() throws Exception
    {
        results.put("/", new StringHandler("world"));

        Future<Response> fr =  client.getRoot();
        Response r = fr.get();

        assertEquals("world", r.getResponseBody());
    }

    @Test
    public void testReturnString() throws Exception
    {
        results.put("/", new StringHandler("world"));

        Future<String> fr =  client.getRootAsString();
        assertEquals("world", fr.get());
    }

    @Test
    public void testQueryParam() throws Exception
    {
        results.put("/", new MiniHandler() {
            public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException
            {
                res.getOutputStream().write(req.getQueryString().getBytes());
            }
        });

        String rs = client.getRootWithParam("brian").get();

        assertEquals("name=brian", rs);

    }

    @BaseURL("http://localhost:12345")
    public interface FooClient
    {
        @GET("/")
        public Future<Response> getRoot();

        @GET("/")
        public Future<String> getRootAsString();

        @GET("/")
        public Future<String> getRootWithParam(@QueryParam("name") String name);
    }

}
