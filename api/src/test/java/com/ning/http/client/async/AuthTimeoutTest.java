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
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Realm;
import com.ning.http.client.Response;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.security.Constraint;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

public abstract class AuthTimeoutTest
        extends AbstractBasicTest {

    private final static String user = "user";

    private final static String admin = "admin";

    protected AsyncHttpClient client;

    public void setUpServer(String auth)
            throws Exception {
        server = new Server();
        Logger root = Logger.getRootLogger();
        root.setLevel(Level.DEBUG);
        root.addAppender(new ConsoleAppender(new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN)));

        port1 = findFreePort();
        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port1);

        server.addConnector(listener);

        LoginService loginService = new HashLoginService("MyRealm", "src/test/resources/realm.properties");
        server.addBean(loginService);

        Constraint constraint = new Constraint();
        constraint.setName(auth);
        constraint.setRoles(new String[]{user, admin});
        constraint.setAuthenticate(true);

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");

        Set<String> knownRoles = new HashSet<String>();
        knownRoles.add(user);
        knownRoles.add(admin);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();

        List<ConstraintMapping> cm = new ArrayList<ConstraintMapping>();
        cm.add(mapping);

        security.setConstraintMappings(cm, knownRoles);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);
        security.setStrict(false);
        security.setHandler(configureHandler());

        server.setHandler(security);
        server.start();
        log.info("Local HTTP server started successfully");
    }

    private class SimpleHandler
            extends AbstractHandler {
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {

            // NOTE: handler sends less bytes than are given in Content-Length, which should lead to timeout

            OutputStream out = response.getOutputStream();
            if (request.getHeader("X-Content") != null) {
                String content = request.getHeader("X-Content");
                response.setHeader("Content-Length", String.valueOf(content.getBytes("UTF-8").length));
                out.write(content.substring(1).getBytes("UTF-8"));
                out.flush();
                out.close();
                return;
            }

            response.setStatus(200);
            out.flush();
            out.close();
        }
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = false)
    public void basicAuthTimeoutTest()
            throws Exception {
        setUpServer(Constraint.__BASIC_AUTH);

        Future<Response> f = execute(false);
        try {
            f.get();
            fail("expected timeout");
        }
        catch (Exception e) {
            inspectException(e);
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = false)
    public void basicPreemptiveAuthTimeoutTest()
            throws Exception {
        setUpServer(Constraint.__BASIC_AUTH);

        Future<Response> f = execute(true);
        try {
            f.get();
            fail("expected timeout");
        }
        catch (Exception e) {
            inspectException(e);
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = false)
    public void digestAuthTimeoutTest()
            throws Exception {
        setUpServer(Constraint.__DIGEST_AUTH);

        Future<Response> f = execute(false);
        try {
            f.get();
            fail("expected timeout");
        }
        catch (Exception e) {
            inspectException(e);
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = false)
    public void digestPreemptiveAuthTimeoutTest()
            throws Exception {
        setUpServer(Constraint.__DIGEST_AUTH);

        Future<Response> f = execute(true);
        try {
            f.get();
            fail("expected timeout");
        }
        catch (Exception e) {
            inspectException(e);
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = false)
    public void basicFutureAuthTimeoutTest()
            throws Exception {
        setUpServer(Constraint.__BASIC_AUTH);

        Future<Response> f = execute(false);
        try {
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        }
        catch (Exception e) {
            inspectException(e);
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = false)
    public void basicFuturePreemptiveAuthTimeoutTest()
            throws Exception {
        setUpServer(Constraint.__BASIC_AUTH);

        Future<Response> f = execute(true);
        try {
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        }
        catch (Exception e) {
            inspectException(e);
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = false)
    public void digestFutureAuthTimeoutTest()
            throws Exception {
        setUpServer(Constraint.__DIGEST_AUTH);

        Future<Response> f = execute(false);
        try {
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        }
        catch (Exception e) {
            inspectException(e);
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = false)
    public void digestFuturePreemptiveAuthTimeoutTest()
            throws Exception {
        setUpServer(Constraint.__DIGEST_AUTH);

        Future<Response> f = execute(true);
        try {
            f.get(1, TimeUnit.SECONDS);
            fail("expected timeout");
        }
        catch (Exception e) {
            inspectException(e);
        }
        client.close();
    }

    protected void inspectException(Throwable t) {
        assertNotNull(t.getCause());
        assertEquals(t.getCause().getClass(), IOException.class);
        if (!t.getCause().getMessage().startsWith("Remotely Closed")){
            fail();
        };
    }

    protected Future<Response> execute(boolean preemptive)
            throws IOException {
        client =
                getAsyncHttpClient(
                        new AsyncHttpClientConfig.Builder().setIdleConnectionInPoolTimeoutInMs(2000).setConnectionTimeoutInMs(20000).setRequestTimeoutInMs(2000).build());
        AsyncHttpClient.BoundRequestBuilder r =
                client.prepareGet(getTargetUrl()).setRealm(realm(preemptive)).setHeader("X-Content",
                        "Test");
        Future<Response> f = r.execute();
        return f;
    }

    private Realm realm(boolean preemptive) {
        return (new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).setUsePreemptiveAuth(preemptive).build();
    }

    @Override
    protected String getTargetUrl() {
        return "http://127.0.0.1:" + port1 + "/";
    }

    @Override
    public AbstractHandler configureHandler()
            throws Exception {
        return new SimpleHandler();
    }
}
