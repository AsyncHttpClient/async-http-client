/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import com.ning.http.client.SimpleAsyncHttpClient;

public class SimpleAHCAuthTest extends AbstractBasicTest {

    protected final static String MY_MESSAGE = "my message";
    protected final static String user = "user";
    protected final static String admin = "admin";

    private Server server2;

    @BeforeClass(alwaysRun = true)
    @Override
    public void setUpGlobal() throws Exception {
        server = new Server();
        Logger root = Logger.getRootLogger();
        root.setLevel(Level.DEBUG);
        root.addAppender(new ConsoleAppender(
                new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN)));

        port1 = findFreePort();
        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port1);

        server.addConnector(listener);

        LoginService loginService = new HashLoginService("MyRealm", "src/test/resources/realm.properties");
        server.addBean(loginService);

        Constraint constraint = new Constraint();
        constraint.setName(Constraint.__BASIC_AUTH);
        constraint.setRoles(new String[]{user, admin});
        constraint.setAuthenticate(true);

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");

        Set<String> knownRoles = new HashSet<String>();
        knownRoles.add(user);
        knownRoles.add(admin);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.setConstraintMappings(new ConstraintMapping[]{mapping}, knownRoles);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);
        security.setStrict(false);
        security.setHandler(configureHandler());

        server.setHandler(security);
        server.start();
        log.info("Local HTTP server started successfully");
    }

    private void setUpSecondServer() throws Exception {
        server2 = new Server();
        port2 = findFreePort();

        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setHost("127.0.0.1");
        connector.setPort(port2);

        server2.addConnector(connector);

        LoginService loginService = new HashLoginService("MyRealm", "src/test/resources/realm.properties");
        server2.addBean(loginService);

        Constraint constraint = new Constraint();
        constraint.setName(Constraint.__DIGEST_AUTH);
        constraint.setRoles(new String[]{user, admin});
        constraint.setAuthenticate(true);

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");

        Set<String> knownRoles = new HashSet<String>();
        knownRoles.add(user);
        knownRoles.add(admin);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler() {

            @Override
            public void handle(String arg0, Request arg1, HttpServletRequest arg2, HttpServletResponse arg3)
                    throws IOException, ServletException {
                System.err.println("request in security handler");
                System.err.println("Authorization: " + arg2.getHeader("Authorization"));
                System.err.println("RequestUri: " + arg2.getRequestURI());
                super.handle(arg0, arg1, arg2, arg3);
            }
        };
        security.setConstraintMappings(new ConstraintMapping[]{mapping}, knownRoles);
        security.setAuthenticator(new DigestAuthenticator());
        security.setLoginService(loginService);
        security.setStrict(true);
        security.setHandler(new RedirectHandler());

        server2.setHandler(security);
        server2.start();
    }

    private void stopSecondServer() throws Exception {
        server2.stop();
    }

    private class RedirectHandler extends AbstractHandler {

        public void handle(String s,
                           Request r,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {

            System.err.println("redirecthandler");
            System.err.println("request: " + request.getRequestURI());
            if ("/uff".equals(request.getRequestURI())) {

                System.err.println("redirect to /bla");
                response.setStatus(302);
                response.setHeader("Location", "/bla");
                response.getOutputStream().flush();
                response.getOutputStream().close();

                return;

            } else {
                System.err.println("got redirected" + request.getRequestURI());
                response.addHeader("X-Auth", request.getHeader("Authorization"));
                response.addHeader("X-Content-Length", String.valueOf(request.getContentLength()));
                response.setStatus(200);
                response.getOutputStream().write("content".getBytes("UTF-8"));
                response.getOutputStream().flush();
                response.getOutputStream().close();
            }
        }
    }

    private class SimpleHandler extends AbstractHandler {
        public void handle(String s,
                           Request r,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {

            if (request.getHeader("X-401") != null) {
                response.setStatus(401);
                response.getOutputStream().flush();
                response.getOutputStream().close();

                return;
            }
            response.addHeader("X-Auth", request.getHeader("Authorization"));
            response.addHeader("X-Content-Length", String.valueOf(request.getContentLength()));
            response.setStatus(200);


            int size = 10 * 1024;
            if (request.getContentLength() > 0) {
                size = request.getContentLength();
            }
            byte[] bytes = new byte[size];
            if (bytes.length > 0) {
                int read = request.getInputStream().read(bytes);
                if (read > 0) {
                    response.getOutputStream().write(bytes, 0, read);
                }
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setRealmPrincipal(user).setRealmPassword(admin).setUrl(getTargetUrl()).build();

        Future<Response> f = client.get();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void redirectAndBasicAuthTest() throws Exception, ExecutionException, TimeoutException, InterruptedException {
        SimpleAsyncHttpClient client = null;
        try {
            setUpSecondServer();
            client = new SimpleAsyncHttpClient.Builder().setRealmPrincipal(user).setRealmPassword(admin).setFollowRedirects(true)
                    .setMaximumNumberOfRedirects(10).setUrl(getTargetUrl2()).build();

            Future<Response> f = client.get();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));

        } finally {
            if (client != null) {
                client.close();
            }
            stopSecondServer();
        }
    }

    @Override
    protected String getTargetUrl() {
        return "http://127.0.0.1:" + port1 + "/";
    }

    protected String getTargetUrl2() {
        return "http://127.0.0.1:" + port2 + "/uff";
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthTestPreemptiveTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setRealmPrincipal(user).setRealmPassword(admin).setRealmUsePreemptiveAuth(true)
                .setUrl(getTargetUrl()).build();

        Future<Response> f = client.get();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthNegativeTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setRealmPrincipal("fake").setRealmPassword(admin).setRealmUsePreemptiveAuth(true)
                .setUrl(getTargetUrl()).build();

        Future<Response> f = client.get();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), 401);
        client.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthTestDerivedRealm() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        SimpleAsyncHttpClient main = new SimpleAsyncHttpClient.Builder().setRealmUsePreemptiveAuth(true).setUrl(getTargetUrl()).build();

        SimpleAsyncHttpClient client = main.derive().build();
        Future<Response> f = client.get();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), 401);
        client.close();

        client = client.derive().setRealmPrincipal(user).setRealmPassword(admin).build();
        f = client.get();
        resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        client.close();

        client = client.derive().setRealmPrincipal("fake").setRealmPassword(admin).build();
        f = client.get();
        resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), 401);
        client.close();

        main.close();
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SimpleHandler();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void nonePreemptiveAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setRealmPrincipal(user).setRealmPassword(admin).setUrl(getTargetUrl()).build();

        Future<Response> f = client.get();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        client.close();
    }

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return null;
    }
}

