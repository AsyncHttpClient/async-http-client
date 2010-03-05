package com.ning.http.client.fancy;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.apache.log4j.BasicConfigurator;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Handler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.testng.annotations.AfterMethod;
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
    private final Map<String, String> results = Collections.synchronizedMap(new HashMap<String, String>());

    @BeforeMethod
    public void setUp() throws Exception
    {
        results.clear();
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
                    res.getOutputStream().write(results.get(path).getBytes());
                }
                else {
                    res.setStatus(404);
                }

                res.getOutputStream().flush();
                res.getOutputStream().close();
            }
        });
        server.start();


        asyncClient = new AsyncHttpClient();
        builder = new FancyClientBuilder(asyncClient);

    }


    @AfterMethod
    public void tearDown() throws Exception
    {
        server.stop();
    }


    @Test
    public void testStuffWorks() throws Exception
    {
        results.put("/hello", "world");
        Response r = asyncClient.prepareGet("http://localhost:12345/hello").execute().get();

        String rs = r.getResponseBody();
        assertEquals("world".length(), rs.length());
        assertEquals("world", rs);

    }

    @Test
    public void testFoo() throws Exception
    {
        FooClient client = builder.build(FooClient.class);
    }

    @BaseURL("http://localhost:")
    public interface FooClient
    {
        @GET("/")
        public Future<Response> getRoot();
    }

}
