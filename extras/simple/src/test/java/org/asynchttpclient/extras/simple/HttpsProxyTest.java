package org.asynchttpclient.extras.simple;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.Response;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.testng.Assert.assertEquals;

public class HttpsProxyTest extends AbstractBasicTest {

  private Server server2;

  public AbstractHandler configureHandler() {
    return new ConnectHandler();
  }

  @BeforeClass(alwaysRun = true)
  public void setUpGlobal() throws Exception {
    server = new Server();
    ServerConnector connector1 = addHttpConnector(server);
    server.setHandler(configureHandler());
    server.start();
    port1 = connector1.getLocalPort();

    server2 = new Server();
    ServerConnector connector2 = addHttpsConnector(server2);
    server2.setHandler(new EchoHandler());
    server2.start();
    port2 = connector2.getLocalPort();

    logger.info("Local HTTP server started successfully");
  }

  @AfterClass(alwaysRun = true)
  public void tearDownGlobal() throws Exception {
    server.stop();
    server2.stop();
  }

  @Test
  public void testSimpleAHCConfigProxy() throws IOException, InterruptedException, ExecutionException {

    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
            .setProxyHost("localhost")
            .setProxyPort(port1)
            .setFollowRedirect(true)
            .setUrl(getTargetUrl2())
            .setAcceptAnyCertificate(true)
            .setHeader("Content-Type", "text/html")
            .build()) {
      Response r = client.get().get();

      assertEquals(r.getStatusCode(), 200);
    }
  }
}
