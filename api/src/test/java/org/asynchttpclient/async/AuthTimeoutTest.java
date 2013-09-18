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
package org.asynchttpclient.async;

import static org.asynchttpclient.async.util.TestUtils.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public abstract class AuthTimeoutTest extends AbstractBasicTest {

    private Server server2;

    @BeforeClass(alwaysRun = true)
    @Override
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();
        port2 = findFreePort();

        server = newJettyHttpServer(port1);
        addBasicAuthHandler(server, false, configureHandler());
        server.start();

        server2 = newJettyHttpServer(port2);
        addDigestAuthHandler(server2, true, configureHandler());
        server2.start();

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
                response.setHeader("Content-Length", String.valueOf(content.getBytes("UTF-8").length));
                out.write(content.substring(1).getBytes("UTF-8"));
            } else {
                response.setStatus(200);
            }
            out.flush();
            out.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = false)
    public void basicAuthTimeoutTest() throws Exception {
        AsyncHttpClient client = newClient();
        try {
            Future<Response> f = execute(client, server, false);
            f.get();
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = false)
    public void basicPreemptiveAuthTimeoutTest() throws Exception {
        AsyncHttpClient client = newClient();
        try {
            Future<Response> f = execute(client, server, true);
            f.get();
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = false)
    public void digestAuthTimeoutTest() throws Exception {
        AsyncHttpClient client = newClient();
        try {
            Future<Response> f = execute(client, server2, false);
            f.get();
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = false)
    public void digestPreemptiveAuthTimeoutTest() throws Exception {
        AsyncHttpClient client = newClient();
        try {
            Future<Response> f = execute(client, server2, true);
            f.get();
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = false)
    public void basicFutureAuthTimeoutTest() throws Exception {
        AsyncHttpClient client = newClient();
        try {
            Future<Response> f = execute(client, server, false);
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = false)
    public void basicFuturePreemptiveAuthTimeoutTest() throws Exception {
        AsyncHttpClient client = newClient();
        try {
            Future<Response> f = execute(client, server, true);
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = false)
    public void digestFutureAuthTimeoutTest() throws Exception {
        AsyncHttpClient client = newClient();
        try {
            Future<Response> f = execute(client, server2, false);
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = false)
    public void digestFuturePreemptiveAuthTimeoutTest() throws Exception {
        AsyncHttpClient client = newClient();
        try {
            Future<Response> f = execute(client, server2, true);
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        } catch (Exception e) {
            inspectException(e);
        } finally {
            client.close();
        }
    }

    protected void inspectException(Throwable t) {
        assertNotNull(t.getCause());
        assertEquals(t.getCause().getClass(), IOException.class);
        if (!t.getCause().getMessage().startsWith("Remotely Closed")) {
            fail();
        }
    }

    private AsyncHttpClient newClient() {
        return getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setIdleConnectionInPoolTimeoutInMs(2000).setConnectionTimeoutInMs(20000).setRequestTimeoutInMs(2000).build());
    }

    protected Future<Response> execute(AsyncHttpClient client, Server server, boolean preemptive) throws IOException {
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl()).setRealm(realm(preemptive)).setHeader("X-Content", "Test");
        Future<Response> f = r.execute();
        return f;
    }

    private Realm realm(boolean preemptive) {
        return (new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).setUsePreemptiveAuth(preemptive).build();
    }

    @Override
    protected String getTargetUrl() {
        return "http://127.0.0.1:" + port1 + "/";
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new IncompleteResponseHandler();
    }
}
