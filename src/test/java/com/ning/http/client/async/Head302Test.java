package com.ning.http.client.async;

import com.ning.http.client.AsyncCompletionHandlerBase;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.RequestType;
import com.ning.http.client.Response;
import org.apache.log4j.BasicConfigurator;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests HEAD request that gets 302 response.
 *
 * @author Hubert Iwaniuk
 */
public class Head302Test extends AbstractBasicTest {
    /**
     * Handler that does Moved (302) in response to HEAD method.
     */
    private class Head302handler extends AbstractHandler {
        public void handle(String s,
                           HttpServletRequest request,
                           HttpServletResponse response,
                           int i) throws IOException, ServletException {
            if ("HEAD".equalsIgnoreCase(request.getMethod())) {
                if (request.getPathInfo().endsWith("_moved")) {
                    response.setStatus(HttpServletResponse.SC_OK);
                } else {
                    response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY); // 302
                    response.setHeader("Location", request.getPathInfo() + "_moved");
                }
            } else { // this handler is to handle HEAD reqeust
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
        }
    }

    @Test(enabled = false)
    public void testHEAD302() throws IOException, BrokenBarrierException, InterruptedException, ExecutionException, TimeoutException {
        AsyncHttpClient client = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder(RequestType.HEAD).setUrl("http://localhost:" + PORT + "/Test").build();

        client.executeRequest(request, new AsyncCompletionHandlerBase() {
            @Override
            public Response onCompleted(Response response) throws Exception {
                l.countDown();
                return super.onCompleted(response);
            }
        }).get(3, TimeUnit.SECONDS);

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();
        BasicConfigurator.configure();

        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(PORT);
        server.addConnector(listener);

        server.setHandler(new Head302handler());
        server.start();
        log.info("Local HTTP server started successfully");
    }
}