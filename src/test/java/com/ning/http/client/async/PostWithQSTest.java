package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Tests POST request with Query String.
 *
 * @author Hubert Iwaniuk
 */
public class PostWithQSTest extends AbstractBasicTest {

    /** POST with QS server part. */
    private class PostWithQSHandler extends AbstractHandler {
        public void handle(String s,
                           HttpServletRequest request,
                           HttpServletResponse response,
                           int i) throws IOException, ServletException {
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                String qs = request.getQueryString();
                ServletInputStream is = request.getInputStream();
                if (qs != null && !qs.equals("") && is.available() == 3) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    byte buf[] = new byte[is.available()];
                    is.readLine(buf, 0, is.available());
                    ServletOutputStream os = response.getOutputStream();
                    os.println(new String(buf));
                    os.flush();
                    os.close();
                } else {
                    response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE);
                }
            } else { // this handler is to handle POST request
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
        }
    }

    @Test
    public void postWithQS() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = new AsyncHttpClient();
        Future<Response> f = client.preparePost("http://localhost:" + PORT + "/?a=b").setBody("abc".getBytes()).execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();
        BasicConfigurator.configure();

        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(PORT);
        server.addConnector(listener);

        server.setHandler(new PostWithQSHandler());
        server.start();
        log.info("Local HTTP server started successfully");
    }
}
