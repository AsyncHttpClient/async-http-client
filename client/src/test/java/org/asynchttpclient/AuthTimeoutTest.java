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
package org.asynchttpclient;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.*;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.exception.RemotelyClosedException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class AuthTimeoutTest extends AbstractBasicTest {

    private Server server2;

    @BeforeClass(alwaysRun = true)
    @Override
    public void setUpGlobal() throws Exception {

        server = new Server();
        ServerConnector connector1 = addHttpConnector(server);
        addBasicAuthHandler(server, configureHandler());
        server.start();
        port1 = connector1.getLocalPort();

        server2 = new Server();
        ServerConnector connector2 = addHttpConnector(server2);
        addDigestAuthHandler(server2, configureHandler());
        server2.start();
        port2 = connector2.getLocalPort();

        logger.info("Local HTTP server started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        super.tearDownGlobal();
        server2.stop();
    }

    private class IncompleteResponseHandler extends AbstractHandler {

        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            // NOTE: handler sends less bytes than are given in Content-Length, which should lead to timeout

            OutputStream out = response.getOutputStream();
            if (request.getHeader("X-Content") != null) {
                String content = request.getHeader("X-Content");
                response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(content.getBytes(UTF_8).length));
                out.write(content.substring(1).getBytes(UTF_8));
            } else {
                response.setStatus(200);
            }
            out.flush();
            out.close();
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void basicAuthTimeoutTest() throws Exception {
        try (AsyncHttpClient client = newClient()) {
            Future<Response> f = execute(client, server, false);
            f.get();
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void basicPreemptiveAuthTimeoutTest() throws Exception {
        try (AsyncHttpClient client = newClient()) {
            Future<Response> f = execute(client, server, true);
            f.get();
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void digestAuthTimeoutTest() throws Exception {
        try (AsyncHttpClient client = newClient()) {
            Future<Response> f = execute(client, server2, false);
            f.get();
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void digestPreemptiveAuthTimeoutTest() throws Exception {
        try (AsyncHttpClient client = newClient()) {
            Future<Response> f = execute(client, server2, true);
            f.get();
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void basicFutureAuthTimeoutTest() throws Exception {
        try (AsyncHttpClient client = newClient()) {
            Future<Response> f = execute(client, server, false);
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void basicFuturePreemptiveAuthTimeoutTest() throws Exception {
        try (AsyncHttpClient client = newClient()) {
            Future<Response> f = execute(client, server, true);
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void digestFutureAuthTimeoutTest() throws Exception {
        try (AsyncHttpClient client = newClient()) {
            Future<Response> f = execute(client, server2, false);
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void digestFuturePreemptiveAuthTimeoutTest() throws Exception {
        try (AsyncHttpClient client = newClient()) {
            Future<Response> f = execute(client, server2, true);
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        }
    }

    protected void inspectException(Throwable t) {
        assertEquals(t.getCause(), RemotelyClosedException.INSTANCE);
    }

    private AsyncHttpClient newClient() {
        return asyncHttpClient(config().setPooledConnectionIdleTimeout(2000).setConnectTimeout(20000).setRequestTimeout(2000));
    }

    protected Future<Response> execute(AsyncHttpClient client, Server server, boolean preemptive) throws IOException {
        return client.prepareGet(getTargetUrl()).setRealm(realm(preemptive)).setHeader("X-Content", "Test").execute();
    }

    private Realm realm(boolean preemptive) {
        return basicAuthRealm(USER, ADMIN).setUsePreemptiveAuth(preemptive).build();
    }

    @Override
    protected String getTargetUrl() {
        return "http://localhost:" + port1 + "/";
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new IncompleteResponseHandler();
    }
}
